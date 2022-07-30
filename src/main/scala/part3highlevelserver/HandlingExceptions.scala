package part3highlevelserver

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.stream.{Materializer, SystemMaterializer}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext

object HandlingExceptions {

  implicit val system: ActorSystem[NotUsed] = ActorSystem(
    Behaviors.empty[NotUsed], "HandlingExceptions",
    ConfigFactory.load().getConfig("my-config")
  )
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val ec: ExecutionContext = system.executionContext

  // 1. Implicitly exception handler
  implicit val customExceptionHandler: ExceptionHandler = ExceptionHandler { // throwable => Route
    case re: RuntimeException =>
      complete(StatusCodes.NotFound, re.getMessage)
    case ie: IllegalArgumentException =>
      complete(StatusCodes.BadRequest, ie.getMessage)
  }

  val simpleRoute: Route = path("api" / "people") {
    get {
      // directive that throws some exception
      throw new RuntimeException("Getting all the people took too long")
    } ~
    post {
      parameter(Symbol("id").as[String]) { id: String =>
        if (id.length > 2)
          throw new NoSuchElementException(s"Parameter $id cannot be found in the database, TABLE FLIP!")
        else
          complete(StatusCodes.OK)
      }
    }
  }

  // 2. Explicitly exception handler
  val runtimeExceptionHandler: ExceptionHandler = ExceptionHandler {
    case e: RuntimeException =>
      complete(StatusCodes.NotFound, e.getMessage)
  }
  val noSuchElementExceptionHandler: ExceptionHandler = ExceptionHandler {
    case e: NoSuchElementException =>
      complete(StatusCodes.BadRequest, e.getMessage)
  }

  val delicatedHandleRoute: Route = handleExceptions(runtimeExceptionHandler) {
    pathPrefix("api" / "people") {
      get {
        // directive that throws some exception
        throw new RuntimeException("Getting all the people took too long")
      } ~
      handleExceptions(noSuchElementExceptionHandler) {
        post {
          parameter(Symbol("id").as[String]) { id: String =>
            if (id.length > 2) // bobble up to find the first exception handler
              throw new NoSuchElementException(s"Parameter $id cannot be found in the database, TABLE FLIP!")
            else
              complete(StatusCodes.OK)
          }
        }
      }
    }
  }

  def main(args: Array[String]): Unit = {
    Http().newServerAt("localhost", 8080).bind(delicatedHandleRoute)
  }
}
