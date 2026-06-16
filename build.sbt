lazy val root = (project in file("."))
  .enablePlugins(GatlingPlugin)
  .settings(
    name := "agent-registration-performance-tests",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.12",
    //implicitConversions & postfixOps are Gatling recommended -language settings
    scalacOptions ++= Seq("-feature", "-language:implicitConversions", "-language:postfixOps"),
    // Enabling sbt-auto-build plugin provides DefaultBuildSettings with default `testOptions` from `sbt-settings` plugin.
    // These testOptions are not compatible with `sbt gatling:test`. So we have to override testOptions here.
    Test / testOptions := Seq.empty,
    libraryDependencies ++= Dependencies.test
  )
lazy val prepareProvideDetailsConcurrencyData =
    taskKey[Unit]("Reset and seed provide-details concurrency data before Gatling")

prepareProvideDetailsConcurrencyData := {
    val log = streams.value.log

    val isJenkinsPerformanceRun =
        sys.props.get("runLocal").contains("false") &&
          !sys.props.get("perftest.runSmokeTest").contains("true")

    if (isJenkinsPerformanceRun) {
        log.info("Preparing provide-details contention data for Jenkins performance run")

        val env = Seq(
            "RESET_URL" -> sys.env.getOrElse(
                "RESET_URL",
                "https://agent-registration-frontend.public.mdtp/agent-registration/test-only/reset"
            ),
            "RESET_BEFORE_SEED" -> sys.env.getOrElse("RESET_BEFORE_SEED", "false"),

            // The contention simulation uses atOnceUsers(6), so one seeded app is enough.
            // Use 5 as a buffer in case the feeder is consumed unexpectedly or the test is rerun.
            "PROVIDE_DETAILS_APPS" -> sys.env.getOrElse("PROVIDE_DETAILS_APPS", "5")
        )

        val exitCode =
            scala.sys.process.Process(
                "scripts/prepare_provide_details_concurrency_data.sh",
                baseDirectory.value,
                env: _*
            ).!

        if (exitCode != 0) {
            sys.error(s"prepare_provide_details_concurrency_data.sh failed with exit code $exitCode")
        }

        log.info("Provide-details contention data prepared")
    } else {
        log.info("Skipping provide-details contention data preparation")
    }
}

Gatling / test := (Gatling / test).dependsOn(prepareProvideDetailsConcurrencyData).value
