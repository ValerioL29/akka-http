package part3highlevelserver

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.Timeout
import org.slf4j.Logger
import part2lowlevelserver._
import part2lowlevelserver.Messages._
import spray.json._

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.{ExecutorService, Executors}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

object PrivateExecutionContext {
  implicit val executors: ExecutorService = Executors.newFixedThreadPool(4)
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(executors)
}

object HighLevelExample extends App with GuitarStoreJsonProtocol {

  import PrivateExecutionContext.ec

  val guitarList: List[Guitar] = List(
    Guitar("Fender", "Stratocaster"),
    Guitar("Gibson", "Les Paul"),
    Guitar("Martin", "LX1")
  )

  val rootBehavior: Behavior[RootCommand] = Behaviors.setup { context: ActorContext[RootCommand] =>
    val dbActor: ActorRef[Command] = context.spawn(GuitarDB(), "guitarDB")
    val responseHandler: ActorRef[Response] =
      context.spawn(Behaviors.receive[Response]{ (context: ActorContext[Response], message: Response) =>
        val logger: Logger = context.log

        message match {
          case FoundGuitar(guitar) =>
            logger.info(s"[aggregator] Guitar is found: $guitar")
            Behaviors.same
          case FoundAllGuitar(guitars) =>
            logger.info(s"[aggregator] Guitars are found: $guitars")
            Behaviors.same
          case GuitarCreated(id) =>
            logger.info(s"[aggregator] Guitar is created, its id is: $id")
            Behaviors.same
          case QuantityAdded(newStock) =>
            logger.info(s"[aggregator] Current stock is updated to: $newStock")
            Behaviors.same
          case GuitarsInOrOutOfStock(guitars) =>
            logger.info(s"[aggregator] Guitars are found: $guitars")
            Behaviors.same
          case NoSuchGuitar =>
            logger.info(s"[aggregator] Guitar cannot be found.")
            Behaviors.same
        }
      }, "replyHandler")

    guitarList.foreach((guitar: Guitar) => dbActor ! CreateGuitar(guitar, responseHandler))

    Behaviors.receiveMessage {
      case RetrieveDBActor(replyTo) =>
        replyTo ! dbActor
        Behaviors.same
    }
  }

  implicit val system: ActorSystem[RootCommand] = ActorSystem(rootBehavior, "HighLevelIntro")
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val scheduler: Scheduler = system.scheduler
  implicit val timeout: Timeout = Timeout(5 seconds)

