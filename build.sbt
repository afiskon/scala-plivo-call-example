name := "plivo-call-example"

version := "0.1"

scalaVersion := "2.11.6"

scalacOptions ++= Seq("-Xmax-classfile-name", "100")

libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-actor" % "2.3.12",
    "com.typesafe.akka" %% "akka-http-experimental" % "1.0",
    "org.json4s" %% "json4s-jackson" % "3.2.11"
  )

