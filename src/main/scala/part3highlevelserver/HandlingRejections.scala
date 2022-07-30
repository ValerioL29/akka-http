package part3highlevelserver

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.stream.{Materializer, SystemMaterializer}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Rejection, RejectionHandler, Route}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object HandlingRejections {

  implicit val system: ActorSystem[NotUsed] = ActorSystem(
    Behaviors.empty[NotUsed], "HandlingRejections",
    ConfigFactory.load().getConfig("my-config")
  )
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val timeout: Timeout = Timeout(5 seconds)
  implicit val scheduler: Scheduler = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  val simpleRoute: Route = path("api" / "rejections") {
    get {
      complete(StatusCodes.OK)
    } ~
      parameter(Symbol("id").as[String]) { _: String =>
        complete(StatusCodes.OK)
      }
  }

  // Rejection handlers
  val badRequestHandler: RejectionHandler = { rejections: Seq[Rejection] =>
    println(s"I have encountered rejections: $rejections")
    Some(complete(StatusCodes.BadRequest))
  }

  val forbiddenHandler: RejectionHandler = { rejections: Seq[Rejection] =>
    println(s"I have encountered rejections: $rejections")
    Some(complete(StatusCodes.Forbidden))
  }

  val simpleRouteWithHandlers: Route = handleRejections(badRequestHandler) { // handle rejections from the top level
    // define server logic inside
    path("api" / "myEndpoint") {
      get {
        complete(StatusCodes.OK)
      } ~
      post {
        handleRejections(forbiddenHandler) { // handle rejections WITHIN
          parameter(Symbol("param").as[String]) { _: String =>
            complete(StatusCodes.OK)
          }
        }
      }
    }
  }

  def main(args: Array[String]): Unit = {
    Http().newServerAt("localhost", 8080).bind(simpleRouteWithHandlers)
  }
}
