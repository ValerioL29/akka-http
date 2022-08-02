package part4client

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.slf4j.Logger
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

object PaymentSystemDomain {
  trait TransactionProtocol
  final case class TransactionRequest(creditCard: CreditCard,
                                  receiverAccount: String,
                                  amount: Double, replyTo: ActorRef[TransactionResponse]) extends TransactionProtocol
  trait TransactionResponse
  case object TransactionAccepted extends TransactionResponse
  case object TransactionRejected extends TransactionResponse

  final case class CreditCard(serialNumber: String, securityCode: String, account: String)
  final case class PaymentRequest(creditCard: CreditCard, receiverAccount: String, amount: Double)
}

object PaymentValidator {

  import PaymentSystemDomain._

  def apply(): Behavior[TransactionProtocol] = Behaviors.receive[TransactionProtocol] {
    (context: ActorContext[TransactionProtocol], message: TransactionProtocol) =>
      val logger: Logger = context.log

      message match {
        case TransactionRequest(creditCard, receiverAccount, amount, replyTo) =>
          logger.info(s"[${context.self.path.name}] Handling ${message.getClass} message.")
          logger.info(s"[${context.self.path.name}] ${creditCard.account} is trying " +
            s"to send $amount to $receiverAccount")
          if (creditCard.serialNumber == "1234-1234-1234-1234") replyTo ! TransactionRejected
          else replyTo ! TransactionAccepted

          Behaviors.same
        case _ =>
          logger.info(s"[${context.self.path.name}] Handling ${message.getClass} message. Not supported.")

          Behaviors.same
      }
  }
}

trait PaymentJsonProtocol extends DefaultJsonProtocol {

  import PaymentSystemDomain._

  implicit val creditCardFormat: RootJsonFormat[CreditCard] = jsonFormat3(CreditCard)
  implicit val paymentRequestFormat: RootJsonFormat[PaymentRequest] = jsonFormat3(PaymentRequest)
}

object PaymentSystem extends PaymentJsonProtocol with SprayJsonSupport {

  import PaymentSystemDomain._

  // akka microservice support
  trait RootCommand
  final case class RetrieveSystemActor(replyTo: ActorRef[ActorRef[TransactionProtocol]]) extends RootCommand

  val rootBehavior: Behavior[RootCommand] = Behaviors.setup { context: ActorContext[RootCommand] =>
    val systemActor: ActorRef[TransactionProtocol] = context.spawn(PaymentValidator(), "validator")

    Behaviors.receiveMessage {
      case RetrieveSystemActor(replyTo) =>
        replyTo ! systemActor
        Behaviors.same
    }
  }

  implicit val system: ActorSystem[RootCommand] = ActorSystem(
    rootBehavior, "PaymentSystem",
    ConfigFactory.load().getConfig("my-config")
  )
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val timeout: Timeout = Timeout(5 seconds)
  implicit val scheduler: Scheduler = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  def main(args: Array[String]): Unit = {
    val retrieveValidatorFuture: Future[ActorRef[TransactionProtocol]] =
      system.ask((replyTo: ActorRef[ActorRef[TransactionProtocol]]) => RetrieveSystemActor(replyTo))

    retrieveValidatorFuture.onComplete {
      case Success(validator) =>
        val paymentRoute: Route = pathPrefix("api" / "payments") {
          post {
            entity(as[PaymentRequest]) { paymentRequest: PaymentRequest =>
              val validationFuture: Future[StatusCode with Serializable] = validator.ask(
                (replyTo: ActorRef[TransactionResponse]) => TransactionRequest(
                  paymentRequest.creditCard,
                  paymentRequest.receiverAccount,
                  paymentRequest.amount,
                  replyTo)
              ).map {
                case TransactionAccepted => StatusCodes.OK
                case TransactionRejected => StatusCodes.Forbidden
                case _ => StatusCodes.BadRequest
              }
              complete(validationFuture)
            }
          }
        }
        Http().newServerAt("localhost", 8080).bind(paymentRoute)
      case Failure(exception) =>
        system.log.error(s"Errors! Failed to retrieve validator: $exception")
    }
  }
}
