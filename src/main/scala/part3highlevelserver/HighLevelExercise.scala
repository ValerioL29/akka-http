package part3highlevelserver

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{Materializer, SystemMaterializer}
import com.typesafe.config.ConfigFactory
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Failure, Success}

case class Person(pin: Int, name: String)

trait PersonJsonProtocol extends DefaultJsonProtocol {
  implicit val personJson: RootJsonFormat[Person] = jsonFormat2(Person)
}

object HighLevelExercise extends PersonJsonProtocol {

  implicit val system: ActorSystem[NotUsed] = ActorSystem(
    Behaviors.empty[NotUsed], "HighLevelExercise",
    ConfigFactory.load().getConfig("my-config")
  )
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val ec: ExecutionContext = system.executionContext

  /**
   * Exercise:
   *
   * - GET /api/people: retrieve ALL the people you have registered
   *
   * - GET /api/people/pin: retrieve the person with that PIN, return as JSON
   *
   * - (harder) POST /api/people with a JSON payload denoting a Person, add that person to your database
   *
   * 1. extract the HTTP request's payload (entity)
   *
   * 2. extract the request
   *
   * 3. process the entity's data
   */
  var people: Vector[Person] = Vector(
    Person(1, "Alice"),
    Person(2, "Bob"),
    Person(3, "Charlie")
  )

  val personServerRoute: Route = pathPrefix("api" / "people") {
    get {
      (path(IntNumber) | parameter(Symbol("pin").as[Int])) { pin: Int =>
        complete(
          HttpEntity(
            ContentTypes.`application/json`,
            people.find((_: Person).pin == pin).toJson.prettyPrint
          )
        )
      } ~
        pathEndOrSingleSlash {
          complete(
            HttpEntity(
              ContentTypes.`application/json`,
              people.toJson.prettyPrint
            )
          )
        }
    } ~
    (post & pathEndOrSingleSlash & extractRequest & extractLog) { (request: HttpRequest, log: LoggingAdapter) =>
      val strictEntityFuture: Future[HttpEntity.Strict] = request.entity.toStrict(2 seconds)
      val personFuture: Future[Person] = strictEntityFuture.map((_: HttpEntity.Strict).data.utf8String.parseJson.convertTo[Person])
      onComplete(personFuture) {
        case Success(person) =>
          log.info(s"Got person: $person")
          people = people :+ person
          complete(StatusCodes.OK)
        case Failure(ex) =>
          failWith(ex)
      }
    }
  }

  def main(args: Array[String]): Unit = {
    Http().newServerAt("localhost", 8080).bind(personServerRoute)
  }
}
