package part3highlevelserver

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, StatusCodes}
import akka.stream.{Materializer, SystemMaterializer}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route

import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn
import scala.util.Try

object DirectivesBreakdown {

  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "DirectivesBreakdown")
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val ec: ExecutionContext = system.executionContext

  /**
   * Type #1: Filtering directives
   */
  val simpleHttpMethodRoute: Route =
    post { // equivalent directives for get, put, patch, delete, head, options
      complete(StatusCodes.Forbidden)
    }

  val simplePathRoute: Route =
    path("about") {
      complete(
        HttpEntity(
          ContentTypes.`application/json`,
          """
            |<html>
            | <body>
            |   Hello from the about page!
            | </body>
            |</html>
            |""".stripMargin
        )
      )
    }

  val complexPathRoute: Route =
    path("api" / "myEndpoint") { // same as /api/myEndpoint
      complete(StatusCodes.OK)
    }

  val doNotConfuse: Route = // the path has been URL encoded, it will return 404 NOT FOUND at /api/myEndpoint
    path("api/myEndpoint") {
      complete(StatusCodes.OK)
    }

  val pathEndRoute: Route =
    pathEndOrSingleSlash { // localhost:8080 OR localhost:8080/
      complete(StatusCodes.OK)
    }

  /**
   * Type #2: extraction directives
   *
   */
  // GET on /api/item/42
  val pathExtractionRoute: Route =
    path("api" / "item" / IntNumber) { (itemNumber: Int) =>
      // other directives

      println(s"[INFO] I have got a number in my path: $itemNumber")
      complete(StatusCodes.OK)
    }

  val pathMultiExtractRoute: Route =
    path("api" / "order" / IntNumber / IntNumber) { (id: Int, inventory: Int) =>
      println(s"[INFO] I have got two numbers in my path: $id $inventory")
      complete(StatusCodes.OK)
    }

  val queryParamExtractionRoute: Route =
    // /api/item?id=45
    path("api" / "item") {
      parameter(Symbol("id").as[Int]) { itemId: Int =>
        println(s"[INFO] I have extracted the ID as $itemId")
        complete(StatusCodes.OK)
      }
    }

  val extractRequestRoute: Route =
    path("controlEndpoint") {
      extractRequest { httpRequest: HttpRequest =>
        extractLog { (log: LoggingAdapter) =>
          log.info(s"I got the http request: $httpRequest")
          complete(StatusCodes.OK)
        }
      }
    }

  /**
   * Type #
   *
   */

  def main(args: Array[String]): Unit = {
    val serverBindingFuture: Future[Http.ServerBinding] =
      Http().newServerAt("localhost", 8080)
        .bind(extractRequestRoute)

    println("The server has been bound at http://localhost:8080/\n" +
      "Press Enter to close.")

    StdIn.readLine()
    println("Shutting down server...")

    serverBindingFuture
      .flatMap((_: Http.ServerBinding).unbind())
      .onComplete((_: Try[Done]) => system.terminate())
  }
}
