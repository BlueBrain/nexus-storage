/*
scalafmt: {
  style = defaultWithAlign
  maxColumn = 150
  align.tokens = [
    { code = "=>", owner = "Case" }
    { code = "?", owner = "Case" }
    { code = "extends", owner = "Defn.(Class|Trait|Object)" }
    { code = "//", owner = ".*" }
    { code = "{", owner = "Template" }
    { code = "}", owner = "Template" }
    { code = ":=", owner = "Term.ApplyInfix" }
    { code = "++=", owner = "Term.ApplyInfix" }
    { code = "+=", owner = "Term.ApplyInfix" }
    { code = "%", owner = "Term.ApplyInfix" }
    { code = "%%", owner = "Term.ApplyInfix" }
    { code = "%%%", owner = "Term.ApplyInfix" }
    { code = "->", owner = "Term.ApplyInfix" }
    { code = "?", owner = "Term.ApplyInfix" }
    { code = "<-", owner = "Enumerator.Generator" }
    { code = "?", owner = "Enumerator.Generator" }
    { code = "=", owner = "(Enumerator.Val|Defn.(Va(l|r)|Def|Type))" }
  ]
}
 */

// Dependency versions
val akkaVersion           = "2.5.23"
val akkaHttpVersion       = "10.1.9"
val apacheCompressVersion = "1.18"
val alpakkaVersion        = "1.1.0"
val catsVersion           = "1.6.1"
val catsEffectVersion     = "1.3.1"
val circeVersion          = "0.11.1"
val commonsVersion        = "0.17.2"
val iamVersion            = "71232da8"
val mockitoVersion        = "1.5.12"
val monixVersion          = "3.0.0-RC3"
val pureconfigVersion     = "0.11.1"
val scalaTestVersion      = "3.0.8"

// Dependencies modules
lazy val akkaHttp        = "com.typesafe.akka"       %% "akka-http"                % akkaHttpVersion
lazy val akkaHttpTestKit = "com.typesafe.akka"       %% "akka-http-testkit"        % akkaHttpVersion
lazy val alpakkaFiles    = "com.lightbend.akka"      %% "akka-stream-alpakka-file" % alpakkaVersion
lazy val apacheCompress  = "org.apache.commons"      % "commons-compress"          % apacheCompressVersion
lazy val akkaSlf4j       = "com.typesafe.akka"       %% "akka-slf4j"               % akkaVersion
lazy val akkaStream      = "com.typesafe.akka"       %% "akka-stream"              % akkaVersion
lazy val catsCore        = "org.typelevel"           %% "cats-core"                % catsVersion
lazy val catsEffect      = "org.typelevel"           %% "cats-effect"              % catsEffectVersion
lazy val circeCore       = "io.circe"                %% "circe-core"               % circeVersion
lazy val commonsCore     = "ch.epfl.bluebrain.nexus" %% "commons-core"             % commonsVersion
lazy val commonsKamon    = "ch.epfl.bluebrain.nexus" %% "commons-kamon"            % commonsVersion
lazy val commonsTest     = "ch.epfl.bluebrain.nexus" %% "commons-test"             % commonsVersion
lazy val iamClient       = "ch.epfl.bluebrain.nexus" %% "iam-client"               % iamVersion
lazy val mockito         = "org.mockito"             %% "mockito-scala"            % mockitoVersion
lazy val monixEval       = "io.monix"                %% "monix-eval"               % monixVersion
lazy val pureconfig      = "com.github.pureconfig"   %% "pureconfig"               % pureconfigVersion
lazy val scalaTest       = "org.scalatest"           %% "scalatest"                % scalaTestVersion

