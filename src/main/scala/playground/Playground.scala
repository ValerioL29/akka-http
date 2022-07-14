package playground

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives.{complete, pathEndOrSingleSlash}
import akka.http.scaladsl.server.Route
import akka.stream.Materializer

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.io.StdIn
import scala.util.Try

object Playground {

  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "AkkaHttpPlayground")
  implicit val mat: Materializer = Materializer(system)
  implicit val executionContext: ExecutionContextExecutor = system.executionContext

  val simpleRoute: Route =
    pathEndOrSingleSlash {
      complete(HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        """
          |<html>
          | <body>
          |   Hello world! - from Akka Http
          | </body>
          |</html>
          |""".stripMargin
      ))
    }

  def main(args: Array[String]): Unit = {
    val bindingFuture: Future[Http.ServerBinding] = Http()
      .newServerAt("localhost", 8989)
      .bind(simpleRoute)

    println(
      "Server now online. Please navigate to http://localhost:8989/\n" +
      "Press RETURN to stop..."
    )

    StdIn.readLine() // let it run until user presses return

    bindingFuture
      .flatMap((_: Http.ServerBinding).unbind()) // trigger unbinding from the port
      .onComplete((_: Try[Done]) => system.terminate()) // and shutdown when done
  }
}
