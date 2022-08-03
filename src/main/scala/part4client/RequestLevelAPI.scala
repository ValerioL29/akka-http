package part4client

import akka.NotUsed
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, Uri}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Materializer, SystemMaterializer}
import com.typesafe.config.ConfigFactory
import spray.json._
import PaymentSystemDomain._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

/**
 * Request-Level API benefits:
 *
 * - the freedom from managing anything
 *
 * - dead simple
 */
object RequestLevelAPI extends PaymentJsonProtocol with SprayJsonSupport {

  implicit val system: ActorSystem[NotUsed] = ActorSystem(
    Behaviors.empty[NotUsed], "RequestLevelAPI",
    ConfigFactory.load().getConfig("my-config")
  )
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val scheduler: Scheduler = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  val responseFuture: Future[HttpResponse] =
    Http().singleRequest(HttpRequest(uri = "https://www.baidu.com"))

  responseFuture.onComplete {
    case Success(response) =>
      println(s"The request was successful and returned: $response")
    case Failure(exception) =>
      println(s"The request failed with: $exception")
  }

  val creditCards: List[CreditCard] = List(
    CreditCard("4242-4242-4242-4242", "424", "tx-test-account"),
    CreditCard("1234-1234-1234-1234", "123", "tx-ken-account"),
    CreditCard("1234-1234-4321-4321", "321", "my-awesome-account"),
  )

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
    .mapAsync(10)(Http().singleRequest(_: HttpRequest))
    .to(Sink.foreach(println))
    .run()

  def main(args: Array[String]): Unit = {
    system.scheduler.scheduleOnce(1 second, () => system.terminate())
  }
}