  /**
   * Server code:
   *
   * 1. GET on /api/guitar => ALL the guitars in the store
   *
   * 2. GET on /api/guitar/?id=X => Fetch a specific guitar information with id X
   *
   * 3. GET on /api/guitar/X => Fetch a specific guitar information with id X
   *
   * 4. Get on /api/guitar/inventory?inStock=true/false
   */
  def startHttpServer(dbActor: ActorRef[Command])(implicit system: ActorSystem[_]): Unit = {

    val logger: Logger = system.log
    /**
     * Complex Guitar Server Route
     */
    //    val guitarServerRoute: Route =
    //      path("api" / "guitar") {
    //        parameter(Symbol("id").as[String]) { (guitarId: String) =>
    //          val guitarFuture: Future[Response] = dbActor ? ((replyTo: ActorRef[Response]) => FindGuitar(guitarId, replyTo))
    //          val entityFuture: Future[HttpEntity.Strict] = guitarFuture.map {
    //            case FoundGuitar(guitar) =>
    //              HttpEntity(
    //                ContentTypes.`application/json`,
    //                guitar.toJson.prettyPrint
    //              )
    //            case _ =>
    //              HttpEntity(
    //                ContentTypes.`text/html(UTF-8)`,
    //                "OOPS! Something goes wrong"
    //              )
    //          }
    //          complete(entityFuture)
    //        } ~
    //        get {
    //          val guitarsFuture: Future[Response] = dbActor ? ((replyTo: ActorRef[Response]) => FindAllGuitar(replyTo))
    //          val entityFuture: Future[HttpEntity.Strict] = guitarsFuture.map{
    //            case FoundAllGuitar(guitars) =>
    //              HttpEntity(
    //                ContentTypes.`application/json`,
    //                guitars.toJson.prettyPrint
    //              )
    //            case _ =>
    //              HttpEntity(
    //                ContentTypes.`text/html(UTF-8)`,
    //                "OOPS! Something goes wrong"
    //              )
    //          }
    //          complete(entityFuture)
    //        }
    //      } ~
    //      path("api" / "guitar" / JavaUUID) { uuid: UUID =>
    //        get {
    //          val guitarFuture: Future[Response] =
    //            dbActor ? ((replyTo: ActorRef[Response]) => FindGuitar(uuid.toString, replyTo))
    //          val entityFuture: Future[HttpEntity.Strict] = guitarFuture.map {
    //            case FoundGuitar(guitar) =>
    //              HttpEntity(
    //                ContentTypes.`application/json`,
    //                guitar.toJson.prettyPrint
    //              )
    //            case _ =>
    //              HttpEntity(
    //                ContentTypes.`text/html(UTF-8)`,
    //                "OOPS! Something goes wrong"
    //              )
    //          }
    //          complete(entityFuture)
    //        }
    //      } ~
    //      path("api" / "guitar" / "inventory") {
    //        get {
    //          parameter(Symbol("inStock").as[Boolean]) { inStock: Boolean =>
    //            val guitarsFuture: Future[Response] =
    //              dbActor ? ((replyTo: ActorRef[Response]) => FindGuitarsInStock(inStock, replyTo))
    //            val entityFuture: Future[HttpEntity.Strict] = guitarsFuture.map {
    //              case GuitarsInOrOutOfStock(guitars) =>
    //                HttpEntity(
    //                  ContentTypes.`application/json`,
    //                  guitars.toJson.prettyPrint
    //                )
    //              case _ =>
    //                HttpEntity(
    //                  ContentTypes.`text/html(UTF-8)`,
    //                  "OOPS! Something goes wrong"
    //                )
    //            }
    //            complete(entityFuture)
    //          }
    //        }
    //      }

    /**
     * Simplified Guitar Server Route
     */
    def toHttpEntity(response: Response): HttpEntity.Strict = response match {
      case FoundAllGuitar(guitars) =>
        HttpEntity(ContentTypes.`application/json`, guitars.toJson.prettyPrint)
      case FoundGuitar(guitar) =>
        HttpEntity(ContentTypes.`application/json`, guitar.toJson.prettyPrint)
      case GuitarsInOrOutOfStock(guitars) =>
        HttpEntity(ContentTypes.`application/json`, guitars.toJson.prettyPrint)
      case _ =>
        HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          "OOPS! Something goes wrong"
        )
    }

    val simplifiedRoute: Route =
      (pathPrefix("api" / "guitar") & get) {
        path("inventory") {
          parameter(Symbol("inStock").as[Boolean]) { inStock: Boolean =>
            complete(
              dbActor.ask((replyTo: ActorRef[Response]) =>
                FindGuitarsInStock(inStock, replyTo))
                .map(toHttpEntity)
            )
          }
        } ~
        (path(JavaUUID) | parameter(Symbol("id").as[UUID])) { uuid: UUID =>
          complete(
            dbActor.ask((replyTo: ActorRef[Response]) =>
              FindGuitar(uuid.toString, replyTo))
              .map(toHttpEntity)
          )
        } ~
        pathEndOrSingleSlash {
          complete(
            dbActor.ask((replyTo: ActorRef[Response]) =>
              FindAllGuitar(replyTo))
              .map(toHttpEntity)
          )
        }
      }


    val httpBindingFuture: Future[Http.ServerBinding] =
      Http().newServerAt("localhost", 8080)
        .bind(simplifiedRoute)
    httpBindingFuture.onComplete {
      case Success(binding) =>
        val address: InetSocketAddress = binding.localAddress
        logger.info(s"Server online at http://${address.getHostString}:${address.getPort}")
      case Failure(ex) =>
        logger.error(s"Failed to bind HTTP server, because $ex")
        system.terminate()
    }
  }

  val newDBActorFuture: Future[ActorRef[Command]] =
    system ? ((replyTo: ActorRef[ActorRef[Command]]) => RetrieveDBActor(replyTo))
  newDBActorFuture.onComplete {
    case Success(db) =>
      this.startHttpServer(db)
    case Failure(exception) =>
      println(s"$exception")
  }
}
