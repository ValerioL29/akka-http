package part3highlevelserver

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.stream.{Materializer, SystemMaterializer}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.slf4j.Logger
import spray.json._

import java.net.InetSocketAddress
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object Probable {
  final case class Player(uuid: String, nickname: String, characterClass: String, level: Int)

  // Messages
  trait TaskProtocol
  case class GetAllPlayers(replyTo: ActorRef[ResponseProtocol]) extends TaskProtocol
  case class GetPlayer(uuid: String, replyTo: ActorRef[ResponseProtocol]) extends TaskProtocol
  case class GetPlayersByClass(characterClass: String, replyTo: ActorRef[ResponseProtocol]) extends TaskProtocol
  case class AddPlayer(player: Player, replyTo: ActorRef[ResponseProtocol]) extends TaskProtocol
  case class RemovePlayer(player: Player, replyTo: ActorRef[ResponseProtocol]) extends TaskProtocol
  trait ResponseProtocol
  case class GetAllPlayersResponse(players: List[Player]) extends ResponseProtocol
  case class GetPlayerResponse(player: Player) extends ResponseProtocol
  case class GetPlayersByClassResponse(player: Player) extends ResponseProtocol
  case class AddPlayerResponse(player: Player) extends ResponseProtocol
  case object RemovePlayerResponse extends ResponseProtocol
  case class OperationFailureResponse(reason: Throwable) extends ResponseProtocol

  // Game Area Actor
  object GameAreaMap {
    val myPlayers: Array[Player] = Array(
      Player(UUID.randomUUID().toString, "Martin", "Warrior", 70),
      Player(UUID.randomUUID().toString, "Bond", "Elf", 67),
      Player(UUID.randomUUID().toString, "Ken", "Wizard", 30)
    )

    def apply(): Behavior[TaskProtocol] = active(Map(
      myPlayers(0).uuid -> myPlayers(0),
      myPlayers(1).uuid -> myPlayers(1),
      myPlayers(2).uuid -> myPlayers(2)
    ))

    def active(players: Map[String, Player]): Behavior[TaskProtocol] = Behaviors.receive[TaskProtocol] {
      (context: ActorContext[TaskProtocol], message: TaskProtocol) =>
        val logger: Logger = context.log

        message match {
          case GetAllPlayers(replyTo) =>
            logger.info(s"[${context.self.path.name}] Fetching all players' information")
            replyTo ! GetAllPlayersResponse(players.values.toList)
            Behaviors.same
          case GetPlayer(uuid, replyTo) =>
            logger.info(s"[${context.self.path.name}] Fetching player with id: $uuid")
            val couldBePlayer: Try[Player] = Try(players(uuid))
            couldBePlayer match {
              case Success(player) =>
                replyTo ! GetPlayerResponse(player)
                Behaviors.same
              case Failure(exception) =>
                replyTo ! OperationFailureResponse(exception)
                Behaviors.same
            }
          case GetPlayersByClass(characterClass, replyTo) =>
            logger.info(s"[${context.self.path.name}] Fetching player with character class: $characterClass")
            val maybePlayer: Option[Player] =
              players.values.find((_: Player).characterClass == characterClass)
            maybePlayer match {
              case Some(player) =>
                replyTo ! GetPlayerResponse(player)
                Behaviors.same
              case None =>
                replyTo ! OperationFailureResponse(new NoSuchElementException("No such kind of player"))
                Behaviors.same
            }
          case AddPlayer(player, replyTo) =>
            logger.info(s"[${context.self.path.name}] Add new player: $player")
            val newPlayers: Map[String, Player] = players + (player.uuid -> player)
            replyTo ! AddPlayerResponse(player)
            active(newPlayers)
          case RemovePlayer(player, replyTo) =>
            logger.info(s"[${context.self.path.name}] Remove player with id: ${player.uuid}")
            val updatedPlayers: Map[String, Player] = players - player.uuid
            replyTo ! RemovePlayerResponse
            active(updatedPlayers)
        }
    }
  }
}

trait PlayerJsonProtocol extends DefaultJsonProtocol {
  import Probable.Player
  implicit val playerFormat: RootJsonFormat[Player] = jsonFormat4(Player)
}

object MarshallingJSON extends PlayerJsonProtocol with SprayJsonSupport {

  import Probable._

  trait RootCommand
  case class RetrieveRootActor(replyTo: ActorRef[ActorRef[TaskProtocol]]) extends RootCommand

