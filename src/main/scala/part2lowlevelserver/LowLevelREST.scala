package part2lowlevelserver

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.slf4j.Logger
import spray.json._

import java.net.InetSocketAddress
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

case class Guitar(maker: String, model: String, quantity: Int = 0)

trait GuitarStoreJsonProtocol extends DefaultJsonProtocol {
  implicit val guitarFormat: RootJsonFormat[Guitar] = jsonFormat3(Guitar)
}

object Messages {
  sealed trait Command
  case class CreateGuitar(guitar: Guitar, replyTo: ActorRef[Response]) extends Command
  case class FindGuitar(id: String, replyTo: ActorRef[Response]) extends Command
  case class FindAllGuitar(replyTo: ActorRef[Response]) extends Command
  case class AddQuantity(id: String, num: Int, replyTo: ActorRef[Response]) extends Command
  case class FindGuitarsInStock(inStock: Boolean, replyTo: ActorRef[Response]) extends Command

  sealed trait Response
  case class GuitarCreated(id: String) extends Response
  case class FoundGuitar(guitar: Guitar) extends Response
  case class FoundAllGuitar(guitars: List[Guitar]) extends Response
  case object NoSuchGuitar extends Response
  case class QuantityAdded(newStock: Int) extends Response
  case class GuitarsInOrOutOfStock(guitars: List[Guitar]) extends Response

  trait RootCommand
  case class RetrieveDBActor(replyTo: ActorRef[ActorRef[Command]]) extends RootCommand
}

object GuitarDB {

  import Messages._

  def apply(): Behavior[Command] = active(Map())

  def active(guitars: Map[String, Guitar]): Behavior[Command] =
    Behaviors.receive { (context: ActorContext[Command], message: Command) =>
      val logger: Logger = context.log

      message match {
        case FindAllGuitar(replyTo) =>
          logger.info(s"[guitarDB] Searching for all guitars...")
          replyTo ! FoundAllGuitar(guitars.values.toList)
          Behaviors.same
        case FindGuitar(id, replyTo) =>
          logger.info(s"[guitarDB] Find guitar with id: $id")

          guitars.getOrElse(id, None) match {
            case guitar: Guitar =>
              replyTo ! FoundGuitar(guitar)
              Behaviors.same
            case _ =>
              replyTo ! NoSuchGuitar
              Behaviors.same
          }
        case CreateGuitar(guitar, replyTo) =>
          logger.info("[guitarDB] Create a new guitar...")
          val newId: String = UUID.randomUUID().toString
          logger.info(s"[guitarDB] New guitar is created with id: $newId")
          replyTo ! GuitarCreated(newId)
          active(guitars + (newId -> guitar))
        case AddQuantity(id, num, replyTo) =>
          val targetGuitar: Product = guitars.getOrElse(id, None)
          targetGuitar match {
            case None =>
              replyTo ! NoSuchGuitar
              Behaviors.same
            case guitar: Guitar =>
              logger.info(s"[guitarDB] Add $num quantities for Guitar: $guitar")
              val newStocks: Int = guitar.quantity + num
              replyTo ! QuantityAdded(newStocks)
              active(guitars + (id -> Guitar(guitar.maker, guitar.model, newStocks)))
          }
        case FindGuitarsInStock(inStock, replyTo) =>
          logger.info(s"[guitarDB] Find all guitars ${if(inStock) "in" else "out of"} stock.")
          if(inStock) {
            val resGuitars: Iterable[Guitar] = guitars.values.filter((_: Guitar).quantity > 0)
            replyTo ! GuitarsInOrOutOfStock(resGuitars.toList)
          }
          else {
            val resGuitars: Iterable[Guitar] = guitars.values.filter((_: Guitar).quantity == 0)
            replyTo ! GuitarsInOrOutOfStock(resGuitars.toList)
          }
          Behaviors.same
      }
    }
}

// JSON -> marshalling
//  val simpleGuitar = Guitar("Fender", "Stratocaster")
//  println(simpleGuitar.toJson.prettyPrint)

// un-marshalling
//  val simpleGuitarJsonString =
//    """
//      |{
//      |  "make": "Fender",
//      |  "model": "Stratocaster"
//      |}
//      |""".stripMargin
//  println(simpleGuitarJsonString.parseJson.convertTo[Guitar])

object LowLevelREST extends App with GuitarStoreJsonProtocol {

  import Messages._

