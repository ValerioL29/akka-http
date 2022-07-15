package part2lowlevelserver

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.{Http, ServerBuilder}
import akka.http.scaladsl.Http.IncomingConnection
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.stream.scaladsl.{Flow, Sink}
import akka.stream.{Materializer, SystemMaterializer}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Failure, Success}

object LowLevelAPI {

  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "LowLevelAPI")
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val ec: ExecutionContext = system.executionContext

  /**
   * Method 1: synchronously serve HTTP responses
   */
  val syncRequestHandler: HttpRequest => HttpResponse = {
    case HttpRequest(HttpMethods.GET, _, _, _, _) =>
      HttpResponse(
        StatusCodes.OK, // HTTP 200
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |   Hello from AKKA HTTP!
            | </body>
            |</html>
            |""".stripMargin
        )
      )
    case request: HttpRequest =>
      request.discardEntityBytes()
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
  }


  //  val httpSyncConnectionHandler: Sink[IncomingConnection, Future[Done]] = Sink.foreach[IncomingConnection] { connection: IncomingConnection =>
  //    connection.handleWithSyncHandler(syncRequestHandler)
  //  }

  // shorthand version:
  //  Http().newServerAt("localhost", 8080)
  //    .bindSync(syncRequestHandler)

  /**
   * Method 2: serve back HTTP response ASYNCHRONOUSLY
   */
  val asyncRequestHandler: HttpRequest => Future[HttpResponse] = {
    // HTTP Request(method, URI, HTTP headers, content, protocol)
    case HttpRequest(HttpMethods.GET, Uri.Path("/home"), _, _, _) =>
      Future(
        HttpResponse(
          StatusCodes.OK, // HTTP 200
          entity = HttpEntity(
            ContentTypes.`text/html(UTF-8)`,
            """
              |<html>
              | <body>
              |   Hello from AKKA HTTP!
              | </body>
              |</html>
              |""".stripMargin
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

  val httpAsyncConnectionHandler: Sink[IncomingConnection, Future[Done]] = Sink.foreach[IncomingConnection] { connection: IncomingConnection =>
    connection.handleWithAsyncHandler(asyncRequestHandler)
  }
  //  Http().newServerAt("localhost", 8080)
  //    .connectionSource()
  //    .runWith(httpAsyncConnectionHandler)

  // shorthand version:
  //  Http().newServerAt("localhost", 8080)
  //    .bind(asyncRequestHandler)

  /**
   * Method 3: async via AKKA Streams
   */
  val streamBasedRequestHandler: Flow[HttpRequest, HttpResponse, _] =
    Flow[HttpRequest].map {
      case HttpRequest(HttpMethods.GET, _, _, _, _) =>
        HttpResponse(
          StatusCodes.OK, // HTTP 200
          entity = HttpEntity(
            ContentTypes.`text/html(UTF-8)`,
            """
              |<html>
              | <body>
              |   Hello from AKKA HTTP!
              | </body>
              |</html>
              |""".stripMargin
          )
        )
      case request: HttpRequest =>
        request.discardEntityBytes()
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
    }

  //    Http().newServerAt("localhost", 8080)
  //      .connectionSource()
  //      .runForeach { connection: IncomingConnection =>
  //        connection.handleWith(streamBasedRequestHandler)
  //      }

  // Shorthand version:
  //    Http().newServerAt("localhost", 8080)
  //      .bindFlow(streamBasedRequestHandler)

  def main(args: Array[String]): Unit = {
    /**
     * Warm up.
     */
    val serverSource: ServerBuilder =
      Http().newServerAt("localhost", 8080)

    val connectionSink: Sink[IncomingConnection, Future[Done]] =
      Sink.foreach[IncomingConnection] { connection: IncomingConnection =>
        println(s"Accepted incoming connection from: ${connection.remoteAddress}")
      }

    val serverBindingFuture: Future[Http.ServerBinding] =
      serverSource
        .connectionSource()
        .to(connectionSink)
        .run()

    serverBindingFuture.onComplete {
      case Success(binding) =>
        println("Server binding successful.")
        binding.terminate(2 seconds)
      case Failure(exception) => println(s"Server binding failed: $exception")
    }
  }
}
