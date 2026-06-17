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

    val skipSeed = sys.props.get("skipSeed").exists(_ == "true")
    if (skipSeed) {
        log.info("Skipping provide-details concurrency data preparation (skipSeed=true)")
    } else {

    val runLocal =
        sys.props.get("runLocal").forall(_ == "true")

    val backendUrl =
        if (runLocal) "http://localhost:22202"
        else "https://agent-registration.protected.mdtp"

    val frontendUrl =
        if (runLocal) "http://localhost:22201"
        else "https://agent-registration-frontend.public.mdtp"

    val stubsUrl =
        if (runLocal) "http://localhost:9099"
        else "https://www.staging.tax.service.gov.uk"

    val resetUrl =
        if (runLocal) s"$frontendUrl/agent-registration/test-only/reset"
        else "https://agent-registration-frontend.public.mdtp/agent-registration/test-only/reset"

    log.info(s"Preparing provide-details contention data. runLocal=$runLocal")

    val env = Seq(
        "BACKEND_URL" -> sys.env.getOrElse("BACKEND_URL", backendUrl),
        "FRONTEND_URL" -> sys.env.getOrElse("FRONTEND_URL", frontendUrl),
        "STUBS_URL" -> sys.env.getOrElse("STUBS_URL", stubsUrl),
        "RESET_URL" -> sys.env.getOrElse("RESET_URL", resetUrl),
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
    } // end if (!skipSeed)
}

Gatling / test := (Gatling / test).dependsOn(prepareProvideDetailsConcurrencyData).value
