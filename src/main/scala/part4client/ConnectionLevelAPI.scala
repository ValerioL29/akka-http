package part4client

import akka.NotUsed
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{Materializer, SystemMaterializer}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

object ConnectionLevelAPI {

  implicit val system: ActorSystem[NotUsed] = ActorSystem(
    Behaviors.empty[NotUsed], "ConnectionLevelAPI",
    ConfigFactory.load().getConfig("my-config")
  )
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val scheduler: Scheduler = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  // Flow[-In, +Out, +Mat]
  val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
    Http().outgoingConnection("www.baidu.com")
  // Get back with a HttpResponse
  // It is materialized automatically
  def oneOffRequest(request: HttpRequest): Future[HttpResponse] =
    Source.single(request).via(connectionFlow).runWith(Sink.head)

  /**
   * Real-Production Scenario:
   * - A Small payments system
   */

  def main(args: Array[String]): Unit = {
    oneOffRequest(HttpRequest()).onComplete {
      case Success(response) => println(s"Got successful response: $response")
      case Failure(exception) => println(s"Sending the request failed: $exception")
    }

    scheduler.scheduleOnce(1 second, () => system.terminate())
  }
}
