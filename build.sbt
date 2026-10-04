// scala-openai-client — standalone sbt build, published to Maven Central.
// The version comes from the git tag via sbt-dynver.
val scala3Version = "3.8.3"
val circeVersion = "0.14.15"
val ironVersion = "3.3.1"
val catsEffectVersion = "3.7.0"
val fs2Version = "3.11.0"
val http4sVersion = "0.23.32"

val commonScalacOptions = Seq(
  "-encoding", "utf8",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror",
  "-Xkind-projector",
  "-language:implicitConversions",
  "-Wconf:msg=is not declared infix:s"
)

// Publishing metadata. The version comes from the git tag via sbt-dynver
// (build.sbt deliberately sets no `version`), and the destination from the
// plugin: sbt 2 uploads to the Sonatype Central Portal itself.
inThisBuild(
  List(
    organization := "io.github.danbills",
    homepage     := Some(uri("https://github.com/danbills/scala-openai-client")),
    licenses     := List("MIT" -> uri("https://opensource.org/licenses/MIT")),
    developers := List(
      Developer(
        id = "danbills",
        name = "Dan Billings",
        email = "dan@megamote.com",
        url = uri("https://github.com/danbills")
      )
    ),
    scmInfo := Some(
      ScmInfo(
        uri("https://github.com/danbills/scala-openai-client"),
        "scm:git:https://github.com/danbills/scala-openai-client.git",
        Some("scm:git:git@github.com:danbills/scala-openai-client.git")
      )
    ),
    versionScheme := Some("early-semver"),
    // sbt 2 uploads to the Sonatype Central Portal itself: `publishSigned`
    // writes a signed bundle into localStaging, then `sonaUpload` /
    // `sonaRelease` push it. Credentials come from SONATYPE_USERNAME /
    // SONATYPE_PASSWORD in the environment.
    publishTo := {
      val snapshots = "https://central.sonatype.com/repository/maven-snapshots/"
      if (isSnapshot.value) Some("central-snapshots".at(snapshots)) else localStaging.value
    }
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(LocalProject("demo"))
  .settings(
    name := "scala-openai-client",
    description := "A Scala 3 client library for OpenAI-compatible HTTP endpoints — " +
      "chat completions, streaming, TTS, audio transcription, with Iron refinement " +
      "types, Circe codecs, and fs2 audio I/O (http4s + cats-effect).",
    scalaVersion := scala3Version,
    scalacOptions := commonScalacOptions,
    libraryDependencies ++= Seq(
      "io.circe"           %% "circe-core"          % circeVersion,
      "io.circe"           %% "circe-generic"       % circeVersion,
      "io.circe"           %% "circe-parser"        % circeVersion,
      "io.github.iltotore" %% "iron"                % ironVersion,
      "io.github.iltotore" %% "iron-circe"          % ironVersion,
      "org.typelevel"      %% "cats-effect"         % catsEffectVersion,
      "org.typelevel"      %% "cats-free"           % "2.13.0",
      "co.fs2"             %% "fs2-core"            % fs2Version,
      "co.fs2"             %% "fs2-io"              % fs2Version,
      "org.http4s"         %% "http4s-ember-client" % http4sVersion,
      "org.http4s"         %% "http4s-circe"        % http4sVersion,
      "org.scalatest"      %% "scalatest"           % "3.2.19" % Test
    )
  )

// Runnable demos against live servers (LLM_URL, WHISPER_URL, TTS_URL); never published.
lazy val demo = project
  .in(file("demo"))
  .dependsOn(root)
  .settings(
    name := "scala-openai-client-demo",
    scalaVersion := scala3Version,
    scalacOptions := commonScalacOptions,
    publish / skip := true
  )
