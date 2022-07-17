package part3highlevelserver

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{Materializer, SystemMaterializer}

import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn
import scala.util.Try

object HighLevelIntro {

  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "HighLevelIntro")
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val ec: ExecutionContext = system.executionContext

  // directives
  val simpleRoute: Route =
    path("home") { // DIRECTIVES
      complete(StatusCodes.OK)
    }

  val pathGetRoute: Route =
    path("home") {
      get {
        complete(StatusCodes.OK)
      }
      // POST -> Method Not Allow
    }

  // chaining directives with '~'
  val chainedRoute: Route =
    path("myEndpoint") {
      get {
        complete(StatusCodes.OK)
      } ~
      post {
        complete(StatusCodes.Forbidden)
      }
    } ~
    path("home") {
      complete(
        HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          "Hello from the high level AKKA HTTP server!"
        )
      )
    } // Routing tree

  def main(args: Array[String]): Unit = {
    val serverBindingFuture: Future[Http.ServerBinding] =
      Http().newServerAt("localhost", 8080)
        .bind(chainedRoute)

    println("The server has been bound at http://localhost:8080/\n" +
      "Press Enter to close.")

    StdIn.readLine()
    println("Shutting down server...")

    serverBindingFuture
      .flatMap((_: Http.ServerBinding).unbind())
      .onComplete((_: Try[Done]) => system.terminate())
  }
}
