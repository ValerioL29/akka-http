package part3highlevelserver

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{Materializer, SystemMaterializer}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Sink
import akka.util.CompactByteString
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object WebSocketsDemo {

  implicit val system: ActorSystem[NotUsed] = ActorSystem(
    Behaviors.empty[NotUsed], "WebSocketsDemo",
    ConfigFactory.load().getConfig("my-config")
  )
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val ec: ExecutionContext = system.executionContext

  // Message: TextMessage vs BinaryMessage
  val textMessage: TextMessage = TextMessage(Source.single("hello via a text message"))
  val binaryMessage: BinaryMessage = BinaryMessage(Source.single(CompactByteString("hello via a binary message")))

  val html: String =
    """
      |<!DOCTYPE html>
      |<html lang="en">
      |<head>
      |    <meta charset="UTF-8">
      |    <title>Websockets Demo</title>
      |    <script>
      |        let exampleSocket = new WebSocket("ws://localhost:8080/greeter");
      |        console.log("Starting websocket...");
      |
      |        exampleSocket.onmessage = (event) => {
      |            let newChild = document.createElement("div");
      |            newChild.innerText = event.data;
      |            document.getElementById("1").appendChild(newChild);
      |        }
      |
      |        exampleSocket.onopen = (_) => {
      |            exampleSocket.send("socket seems to be open...")
      |        }
      |
      |        exampleSocket.send("socket says: hello, server!!!")
      |    </script>
      |</head>
      |<body>
      |    <h1>Starting websocket...</h1>
      |    <div id="1">
      |    </div>
      |</body>
      |</html>
      |""".stripMargin

  // Flow[-In, +Out, +Mat]
  val websocketFlow: Flow[Message, Message, Any] = Flow[Message].map {
    case textMessage: TextMessage =>
      TextMessage(Source.single("Server says back:")
        ++ textMessage.textStream
        ++ Source.single("!")
      )
    case binaryMessage: BinaryMessage =>
      binaryMessage.dataStream.runWith(Sink.ignore)
      TextMessage(Source.single("Server received a binary message..."))
  }

  final case class SocialPost(owner: String, content: String)
  val socialFeed: Source[SocialPost, NotUsed] = Source(List(
    SocialPost("Martin", "Scala 3 has been announced!"),
    SocialPost("Ken", "A new Rock the JVM course is open!"),
    SocialPost("Martin", "I killed Java."),
  ))
  val socialMessages: Source[TextMessage.Strict, NotUsed] = socialFeed.throttle(1, 2 second)
    .map((socialPost: SocialPost) => TextMessage(s"${socialPost.owner} said: ${socialPost.content}"))
  val socialFlow: Flow[Message, TextMessage.Strict, NotUsed] =
    Flow.fromSinkAndSource(Sink.foreach[Message](println), socialMessages)

  val websocketRoute: Route =
    (pathEndOrSingleSlash & get) {
      complete(HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        html
      ))
    } ~
    path("greeter") {
      handleWebSocketMessages(socialFlow)
    }

  def main(args: Array[String]): Unit = {
    Http().newServerAt("localhost", 8080).bind(websocketRoute)
  }
}