lazy val storage = project
  .in(file("."))
  .settings(assemblySettings, testSettings, buildInfoSettings)
  .enablePlugins(BuildInfoPlugin, ServicePackagingPlugin)
  .aggregate(client)
  .settings(
    name                  := "storage",
    moduleName            := "storage",
    coverageFailOnMinimum := true,
    libraryDependencies ++= Seq(
      apacheCompress,
      akkaHttp,
      akkaStream,
      akkaSlf4j,
      alpakkaFiles,
      catsCore,
      catsEffect,
      circeCore,
      commonsCore,
      commonsKamon,
      iamClient,
      monixEval,
      pureconfig,
      akkaHttpTestKit % Test,
      commonsTest     % Test,
      mockito         % Test,
      scalaTest       % Test
    ),
    cleanFiles ++= Seq(
      baseDirectory.value / "permissions-fixer" / "target" / "**",
      baseDirectory.value / "nexus-storage.jar"
    ),
    mappings in Universal := {
      val universalMappings = (mappings in Universal).value :+ cargo.value
      universalMappings.foldLeft(Vector.empty[(File, String)]) {
        case (acc, (file, filename)) if filename.contains("kanela-agent") =>
          acc :+ (file, "lib/instrumentation-agent.jar")
        case (acc, other) =>
          acc :+ other
      }
    }
  )

lazy val client = project
  .in(file("client"))
  .disablePlugins(AssemblyPlugin)
  .settings(
    testSettings,
    name                  := "storage-client",
    moduleName            := "storage-client",
    coverageFailOnMinimum := true,
    libraryDependencies ++= Seq(
      akkaHttp,
      akkaStream,
      catsCore,
      circeCore,
      commonsCore,
      iamClient,
      akkaHttpTestKit % Test,
      commonsTest     % Test,
      mockito         % Test,
      scalaTest       % Test,
    )
  )

lazy val assemblySettings = Seq(
  test in assembly               := {},
  assemblyOutputPath in assembly := baseDirectory.value / "nexus-storage.jar",
  assemblyMergeStrategy in assembly := {
    case PathList("org", "apache", "commons", "logging", xs @ _*)        => MergeStrategy.last
    case PathList("akka", "remote", "kamon", xs @ _*)                    => MergeStrategy.last
    case PathList("kamon", "instrumentation", "akka", "remote", xs @ _*) => MergeStrategy.last
    case "META-INF/versions/9/module-info.class"                         => MergeStrategy.discard
    case x =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
  }
)

lazy val testSettings = Seq(
  Test / testOptions       += Tests.Argument(TestFrameworks.ScalaTest, "-o", "-u", "target/test-reports"),
  Test / parallelExecution := false
)

lazy val buildInfoSettings = Seq(
  buildInfoKeys    := Seq[BuildInfoKey](version),
  buildInfoPackage := "ch.epfl.bluebrain.nexus.storage.config"
)

lazy val cargo = taskKey[(File, String)]("Run Cargo to build 'nexus-fixer'")

cargo := {
  import scala.sys.process._

  val log = streams.value.log
  val cmd = Process(Seq("cargo", "build", "--release"), baseDirectory.value / "permissions-fixer")
  if ((cmd !) == 0) {
    log.success("Cargo build successful.")
    (baseDirectory.value / "permissions-fixer" / "target" / "release" / "nexus-fixer") -> "bin/nexus-fixer"
  } else {
    log.error("Cargo build failed.")
    throw new RuntimeException
  }
}

inThisBuild(
  List(
    homepage := Some(url("https://github.com/BlueBrain/nexus-storage")),
    licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    scmInfo  := Some(ScmInfo(url("https://github.com/BlueBrain/nexus-storage"), "scm:git:git@github.com:BlueBrain/nexus-storage.git")),
    developers := List(
      Developer("bogdanromanx", "Bogdan Roman", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
      Developer("hygt", "Henry Genet", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
      Developer("umbreak", "Didac Montero Mendez", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
      Developer("wwajerowicz", "Wojtek Wajerowicz", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/"))
    ),
    // These are the sbt-release-early settings to configure
    releaseEarlyWith              := BintrayPublisher,
    releaseEarlyNoGpg             := true,
    releaseEarlyEnableSyncToMaven := false
  )
)

addCommandAlias("review", ";clean;scalafmtSbt;test:scalafmtCheck;scalafmtSbtCheck;coverage;scapegoat;test;coverageReport;coverageAggregate")
