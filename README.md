# agent-registration-performance-tests

Performance test suite for Agent Registration, using HMRC's `performance-test-runner`.

The suite currently covers:

* a full sole trader registration journey, run through `AgentRegistrationSimulation`
* a targeted provide-details contention check, run through `AgentRegistrationProvideDetailsContentionSimulation`

The sole trader journey is the standard rate-based performance journey.

The provide-details contention simulation is separate from the rate-based `journeys.conf` setup. It starts six users at once against the same pre-seeded application, representing the six listed individuals providing details concurrently.

## Pre-requisites

### Local services

Start MongoDB using the MDTP Handbook instructions.

Start the stubbed Agent Registration services:

```bash
sm2 --start AGENT_REGISTRATION_STUBBED_GRS
```

Stop the backend and frontend if they are already running under `sm2`:

```bash
sm2 --stop AGENT_REGISTRATION
sm2 --stop AGENT_REGISTRATION_FRONTEND
```

Start `agent-registration` manually with test-only routes enabled:

```bash
cd ../agent-registration
sbt -Dapplication.router=testOnlyDoNotUseInAppConf.Routes run
```

Start `agent-registration-frontend` manually with test-only routes enabled and email verification disabled:

```bash
cd ../agent-registration-frontend
sbt -DignoreEmailVerification=true -Dapplication.router=testOnlyDoNotUseInAppConf.Routes run
```

The local default service URLs are:

```text
agent-registration:          http://localhost:22202
agent-registration-frontend: http://localhost:22201
agents-external-stubs:       http://localhost:9099
```

### Logging

The default HTTP request log level is controlled by:

```text
src/test/resources/logback.xml
```

Use `-DdebugRequests=true` when running locally if request-level debug output is needed.

Use `-DdebugRequests=false` for normal local and Jenkins runs.

## Warning

Do not run a full staging performance test from your local machine.

Full staging performance tests should be executed from Performance Jenkins:

```text
https://performance.tools.staging.tax.service.gov.uk
```

## Simulations

### AgentRegistrationSimulation

`AgentRegistrationSimulation` runs the standard rate-based performance journey configured in `journeys.conf`.

It currently runs the full sole trader registration journey.

The sole trader journey submits a complete Agent Registration application from start to finish.

Each sole trader virtual user creates a new independent application.

### AgentRegistrationProvideDetailsContentionSimulation

`AgentRegistrationProvideDetailsContentionSimulation` runs a targeted contention check.

It is not configured as a normal `journeys.conf` journey because `journeys.conf` load values represent journeys per second.

The required behaviour for this test is not sustained throughput. It is a specific contention event where six listed individuals access the same pre-seeded application at the same time.

The simulation uses:

```scala
atOnceUsers(6)
```

Each seeded application has six listed individuals.

The generated feeder is grouped so that the first six rows belong to the same application, the next six rows belong to the next application, and so on.

Example feeder grouping:

```text
app-0001-individual-0001
app-0001-individual-0002
app-0001-individual-0003
app-0001-individual-0004
app-0001-individual-0005
app-0001-individual-0006

app-0002-individual-0001
app-0002-individual-0002
...
```

Each row contains an individual sign-in slot that continues directly to:

```text
/agent-registration/provide-details/match-application/:linkId
```

The generated feeder is:

```text
src/test/resources/data/provide-details-concurrency.csv
```

## Journeys

### Sole trader journey

Configured in `journeys.conf` as:

```text
sole-trader-journey
```

This journey submits a complete Agent Registration application from start to finish.

### Provide-details contention

The provide-details contention flow is not configured in `journeys.conf`.

Do not add the old `provide-details-concurrency-journey` back to `journeys.conf` unless you deliberately want to run a sustained journeys-per-second workload.

The previous `load = 6` model meant six new provide-details journeys every second. That is not the intended contention check.

The intended contention check is handled by:

```text
uk.gov.hmrc.perftests.mmtar.AgentRegistrationProvideDetailsContentionSimulation
```

## Local sole trader performance test

Run the standard sole trader simulation with the configured 10-minute profile:

```bash
cd /Users/markbennett/workspace/agent-registration-performance-tests

sbt -DdebugRequests=false \
    -DrunLocal=true \
    -Dperftest.loadPercentage=100 \
    -Dperftest.rampupTime=1 \
    -Dperftest.constantRateTime=8 \
    -Dperftest.rampdownTime=1 \
    "gatling:testOnly uk.gov.hmrc.perftests.mmtar.AgentRegistrationSimulation"
```

The standard 10-minute profile is:

```text
1 minute ramp up
8 minutes at constant rate
1 minute ramp down
```

## Local provide-details contention test

Seed one pre-created application with six listed individuals:

```bash
cd /Users/markbennett/workspace/agent-registration-performance-tests

BACKEND_URL=http://localhost:22202 \
FRONTEND_URL=http://localhost:22201 \
STUBS_URL=http://localhost:9099 \
RESET_BEFORE_SEED=false \
PROVIDE_DETAILS_APPS=1 \
scripts/prepare_provide_details_concurrency_data.sh
```

Check the feeder:

```bash
wc -l src/test/resources/data/provide-details-concurrency.csv
head -7 src/test/resources/data/provide-details-concurrency.csv
```

For one seeded application, the CSV should contain one header row plus six individual rows:

```text
7 src/test/resources/data/provide-details-concurrency.csv
```

Run the targeted contention simulation:

```bash
sbt -DdebugRequests=false \
    -DrunLocal=true \
    "gatling:testOnly uk.gov.hmrc.perftests.mmtar.AgentRegistrationProvideDetailsContentionSimulation"
```

The expected result is:

```text
6 users started at once
150 requests total
0 failed requests
```

## Local end-to-end validation

To run both simulations locally, run the sole trader simulation first, then the contention simulation.

Seed the provide-details data before running the contention simulation:

```bash
cd /Users/markbennett/workspace/agent-registration-performance-tests

BACKEND_URL=http://localhost:22202 \
FRONTEND_URL=http://localhost:22201 \
STUBS_URL=http://localhost:9099 \
RESET_BEFORE_SEED=false \
PROVIDE_DETAILS_APPS=1 \
scripts/prepare_provide_details_concurrency_data.sh
```

Run the standard sole trader simulation:

```bash
sbt -DdebugRequests=false \
    -DrunLocal=true \
    -Dperftest.loadPercentage=100 \
    -Dperftest.rampupTime=1 \
    -Dperftest.constantRateTime=8 \
    -Dperftest.rampdownTime=1 \
    "gatling:testOnly uk.gov.hmrc.perftests.mmtar.AgentRegistrationSimulation"
```

Then run the targeted contention simulation:

```bash
sbt -DdebugRequests=false \
    -DrunLocal=true \
    "gatling:testOnly uk.gov.hmrc.perftests.mmtar.AgentRegistrationProvideDetailsContentionSimulation"
```

## Jenkins performance test

The Jenkins performance job runs:

```bash
sbt -DrunLocal=false gatling:test
```

Because `gatling:test` runs all Gatling `Simulation` classes, the Jenkins job is expected to run both simulations:

```text
uk.gov.hmrc.perftests.mmtar.AgentRegistrationSimulation
uk.gov.hmrc.perftests.mmtar.AgentRegistrationProvideDetailsContentionSimulation
```

The expected order is:

```text
1. prepareProvideDetailsConcurrencyData runs before Gatling
2. provide-details contention data is seeded
3. AgentRegistrationSimulation runs
4. AgentRegistrationProvideDetailsContentionSimulation runs
5. Gatling reports are generated and published
```

The Jenkins job should not reset shared staging data by default.

Recommended Jenkins seed defaults:

```text
RESET_BEFORE_SEED=false
PROVIDE_DETAILS_APPS=5
```

This creates five pre-seeded applications, each with six listed individuals, giving thirty feeder rows for the targeted contention simulation.

Only six rows are required for a single contention run, but five applications gives a small safety buffer.

## Jenkins data preparation

The Jenkins data preparation is wired in `build.sbt` using:

```scala
Gatling / test := (Gatling / test).dependsOn(prepareProvideDetailsConcurrencyData).value
```

This means the data preparation task runs once before Gatling starts.

It does not reset and reseed between simulations.

The recommended `build.sbt` environment block is:

```scala
val env = Seq(
  "RESET_URL" -> sys.env.getOrElse(
    "RESET_URL",
    "https://agent-registration-frontend.public.mdtp/agent-registration/test-only/reset"
  ),
  "RESET_BEFORE_SEED" -> sys.env.getOrElse("RESET_BEFORE_SEED", "false"),
  "PROVIDE_DETAILS_APPS" -> sys.env.getOrElse("PROVIDE_DETAILS_APPS", "5")
)
```

Use `RESET_BEFORE_SEED=true` only if you deliberately want to clear the target environment first.

Do not use reset in shared staging unless agreed with the team.

## Manual Jenkins-style data preparation

The Jenkins-style seed can be run manually with:

```bash
RESET_BEFORE_SEED=false \
PROVIDE_DETAILS_APPS=5 \
scripts/prepare_provide_details_concurrency_data.sh
```

For local services, include the local URLs:

```bash
BACKEND_URL=http://localhost:22202 \
FRONTEND_URL=http://localhost:22201 \
STUBS_URL=http://localhost:9099 \
RESET_BEFORE_SEED=false \
PROVIDE_DETAILS_APPS=5 \
scripts/prepare_provide_details_concurrency_data.sh
```

## Useful data preparation overrides

```bash
RESET_BEFORE_SEED=false
```

Skips the reset endpoint. This is the recommended default for shared staging.

```bash
RESET_BEFORE_SEED=true
```

Calls the configured reset endpoint before seeding. Use only when a clean environment is required and it is safe to delete existing data.

```bash
PROVIDE_DETAILS_APPS=1
```

Seeds one application and six individuals. This is enough for one local contention run.

```bash
PROVIDE_DETAILS_APPS=5
```

Seeds five applications and thirty individuals. This is the recommended Jenkins default.

## Things not to do

Do not re-add the old sustained provide-details journey unless intentionally testing sustained throughput:

```hocon
provide-details-concurrency-journey = {
  load = 6
  ...
}
```

That old configuration means:

```text
6 provide-details journeys per second
```

For a 1/8/1 profile, that creates thousands of individual journeys and does not represent the targeted six-person contention check.

Do not rely on the old calculated seed volume based on `PROVIDE_DETAILS_JPS=6`.

The current contention simulation only needs a small fixed seed count.

## Formatting

Check formatting:

```bash
sbt scalafmtCheckAll scalafmtCheck
```

Format SBT files:

```bash
sbt scalafmtSbt
```

Format all files:

```bash
sbt scalafmtAll
```

## License

This code is open source software licensed under the Apache 2.0 License.
