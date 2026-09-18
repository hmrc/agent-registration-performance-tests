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

lazy val prepareRiskOutcomeData =
    taskKey[Unit]("Seed deterministic risk-outcome data before Gatling")

lazy val preparePerformanceTestData =
    taskKey[Unit]("Prepare all deterministic performance test data before Gatling")

prepareProvideDetailsConcurrencyData := {
    val log = streams.value.log

    val jvmOptions = javaOptions.value

    def sysProp(name: String): Option[String] =
        sys.props.get(name).orElse {
            jvmOptions.collectFirst {
                case option if option.startsWith(s"-D$name=") =>
                    option.stripPrefix(s"-D$name=")
            }
        }

    val runLocal =
        sysProp("runLocal").forall(_ == "true")

    val backendUrl =
        if (runLocal) {
            "http://localhost:22202"
        } else {
            sys.env.getOrElse("BACKEND_URL", "https://agent-registration.protected.mdtp")
        }

    val frontendUrl =
        if (runLocal) {
            "http://localhost:22201"
        } else {
            sys.env.getOrElse("FRONTEND_URL", "https://www.staging.tax.service.gov.uk")
        }

    val stubsUrl =
        if (runLocal) {
            "http://localhost:9099"
        } else {
            sys.env.getOrElse("STUBS_URL", "https://www.staging.tax.service.gov.uk/agents-external-stubs")
        }

    val resetUrl =
        sys.env.getOrElse("RESET_URL", s"$frontendUrl/agent-registration/test-only/reset")

    log.info(s"Preparing provide-details contention data. runLocal=$runLocal")
    log.info(s"Using backend URL: $backendUrl")
    log.info(s"Using frontend URL: $frontendUrl")
    log.info(s"Using stubs URL: $stubsUrl")

    val env = Seq(
        "BACKEND_URL" -> backendUrl,
        "FRONTEND_URL" -> frontendUrl,
        "STUBS_URL" -> stubsUrl,
        "RESET_URL" -> resetUrl,
        "RESET_BEFORE_SEED" -> sys.env.getOrElse("RESET_BEFORE_SEED", "false"),
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
}

prepareRiskOutcomeData := {
    val log = streams.value.log

    val jvmOptions = javaOptions.value

    def sysProp(name: String): Option[String] =
        sys.props.get(name).orElse {
            jvmOptions.collectFirst {
                case option if option.startsWith(s"-D$name=") =>
                    option.stripPrefix(s"-D$name=")
            }
        }

    val runLocal =
        sysProp("runLocal").forall(_ == "true")

    val backendUrl =
        if (runLocal) {
            "http://localhost:22202"
        } else {
            sys.env.getOrElse("BACKEND_URL", "https://agent-registration.protected.mdtp")
        }

    val frontendUrl =
        if (runLocal) {
            "http://localhost:22201"
        } else {
            sys.env.getOrElse("FRONTEND_URL", "https://www.staging.tax.service.gov.uk")
        }

    val stubsUrl =
        if (runLocal) {
            "http://localhost:9099"
        } else {
            sys.env.getOrElse("STUBS_URL", "https://www.staging.tax.service.gov.uk")
        }

    log.info(s"Preparing risk-outcome performance data. runLocal=$runLocal")
    log.info(s"Using backend URL: $backendUrl")
    log.info(s"Using frontend URL: $frontendUrl")
    log.info(s"Using stubs URL: $stubsUrl")

    val env = Seq(
        "BACKEND_URL" -> backendUrl,
        "FRONTEND_URL" -> frontendUrl,
        "STUBS_URL" -> stubsUrl,
        "RESET_URL" -> sys.env.getOrElse("RESET_URL", s"$frontendUrl/agent-registration/test-only/reset"),
        "RESET_BEFORE_SEED" -> sys.env.getOrElse("RESET_BEFORE_SEED", "false"),
        "RISK_OUTCOME_POOLS" -> sys.env.getOrElse("RISK_OUTCOME_POOLS", "load"),
        "RISK_OUTCOME_OUTPUT_DIR" -> sys.env.getOrElse("RISK_OUTCOME_OUTPUT_DIR", "src/test/resources/data/risk-outcomes"),
        "RISK_OUTCOME_TOTAL_PEAK_JPS" -> sys.env.getOrElse("RISK_OUTCOME_TOTAL_PEAK_JPS", sysProp("riskOutcome.totalPeakJps").getOrElse("0.1")),
        "RISK_OUTCOME_RAMPUP_MINUTES" -> sys.env.getOrElse("RISK_OUTCOME_RAMPUP_MINUTES", sysProp("riskOutcome.rampUpMinutes").getOrElse("1")),
        "RISK_OUTCOME_STEADY_MINUTES" -> sys.env.getOrElse("RISK_OUTCOME_STEADY_MINUTES", sysProp("riskOutcome.steadyMinutes").getOrElse("8")),
        "RISK_OUTCOME_RAMPDOWN_MINUTES" -> sys.env.getOrElse("RISK_OUTCOME_RAMPDOWN_MINUTES", sysProp("riskOutcome.rampDownMinutes").getOrElse("1")),
        "RISK_OUTCOME_BUFFER_PERCENT" -> sys.env.getOrElse("RISK_OUTCOME_BUFFER_PERCENT", "20"),
        "RISK_OUTCOME_LOAD_MAIN_RECORDS" -> sys.env.getOrElse("RISK_OUTCOME_LOAD_MAIN_RECORDS", ""),
        "RISK_OUTCOME_LOAD_CONTROL_RECORDS" -> sys.env.getOrElse("RISK_OUTCOME_LOAD_CONTROL_RECORDS", ""),
        "RISK_OUTCOME_LOAD_SCALE_RECORDS" -> sys.env.getOrElse("RISK_OUTCOME_LOAD_SCALE_RECORDS", ""),
        "RISK_OUTCOME_SMOKE_RECORDS" -> sys.env.getOrElse("RISK_OUTCOME_SMOKE_RECORDS", "1")
    )

    val exitCode =
        scala.sys.process.Process(
            "scripts/prepare_risk_outcome_data.sh",
            baseDirectory.value,
            env.filterNot(_._2.isEmpty): _*
        ).!

    if (exitCode != 0) {
        sys.error(s"prepare_risk_outcome_data.sh failed with exit code $exitCode")
    }

    log.info("Risk-outcome performance data prepared")
}

preparePerformanceTestData := Def.sequential(
    prepareProvideDetailsConcurrencyData,
    prepareRiskOutcomeData
).value

Gatling / test := (Gatling / test).dependsOn(preparePerformanceTestData).value
