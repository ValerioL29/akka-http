package part3highlevelserver

import akka.{Done, NotUsed}
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.stream.{Materializer, SystemMaterializer}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.typesafe.config.ConfigFactory
import spray.json._

import scala.concurrent.{ExecutionContext, Future}

// domain model
final case class Item(name: String, id: Long)
final case class Order(items: List[Item])

trait ShoppingJSONProtocol extends DefaultJsonProtocol {
  // formats for unmarshalling and marshalling
  implicit val itemFormat: RootJsonFormat[Item] = jsonFormat2(Item)
  implicit val orderFormat: RootJsonFormat[Order] = jsonFormat1(Order)
}

object OfficialMarshallingJSONExample extends ShoppingJSONProtocol with SprayJsonSupport {

  implicit val system: ActorSystem[NotUsed] = ActorSystem(
    Behaviors.empty[NotUsed], "OfficialMarshallingJSONExample",
    ConfigFactory.load().getConfig("my-config")
  )
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val ec: ExecutionContext = system.executionContext

  var orders: List[Item] = Nil

  // (fake) async database query api
  def fetchItem(itemId: Long): Future[Option[Item]] = Future {
    orders.find((o: Item) => o.id == itemId)
  }
  def saveOrder(order: Order): Future[Done] = {
    orders = order match {
      case Order(items) => items ::: orders
      case _            => orders
    }
    Future { Done }
  }

  def main(args: Array[String]): Unit = {
    val route: Route =
        get {
          pathPrefix("item" / LongNumber) { id: Long =>
            // there might be no item for a given id
            val maybeItem: Future[Option[Item]] = fetchItem(id)

            onSuccess(maybeItem) {
              case Some(item) => complete(item)
              case None       => complete(StatusCodes.NotFound)
            }
          }
        } ~
        post {
          path("create-order") {
            entity(as[Order]) { order: Order =>
              val saved: Future[Done] = saveOrder(order)
              onSuccess(saved) { _: Done => // we are not interested in the result value `Done` but only in the fact that it was successful
                complete("order created")
              }
            }
          }
        }

    Http().newServerAt("localhost", 8080).bind(route)
    println(s"Server online at http://localhost:8080/")
  }
}
