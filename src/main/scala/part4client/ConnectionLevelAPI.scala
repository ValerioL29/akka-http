package part4client

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{Materializer, SystemMaterializer}
import com.typesafe.config.ConfigFactory
import part4client.PaymentSystemDomain._
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object ConnectionLevelAPI extends PaymentJsonProtocol with SprayJsonSupport {

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

  //  oneOffRequest(HttpRequest()).onComplete {
  //    case Success(response) => println(s"Got successful response: $response")
  //    case Failure(exception) => println(s"Sending the request failed: $exception")
  //  }
  //  scheduler.scheduleOnce(1 second, () => system.terminate())

  /**
   * Real-Production Scenario:
   * - A Small payments system
   */
  val creditCards: List[CreditCard] = List(
    CreditCard("4242-4242-4242-4242", "424", "tx-test-account"),
    CreditCard("1234-1234-1234-1234", "123", "tx-ken-account"),
    CreditCard("1234-1234-4321-4321", "321", "my-awesome-account"),
  )

  def main(args: Array[String]): Unit = {
    val httpRequests: List[HttpRequest] =
      creditCards
        .map((card: CreditCard) => PaymentRequest(card, "elapsed-store-account", 99))
        .map((request: PaymentRequest) =>
          HttpRequest(
            HttpMethods.POST,
            uri = Uri("/api/payments"),
            entity = HttpEntity(
              ContentTypes.`application/json`,
              request.toJson.prettyPrint
            )
          )
        )

    Source(httpRequests)
      .via(Http().outgoingConnection("localhost", 8080))
      .to(Sink.foreach[HttpResponse](println))
      .run()
  }
}