  val rootBehaviors: Behavior[RootCommand] = Behaviors.setup { context: ActorContext[RootCommand] =>
    val gameAreaActor: ActorRef[TaskProtocol] = context.spawn(GameAreaMap(), "Probable")

    Behaviors.receiveMessage {
      case RetrieveRootActor(replyTo) =>
        replyTo ! gameAreaActor
        Behaviors.same
    }
  }

  /**
   * Endpoints:
   *
   * - GET /api/player, returns all the players in the map, as JSON
   *
   * - GET /api/player/(id), returns the player with the given nickname (as JSON)
   *
   * - GET /api/player/?id=X, does the same
   *
   * - GET /api/player/class/(characterClass), returns all the players with the given character class
   *
   * - POST /api/player with JSON payload, adds the player to the map
   *
   * - DELETE /api/player with JSON payload, removes the player from the map
   *
   */
  def toHttpEntity(response: ResponseProtocol): HttpEntity.Strict = response match {
    case GetAllPlayersResponse(players: List[Player]) =>
      HttpEntity(ContentTypes.`application/json`, players.toJson.prettyPrint)
    case GetPlayerResponse(player: Player) =>
      HttpEntity(ContentTypes.`application/json`, player.toJson.prettyPrint)
    case GetPlayersByClassResponse(player: Player) =>
      HttpEntity(ContentTypes.`application/json`, player.toJson.prettyPrint)
    case AddPlayerResponse(player: Player) =>
      HttpEntity(ContentTypes.`application/json`, player.toJson.prettyPrint)
    case RemovePlayerResponse =>
      HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Player has been successfully removed.")
    case OperationFailureResponse(reason: Throwable) =>
      HttpEntity(ContentTypes.`text/plain(UTF-8)`, s"Operation failed: ${reason.getMessage}")
  }

  def startHttpServer(gameAppActor: ActorRef[TaskProtocol])(implicit system: ActorSystem[_]): Unit = {
    implicit val ec: ExecutionContext = system.executionContext
    implicit val timeout: Timeout = Timeout(5 seconds)
    implicit val scheduler: Scheduler = system.scheduler

    val gameAreaRoute: Route = pathPrefix("api" / "player") {
      get {
        path("class" / Segment) { characterClass: String =>
          complete(
            gameAppActor.ask((replyTo: ActorRef[ResponseProtocol]) => GetPlayersByClass(characterClass, replyTo))
              .map(toHttpEntity)
          )
        } ~
        (path(Segment) | parameter(Symbol("id").as[String])) { uuid: String =>
          complete(
            gameAppActor.ask((replyTo: ActorRef[ResponseProtocol]) => GetPlayer(uuid, replyTo))
              .map(toHttpEntity)
          )
        } ~
        pathEndOrSingleSlash {
          complete(
            gameAppActor.ask((replyTo: ActorRef[ResponseProtocol]) => GetAllPlayers(replyTo))
              .map(toHttpEntity)
          )
        }
      } ~
      post {
        // Magic!!!
        entity(as[Player]) { player: Player =>
          complete(
            gameAppActor.ask((replyTo: ActorRef[ResponseProtocol]) => AddPlayer(player, replyTo))
              .map(toHttpEntity)
          )
        }
      } ~
      delete {
        entity(as[Player]) { player: Player =>
          complete(
            gameAppActor.ask((replyTo: ActorRef[ResponseProtocol]) => RemovePlayer(player, replyTo))
              .map(toHttpEntity)
          )
        }
      }
    }

    val httpBindingFuture: Future[Http.ServerBinding] =
      Http().newServerAt("localhost", 8080).bind(gameAreaRoute)

    httpBindingFuture.onComplete {
      case Success(binding) =>
        val address: InetSocketAddress = binding.localAddress
        system.log.info(s"Server online at http://${address.getHostString}:${address.getPort}")
      case Failure(ex) =>
        system.log.error(s"Failed to bind HTTP server, because $ex")
        system.terminate()
    }
  }

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[RootCommand] = ActorSystem(
      rootBehaviors, "MarshallingJSON",
      ConfigFactory.load().getConfig("my-config")
    )
    implicit val mat: Materializer = SystemMaterializer(system).materializer
    implicit val timeout: Timeout = Timeout(5 seconds)
    implicit val scheduler: Scheduler = system.scheduler
    implicit val ec: ExecutionContext = system.executionContext

    val masterActorFuture: Future[ActorRef[TaskProtocol]] =
      system.ask((replyTo: ActorRef[ActorRef[TaskProtocol]]) => RetrieveRootActor(replyTo))
    masterActorFuture.foreach(startHttpServer)
  }
}
