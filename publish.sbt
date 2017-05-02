publishMavenStyle := true

publishTo := {
  val SonatypeRepositoryRoot = "https://oss.sonatype.org/"
  if (version.value.trim.endsWith("SNAPSHOT")) {
    Some("Sonatype Nexus Snapshots" at SonatypeRepositoryRoot + "content/repositories/snapshots")
  } else {
    Some("Sonatype Nexus Staging" at SonatypeRepositoryRoot + "service/local/staging/deploy/maven2")
  }
}

publishArtifact in Test := false

pomIncludeRepository := { _ => false }

useGpg := true