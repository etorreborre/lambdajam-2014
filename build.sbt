build.sbt

name := "lambdajam-2014"

version := "1.0"

scalaVersion := "2.10"

libraryDependencies ++= Seq(
 "org.scalaz" % "scalaz-stream" % "0.3.2",
 "org.specs2" % "specs2-core" % "2.3.11")