name := "sbt-s3-resolver"

version := "0.12.0-SNAPSHOT"

description := "SBT S3 Resolver Plugin"

homepage := Some(url("https://github.com/margussipria/sbt-s3-resolver"))
licenses := Seq("Apache License, Version 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

organization := "eu.sipria.sbt"
organizationName := "None"
organizationHomepage := Some(url("https://github.com/margussipria"))

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

scalacOptions := Seq(
  "-encoding", "UTF-8",
  "-unchecked",
  "-deprecation",
  "-language:implicitConversions",
  "-feature",
  "-Xlint"
) ++ {
  val Scala2_10 = (2, 10)
  val Scala2_11 = (2, 11)
  val Scala2_12 = (2, 12)

  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some(Scala2_10) => Seq.empty
    case Some(Scala2_11) => Seq(
      "-Ywarn-unused-import"
    )
    case Some(Scala2_12) => Seq(
      "-opt:l:project"
    )
    case _ => throw new Exception("Unknown Scala version")
  }
}

sbtPlugin := true

val AmazonSDKVersion = "1.11.125"

def amazonSDK(artifactId: String) = {
  "com.amazonaws" % artifactId % AmazonSDKVersion
}

libraryDependencies ++= Seq(
  amazonSDK("aws-java-sdk-s3"),
  amazonSDK("aws-java-sdk-sts"),
  "org.apache.ivy" % "ivy" % "2.3.0"
)

developers := List(
  Developer("margussipria", "Margus Sipria", "margus+ssbt-s3-resolver@sipria.fi", url("https://github.com/margussipria"))
)

scmInfo := Some(ScmInfo(
  browseUrl = url("http://github.com/margussipria/sbt-s3-resolver"),
  connection = "scm:git:https://github.com/margussipria/sbt-s3-resolver.git",
  devConnection = Some("scm:git:git@github.com:margussipria/sbt-s3-resolver.git")
))
