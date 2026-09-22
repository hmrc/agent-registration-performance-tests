import sbt._

object Dependencies {

  val test = Seq(
    "uk.gov.hmrc"          %% "performance-test-runner"   % "6.3.0"         % Test,
    "com.lihaoyi"           %% "ujson"                     % "3.3.1"         % Test
  )

}
