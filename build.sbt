name := "akka-http"

version := "0.1"

scalaVersion := "2.13.8"

val AkkaVersion = "2.6.19"
val AkkaHttpVersion = "10.2.9"
val scalaTestVersion = "3.2.12"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed"         % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream-typed"        % AkkaVersion,
  "com.typesafe.akka" %% "akka-http"                % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json"     % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-testkit"        % AkkaHttpVersion  % "Test",
  // testing
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion      % "Test",
  "org.scalatest"     %% "scalatest"                % scalaTestVersion % "Test",

  // JWT
  "com.pauldijou"     %% "jwt-spray-json"           % "5.0.0"
)