  implicit val guitarList: List[Guitar] = List(
    Guitar("Fender", "Stratocaster"),
    Guitar("Gibson", "Les Paul"),
    Guitar("Martin", "LX1")
  )

  implicit val rootBehavior: Behavior[RootCommand] = Behaviors.setup { context: ActorContext[RootCommand] =>
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

  implicit val system: ActorSystem[RootCommand] = ActorSystem(rootBehavior, "LowLevelAPIExercise", ConfigFactory.load())
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val ec: ExecutionContext = system.executionContext
  implicit val scheduler: Scheduler = system.scheduler
  implicit val defaultTimeout: Timeout = Timeout(5 seconds)

  /**
   * Server code:
   *
   * 1. GET on /api/guitar => ALL the guitars in the store
   *
   * 2. GET on /api/guitar/id => Fetch a specific guitar information
   *
   * 3. POST on /api/guitar => INSERT the guitar into the store
   *
   * 4. Get on /api/guitar/inventory?inStock=true/false which returns the guitars in stock as a JSON
   *
   * 5. POST to /api/guitar/inventory?id=X&quantity=Y which adds Y guitars to the stock for guitar with id X
   */
  def startHttpServer(dbActor: ActorRef[Command])(implicit system: ActorSystem[_]): Unit = {
    val logger: Logger = system.log

    def getGuitar(query: Query): Future[HttpResponse] = {
      val guitarId: Option[String] = query.get("id")

      guitarId match {
        case None =>
          Future(HttpResponse(
            StatusCodes.NotFound,
            entity = HttpEntity(
              ContentTypes.`text/html(UTF-8)`,
              "You need to provide a specific 'id' query parameter to fetch a certain guitar information."
            )
          ))
        case Some(id) =>
          val guitarFuture: Future[Response] = dbActor ? ((replyTo: ActorRef[Response]) => FindGuitar(id, replyTo))
          guitarFuture.map {
            case FoundGuitar(guitar) =>
              HttpResponse(
                StatusCodes.OK,
                entity = HttpEntity(
                  ContentTypes.`application/json`,
                  guitar.toJson.prettyPrint
                )
              )
            case NoSuchGuitar =>
              HttpResponse(
                StatusCodes.NotFound,
                entity = HttpEntity(
                  ContentTypes.`text/html(UTF-8)`,
                  s"Guitar($id) cannot be found."
                )
              )
          }
      }
    }

    def getGuitarQuantity(query: Query): Future[HttpResponse] = {
      val guitarId: Option[String] = query.get("id")

      guitarId match {
        case None =>
          Future(HttpResponse(
            StatusCodes.NotFound,
            entity = HttpEntity(
              ContentTypes.`text/html(UTF-8)`,
              "You need to provide a specific 'id' query parameter to fetch a certain guitar information."
            )
          ))
        case Some(id) =>
          val guitarFuture: Future[Response] = dbActor ? ((replyTo: ActorRef[Response]) => FindGuitar(id, replyTo))
          guitarFuture.map {
            case FoundGuitar(guitar) =>
              HttpResponse(
                StatusCodes.OK,
                entity = HttpEntity(
                  ContentTypes.`text/html(UTF-8)`,
                  s"${guitar.model} model has ${guitar.quantity} on stock."
                )
              )
            case NoSuchGuitar =>
              HttpResponse(
                StatusCodes.NotFound,
                entity = HttpEntity(
                  ContentTypes.`text/html(UTF-8)`,
                  s"Guitar($id) cannot be found."
                )
              )
          }
      }
    }

    val requestHandler: HttpRequest => Future[HttpResponse] = {
      case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar"), _, _, _) =>
        /**
         * query parameter handling code
         */
        val query: Query = uri.query() // query object <=> Map[String, String]
        if (query.isEmpty) {
          val findAllGuitarsFuture: Future[Response] = dbActor ? ((replyTo: ActorRef[Response]) => FindAllGuitar(replyTo))
          findAllGuitarsFuture.map {
            case FoundAllGuitar(guitars) =>
              HttpResponse(
                StatusCodes.OK,
                entity = HttpEntity(
                  ContentTypes.`application/json`,
                  guitars.toJson.prettyPrint
                )
              )
            case _ =>
              HttpResponse(
                StatusCodes.BadRequest,
                entity = HttpEntity(
                  ContentTypes.`text/html(UTF-8)`,
                  "OOPS! Something goes wrong..."
                )
              )
          }
        }
        else getGuitar(query)

      case HttpRequest(HttpMethods.POST, Uri.Path("/api/guitar"), _, entity, _) =>
        // entities are a Source[ByteString]
        val strictEntityFuture: Future[HttpEntity.Strict] = entity.toStrict(5 seconds)
        strictEntityFuture.flatMap { strictEntity: HttpEntity.Strict =>
          val guitar: Guitar = strictEntity.data.utf8String.parseJson.convertTo[Guitar]

          val createGuitarFuture: Future[Response] = dbActor ? ((replyTo: ActorRef[Response]) => CreateGuitar(guitar, replyTo))
          createGuitarFuture.map {
            case GuitarCreated(id) =>
              HttpResponse(
                StatusCodes.OK,
                entity = HttpEntity(
                  ContentTypes.`text/html(UTF-8)`,
                  s"Guitar is created successfully! Its uuid is: $id"
                )
              )
            case _ =>
              HttpResponse(
                StatusCodes.BadRequest,
                entity = HttpEntity(
                  ContentTypes.`text/html(UTF-8)`,
                  "OOPS! Something goes wrong..."
                )
              )
          }
        }

      case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar/inventory"), _, _, _) =>
        val query: Query = uri.query()
        if (query.isEmpty) {
          val findAllGuitarFuture: Future[Response] = dbActor ? ((replyTo: ActorRef[Response]) => FindAllGuitar(replyTo))
          findAllGuitarFuture.map {
            case FoundAllGuitar(guitars) =>
              HttpResponse(
                StatusCodes.OK,
                entity = HttpEntity(
                  ContentTypes.`application/json`,
                  guitars.toJson.prettyPrint
                )
              )
            case _ =>
              HttpResponse(
                StatusCodes.NotFound,
                entity = HttpEntity(
                  ContentTypes.`text/html(UTF-8)`,
                  "OOPS! Something goes wrong..."
                )
              )
          }
        }
        else getGuitarQuantity(query)

      case HttpRequest(HttpMethods.POST, uri@Uri.Path("/api/guitar/inventory"), _, _, _) =>
        val query: Query = uri.query()
        if (query.isEmpty) {
          Future(
            HttpResponse(
              StatusCodes.BadRequest,
              entity = HttpEntity(
                ContentTypes.`text/html(UTF-8)`,
                "To update stock quantity, you need to specify target guitar's " +
                  "id and number of quantities that is going to be added."
              )
            )
          )
        }
        else {
          val guitarId: Option[String] = query.get("id")
          val guitarQuantityChange: Option[Int] = query.get("quantity").map((_: String).toInt)

          (guitarId, guitarQuantityChange) match {
            case (Some(id), Some(num)) =>
              val guitarQuantityModificationFuture: Future[Response] =
                dbActor ? ((replyTo: ActorRef[Response]) => AddQuantity(id, num, replyTo))
              guitarQuantityModificationFuture.map {
                case QuantityAdded(newStock) =>
                  HttpResponse(
                    entity = HttpEntity(
                      ContentTypes.`text/html(UTF-8)`,
                      s"Quantity modification successful. Current stock is: $newStock"
                    )
                  )
                case NoSuchGuitar =>
                  HttpResponse(
                    StatusCodes.NotFound,
                    entity = HttpEntity(
                      ContentTypes.`text/html(UTF-8)`,
                      s"Could not find target guitar item in the stock."
                    )
                  )
              }
            case _ =>
              Future(
                HttpResponse(
                  StatusCodes.BadRequest,
                  entity = HttpEntity(
                    ContentTypes.`text/html(UTF-8)`,
                    "To update stock quantity, you need to specify target guitar's " +
                      "id and number of quantities that is going to be added."
                  )
                )
              )
          }
        }
    }

    val httpBindingFuture: Future[Http.ServerBinding] =
      Http().newServerAt("localhost", 8080)
        .bind(requestHandler)
    httpBindingFuture.onComplete {
      case Success(binding) =>
        val address: InetSocketAddress = binding.localAddress
        logger.info(s"Server online at http://${address.getHostString}:${address.getPort}")
      case Failure(ex) =>
        logger.error(s"Failed to bind HTTP server, because $ex")
        system.terminate()
    }
  }

  /**
   * Initiate server
   */
  implicit val guitarDBActorFuture: Future[ActorRef[Command]] =
    system ? ((replyTo: ActorRef[ActorRef[Command]]) => RetrieveDBActor(replyTo))
  guitarDBActorFuture.onComplete {
    case Success(db) => startHttpServer(db)
    case Failure(exception) => println(s"$exception")
  }
}
