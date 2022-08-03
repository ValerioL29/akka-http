package part4client

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{Materializer, SystemMaterializer}
import com.typesafe.config.ConfigFactory
import part4client.PaymentSystemDomain._
import spray.json._

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
 * Host-Level API benefits:
 *
 * - The freedom from managing individual connections
 *
 * - Best for high-volume, short-lived requests
 *
 * Do not use the host-level API for:
 *
 * - one-off requests (use the request-level API)
 *
 * - long-lived requests (use the connection-level API)
 *
 */
object HostLevelAPI extends PaymentJsonProtocol with SprayJsonSupport {

  implicit val system: ActorSystem[NotUsed] = ActorSystem(
    Behaviors.empty[NotUsed], "HostLevelAPI",
    ConfigFactory.load().getConfig("my-config")
  )
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val scheduler: Scheduler = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  // Flow[-In, +Out, +Mat]
  val poolFlow: Flow[(HttpRequest, Int), (Try[HttpResponse], Int), Http.HostConnectionPool] =
    Http().cachedHostConnectionPool[Int]("www.baidu.com")

  def main(args: Array[String]): Unit = {
    Source(1 to 10)
      .map((i: Int) => (HttpRequest(), i))
      .via(poolFlow)
      .map {
        case (Success(response), value) =>
          // VERY IMPORTANT
          response.discardEntityBytes() // If not, it will leak the connection from connection pools
          s"Request $value has received response: $response"
        case (Failure(ex), value) =>
          s"Request $value has failed: $ex"
      }
      // .runWith(Sink.foreach[String](println))

    val creditCards: List[CreditCard] = List(
      CreditCard("4242-4242-4242-4242", "424", "tx-test-account"),
      CreditCard("1234-1234-1234-1234", "123", "tx-ken-account"),
      CreditCard("1234-1234-4321-4321", "321", "my-awesome-account"),
    )

    val httpRequests: List[(HttpRequest, String)] =
      creditCards
        .map((card: CreditCard) => PaymentRequest(card, "elapsed-store-account", 99))
        .map((request: PaymentRequest) =>
          (HttpRequest(
            HttpMethods.POST,
            uri = Uri("/api/payments"),
            entity = HttpEntity(
              ContentTypes.`application/json`,
              request.toJson.prettyPrint
            )
          ),
          UUID.randomUUID().toString)
        )

    Source(httpRequests)
      .via(Http().cachedHostConnectionPool[String]("localhost", 8080))
      .runForeach {
        case (Success(response@HttpResponse(StatusCodes.Forbidden, _, _, _)), orderId) =>
          println(s"The order ID $orderId was not allowed to proceed: $response")
        case (Success(response), orderId) => println(s"The order ID $orderId was successful" +
          s" and returned the response: $response")
        case (Failure(ex), orderId) => println(s"The order ID $orderId could not be completed $ex")
      }
  }
}
