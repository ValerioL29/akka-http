package part3highlevelserver

import akka.{Done, NotUsed}
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Multipart}
import akka.http.scaladsl.model.Multipart.FormData
import akka.stream.{IOResult, Materializer, SystemMaterializer}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import com.typesafe.config.ConfigFactory

import java.io.File
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object UploadingFiles {

  implicit val system: ActorSystem[NotUsed] = ActorSystem(
    Behaviors.empty[NotUsed], "UploadingFiles",
    ConfigFactory.load().getConfig("my-config")
  )
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val ec: ExecutionContext = system.executionContext

  val fileRoute: Route = pathPrefix("api" / "file") {
    (pathEndOrSingleSlash & get) {
      complete(HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        """
          |<html>
          | <body>
          |   <form action="http://localhost:8080/api/file/upload" method="post" enctype="multipart/form-data">
          |     <input type="file" name="myFile">
          |     <button type="submit">Upload</button>
          |   </form>
          | </body>
          |</html>
          |""".stripMargin
      ))
    } ~
    (path("upload") & extractLog & post) { log: LoggingAdapter =>
      // handle uploading files
      // multipart/form-data
      entity(as[Multipart.FormData]) { formData: Multipart.FormData =>
        // handle file payload
        val partsSource: Source[FormData.BodyPart, Any] = formData.parts
        val filePartsSink: Sink[FormData.BodyPart, Future[Done]] =
          Sink.foreach[FormData.BodyPart] { bodyPart: FormData.BodyPart =>
            if (bodyPart.name == "myFile") {
              // create a file
              val filename: String = "src/main/download" +
                bodyPart.filename.getOrElse("tempFile_" + System.currentTimeMillis())
              val file = new File(filename)
              log.info(s"Writing to file: $filename")

              val fileContentSource: Source[ByteString, Any] = bodyPart.entity.dataBytes
              val fileContentSink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(file.toPath)

              // writing the data to the file
              fileContentSource.runWith(fileContentSink)
            }
          }
        val writeOperationFuture: Future[Done] = partsSource.runWith(filePartsSink)

        onComplete(writeOperationFuture) {
          case Success(_) => complete("File uploaded!")
          case Failure(exception) => complete(s"File failed to upload: $exception")
        }
      }
    }
  }

  def main(args: Array[String]): Unit = {
    Http().newServerAt("localhost", 8080).bind(fileRoute)
  }
}
