package part2lowlevelserver

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.stream.{Materializer, SystemMaterializer}

import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn
import scala.util.Try

object LowLevelAPIExercise {

  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "LowLevelAPIExercise")
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val ec: ExecutionContext = system.executionContext

  /**
   *
   * Exercise: create your own HTTP server running on localhost on 8388, which replies
   * - with a welcome message on the "front door" localhost:8388
   * - with a proper HTML on localhost:8388/about
   * - with a 404 message otherwise
   */
  val asyncRequestHandler: HttpRequest => Future[HttpResponse] = {
    // HTTP Request(method, URI, HTTP headers, content, protocol)
    case HttpRequest(HttpMethods.GET, Uri.Path("/"), _, _, _) =>
      Future(
        HttpResponse(
          StatusCodes.OK, // HTTP 200
          entity = HttpEntity(
            ContentTypes.`text/plain(UTF-8)`,
            "front door"
          )
        )
      )
    case HttpRequest(HttpMethods.GET, Uri.Path("/about"), _, _, _) =>
      Future(
        HttpResponse(
          StatusCodes.OK, // HTTP 200
          entity = HttpEntity(
            ContentTypes.`text/html(UTF-8)`,
            """
              |<html>
              | <body>
              |   <div style="color: red;">
              |     This is the information page about this website.
              |   </div>
              | </body>
              |</html>
              |""".stripMargin
          )
        )
      )
    case HttpRequest(HttpMethods.GET, Uri.Path("/search"), _, _, _) =>
      Future(
        HttpResponse(
          StatusCodes.Found,
          headers = List(
            Location("https://www.baidu.com/")
          )
        )
      )
    case request: HttpRequest =>
      request.discardEntityBytes()
      Future(
        HttpResponse(
          StatusCodes.NotFound, // HTTP 404
          entity = HttpEntity(
            ContentTypes.`text/html(UTF-8)`,
            """
              |<html>
              | <body>
              |   OOPS! The resource can not be found.
              | </body>
              |</html>
              |""".stripMargin
          )
        )
      )
  }

  def main(args: Array[String]): Unit = {
    val bindingFuture: Future[Http.ServerBinding] =
      Http().newServerAt("localhost", 8388)
        .bind(asyncRequestHandler)

    println(
      "Server now online. Please navigate to http://localhost:8388/\n" +
      "Press ENTER to stop..."
    )

    StdIn.readLine()

    bindingFuture
      .flatMap((_: Http.ServerBinding).unbind()) // trigger unbinding from the port
      .onComplete((_: Try[Done]) => system.terminate()) // and shutdown when done
  }
}
