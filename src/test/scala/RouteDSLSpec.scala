import akka.http.javadsl.server.MethodRejection
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Rejection, Route}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

case class Book(id: Int, author: String, title: String)

trait BookJsonProtocol extends DefaultJsonProtocol {
  implicit val booFormat: RootJsonFormat[Book] = jsonFormat3(Book)
}

class RouteDSLSpec extends AnyWordSpec with Matchers with ScalatestRouteTest with BookJsonProtocol {

  import RouteDSLSpec._

  "A digital library backend" should {
    "return all the books in the library" in {
      // send an HTTP request through an endpoint that you want to test
      // inspect the response
      Get("/api/book") ~> libraryRoute ~> check {
        // assertion
        status shouldBe StatusCodes.OK

        entityAs[List[Book]] shouldBe books
      }
    }

    "return a book by hitting the query parameter endpoint" in {
      Get("/api/book?id=2") ~> libraryRoute ~> check {
        status shouldBe StatusCodes.OK
        responseAs[Option[Book]] shouldBe Some(books(1))
      }
    }

    "return a book by calling the endpoint with the id in the path" in {
      Get("/api/book/2") ~> libraryRoute ~> check {
        response.status shouldBe StatusCodes.OK

        val strictEntityFuture: Future[HttpEntity.Strict] = response.entity.toStrict(1 second)
        val strictEntity: HttpEntity.Strict = Await.result(strictEntityFuture, 1 second) // block the codes for future

        strictEntity.contentType shouldBe ContentTypes.`application/json`

        val book: Option[Book] = strictEntity.data.utf8String.parseJson.convertTo[Option[Book]]
        book shouldBe Some(books(1))
      }
    }

    "insert a book into the 'database'" in {
      val newBook: Book = Book(5, "Steven PressField", "The War of Art")
      Post("/api/book", newBook) ~> libraryRoute ~> check {
        status shouldBe StatusCodes.OK
        books should contain(newBook) // same
      }
    }

    "not accept other methods that POST and GET" in {
      Delete("/api/book") ~> libraryRoute ~> check {
        rejections should not be empty // "natural language style"

        val methodRejections: Seq[Rejection with MethodRejection] = rejections.collect {
          case rejection: MethodRejection => rejection
        }

        methodRejections.length shouldBe 2
      }
    }

    "return all the books of a given author" in {
      Get("/api/book/author/JRR%20Tolkien") ~> libraryRoute ~> check {
        status shouldBe StatusCodes.OK
        entityAs[List[Book]] shouldBe books.filter((_: Book).author == "JRR Tolkien")
      }
    }
  }

}

object RouteDSLSpec extends BookJsonProtocol with SprayJsonSupport {

  // code under test
  var books: List[Book] = List(
    Book(1, "Harper Lee", "To Kill a Mockingbird"),
    Book(2, "JRR Tolkien", "The Lord of the Rings"),
    Book(3, "GRR Marting", "A Song of Ice and Fire"),
    Book(4, "Tony Robbins", "Awaken the Giant Within")
  )

  /**
   * Endpoints:
   *
   * - GET /api/book - returns all the books in the library
   *
   * - GET /api/book/X - return a single book with id X
   *
   * - GET /api/book?id=X - same
   *
   * - POST /api/book - adds a new book to the library
   *
   */
  val libraryRoute: Route = pathPrefix("api" / "book") {
    get {
      (path(IntNumber) | parameter(Symbol("id").as[Int])) { id: Int =>
        complete(books.find((_: Book).id == id))
      } ~
      path("author" / Segment) { author: String =>
        complete(books.filter((_: Book).author == author))
      } ~
      pathEndOrSingleSlash {
        complete(books)
      }
    } ~
    post {
      entity(as[Book]) { book: Book =>
        books = books :+ book
        complete(StatusCodes.OK)
      } ~
      complete(StatusCodes.BadRequest)
    }
  }
}
