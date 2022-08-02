package part3highlevelserver

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.{Materializer, SystemMaterializer}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.typesafe.config.ConfigFactory
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtSprayJson}
import spray.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object SecurityDomain extends DefaultJsonProtocol {
  final case class LoginRequest(username: String, password: String)
  implicit val loginRequestFormat: RootJsonFormat[LoginRequest] = jsonFormat2(LoginRequest)
}

object JwtAuthorization extends SprayJsonSupport {

  import SecurityDomain._

  implicit val system: ActorSystem[NotUsed] = ActorSystem(
    Behaviors.empty[NotUsed], "JwtAuthorization",
    ConfigFactory.load().getConfig("my-config")
  )
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  implicit val ec: ExecutionContext = system.executionContext

  val superSecretPasswordDb: Map[String, String] = Map(
    "admin" -> "admin",
    "ken" -> "elapsed"
  )
  val algorithm: JwtAlgorithm.HS256.type = JwtAlgorithm.HS256
  val secretKey: String = "elapsed-secret"

  def checkPassword(username: String, password: String): Boolean =
    superSecretPasswordDb.contains(username) && superSecretPasswordDb(username) == password

  def createToken(expirationPeriodInDays: Int): String = {
    val claims: JwtClaim = JwtClaim(
      expiration = Some(System.currentTimeMillis() / 1000 + TimeUnit.DAYS.toSeconds(expirationPeriodInDays)),
      issuedAt = Some(System.currentTimeMillis() / 1000),
      issuer = Some("elapsed.top")
    )

    JwtSprayJson.encode(claims, secretKey, algorithm) // JWT string
  }

  def isTokenExpired(token: String): Boolean = JwtSprayJson.decode(token, secretKey, Seq(algorithm)) match {
    case Success(claims) => claims.expiration.getOrElse(0L) < System.currentTimeMillis() / 1000
    case Failure(_) => true
  }

  def isTokenValid(token: String): Boolean = JwtSprayJson.isValid(token, secretKey, Seq(algorithm))

  val loginRoute: Route = pathPrefix("api" / "jwt") {
    post {
      entity(as[LoginRequest]) {
        case LoginRequest(username, password) if checkPassword(username, password) =>
          val token: String = createToken(1)
          respondWithHeader(RawHeader("Access-Token", token)) {
            complete(StatusCodes.OK)
          }
        case _ => complete(StatusCodes.Unauthorized)
      }
    } ~
    get {
      path("secureEndpoint") {
        optionalHeaderValueByName("Authorization") {
          case Some(token) =>
            if (isTokenValid(token)) {
              if (isTokenExpired(token)){
                complete(HttpResponse(
                  status = StatusCodes.Unauthorized,
                  entity = "Token expired."
                ))
              } else {
                complete(HttpResponse(
                  status = StatusCodes.OK,
                  entity = "User accessed authorized endpoint!"
                ))
              }
            } else {
              complete(HttpResponse(
                status = StatusCodes.Unauthorized,
                entity = "Token is invalid, or has been tampered with."
              ))
            }
          case _ =>
            complete(HttpResponse(
              status = StatusCodes.Unauthorized,
              entity = "No token provided!"
            ))
        }
      }
    }
  }

  def main(args: Array[String]): Unit = {
    Http().newServerAt("localhost", 8080).bind(loginRoute)
  }
}
