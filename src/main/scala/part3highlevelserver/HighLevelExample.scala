package part3highlevelserver

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.stream.{Materializer, SystemMaterializer}

import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn
import scala.util.Try

object HighLevelExample {

  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "HighLevelIntro")
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val ec: ExecutionContext = system.executionContext



  def main(args: Array[String]): Unit = {
    val serverBindingFuture: Future[Http.ServerBinding] =
      Http().newServerAt("localhost", 8080)
        .bind(???)

    println("The server has been bound at http://localhost:8080/\n" +
      "Press Enter to close.")

    StdIn.readLine()
    println("Shutting down server...")

    serverBindingFuture
      .flatMap((_: Http.ServerBinding).unbind())
      .onComplete((_: Try[Done]) => system.terminate())
  }
}
