package part4client

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import org.slf4j.Logger

object PaymentSystemDomain {
  trait TransactionProtocol
  final case class PaymentRequest(creditCard: CreditCard,
                                  receiverAccount: String,
                                  amount: Double, replyTo: ActorRef[TransactionProtocol]) extends TransactionProtocol
  case object PaymentAccepted extends TransactionProtocol
  case object PaymentRejected extends TransactionProtocol

  final case class CreditCard(serialNumber: String, securityCode: String, senderAccount: String)
}

object PaymentValidator {

  import PaymentSystemDomain._

  def apply(): Behavior[TransactionProtocol] = Behaviors.receive[TransactionProtocol] {
    (context: ActorContext[TransactionProtocol], message: TransactionProtocol) =>
      val logger: Logger = context.log

      message match {
        case PaymentRequest(creditCard, receiverAccount, amount, replyTo) =>
          logger.info(s"[${context.self.path.name}] Handling ${message.getClass} message.")
          logger.info(s"[${context.self.path.name}] ${creditCard.senderAccount} is trying " +
            s"to send $amount to $receiverAccount")
          if (creditCard.serialNumber == "1234-1234-1234-1234") replyTo ! PaymentRejected
          else replyTo ! PaymentAccepted

          Behaviors.same
        case _ =>
          logger.info(s"[${context.self.path.name}] Handling ${message.getClass} message. Not supported.")

          Behaviors.same
      }
  }
}

object PaymentSystem {

  def main(args: Array[String]): Unit = {

  }
}
