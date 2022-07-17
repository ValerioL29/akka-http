name := "akka-http"

version := "0.1"

scalaVersion := "2.13.8"

val AkkaVersion = "2.6.19"
val AkkaHttpVersion = "10.2.9"
val scalaTestVersion = "3.2.12"

libraryDependencies ++= Seq(
  // akka stream and akka actor
  "com.typesafe.akka" %% "akka-actor-typed"         % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream"              % AkkaVersion,
  // akka http
  "com.typesafe.akka" %% "akka-http"                % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json"     % AkkaHttpVersion,
  // testing
  "com.typesafe.akka" %% "akka-stream-testkit"      % AkkaVersion      % Test,
  "com.typesafe.akka" %% "akka-http-testkit"        % AkkaHttpVersion  % Test,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion      % Test,
  "org.scalatest"     %% "scalatest"                % scalaTestVersion,
  // JWT
  "com.pauldijou"     %% "jwt-spray-json"           % "5.0.0",
  "ch.qos.logback"    % "logback-classic"           % "1.2.11"
)