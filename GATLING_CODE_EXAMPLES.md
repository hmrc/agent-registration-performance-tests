# Gatling Test Implementation - Code Examples

## Basic Setup

### 1. HttpProtocol Configuration

```scala
// HttpProtocol.scala
package uk.gov.hmrc.agentregistration.gatling

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.http.protocol.HttpProtocolBuilder

object HttpProtocol {

  def httpConf: HttpProtocolBuilder = http
    .baseUrl("http://localhost:22201")
    .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    .acceptEncodingHeader("gzip, deflate")
    .acceptLanguageHeader("en-US,en;q=0.9")
    .userAgentHeader("Mozilla/5.0")
    .disableCaching // Ensure fresh responses
    .shareConnections // Use shared connection pool

  def grsStubConf: HttpProtocolBuilder = http
    .baseUrl("http://localhost:9717") // Sole trader GRS
    .acceptHeader("application/json")
    .disableCaching

}
```

### 2. Feeder for Test Data

```scala
// Feeders.scala
package uk.gov.hmrc.agentregistration.gatling

import io.gatling.core.Predef._
import scala.util.Random

object Feeders {

  def applicantNameFeeder = Iterator.continually(Map(
    "firstName" -> s"Test${Random.nextInt(10000)}",
    "lastName" -> "Applicant"
  ))

  def businessNameFeeder = Iterator.continually(Map(
    "businessName" -> s"Test Business ${Random.nextInt(10000)}"
  ))

  def emailFeeder = Iterator.continually(Map(
    "applicantEmail" -> s"applicant${Random.nextInt(100000)}@test.com",
    "businessEmail" -> s"business${Random.nextInt(100000)}@test.com"
  ))

  def phoneFeeder = Iterator.continually(Map(
    "applicantPhone" -> s"0${Random.nextInt(7) + 1}${String.format("%09d", Random.nextInt(1000000000))}",
    "businessPhone" -> s"020${String.format("%08d", Random.nextInt(100000000))}"
  ))

  def amlsFeeder = Iterator.continually(Map(
    "amlsSupervisor" -> "Financial Conduct Authority",
    "amlsRegNumber" -> s"${Random.nextInt(1000000000)}",
    "amlsExpiryDate" -> "31122025"
  ))

  def partnerNameFeeder = Iterator.continually(Map(
    "partnerFirstName" -> s"Partner${Random.nextInt(10000)}",
    "partnerLastName" -> "Test",
    "partnerDOB" -> "01011980",
    "partnerSharePercent" -> s"${Random.nextInt(90) + 10}"
  ))

}
```

### 3. Session Helpers

```scala
// SessionHelpers.scala
package uk.gov.hmrc.agentregistration.gatling

import io.gatling.core.Predef._
import io.gatling.core.session.Session

object SessionHelpers {

  // Extract applicationId from response and store in session
  def extractApplicationId = {
    jsonPath("$.applicationId").saveAs("applicationId")
  }

  // Extract journeyId from URL redirect
  def extractJourneyId = {
    regex("""journeyId=([^&]+)""").saveAs("journeyId")
  }

  // Extract linkId from email (simulated)
  def extractLinkId = {
    jsonPath("$.linkId").saveAs("linkId")
  }

  // Extract uploadId from upscan response
  def extractUploadId = {
    jsonPath("$.uploadId").saveAs("uploadId")
  }

  // Store dynamic path params
  def storePathParams(agentType: String, businessType: String, userRole: String) = {
    exec(session => {
      session
        .set("agentType", agentType)
        .set("businessType", businessType)
        .set("userRole", userRole)
    })
  }

}
```

---

## Request Chains by Journey Stage

### Stage 1: Business Classification

```scala
// BusinessClassificationChain.scala
package uk.gov.hmrc.agentregistration.gatling.chains

import io.gatling.core.Predef._
import io.gatling.http.Predef._

object BusinessClassificationChain {

  val selectAgentType = exec(
    http("GET Agent Type Selection")
      .get("/apply/about-your-business/agent-type")
      .check(status.is(200))
  ).pause(2, 3)
    .exec(
      http("POST Agent Type Selection")
        .post("/apply/about-your-business/agent-type")
        .formParam("agentType", "UkTaxAgent")
        .check(status.is(303))
    )

  val selectBusinessType = exec(
    http("GET Business Type Selection")
      .get("/apply/about-your-business/business-type")
      .check(status.is(200))
  ).pause(2, 3)
    .exec(
      http("POST Business Type - Sole Trader")
        .post("/apply/about-your-business/business-type")
        .formParam("businessType", "${businessType}")
        .check(status.is(303))
    )

  val selectUserRole = exec(
    http("GET User Role Selection")
      .get("/apply/about-your-business/user-role")
      .check(status.is(200))
  ).pause(2, 3)
    .exec(
      http("POST User Role")
        .post("/apply/about-your-business/user-role")
        .formParam("userRole", "${userRole}")
        .check(status.is(303))
    )

  val selectSignInType = exec(
    http("GET Sign-In Type Selection")
      .get("/apply/about-your-business/agent-online-services-account")
      .check(status.is(200))
  ).pause(2, 3)
    .exec(
      http("POST Sign-In Type")
        .post("/apply/about-your-business/agent-online-services-account")
        .formParam("typeOfSignIn", "HmrcOnlineServices")
        .check(status.is(303))
    )

  val viewSignInPage = exec(
    http("GET Sign-In Page")
      .get("/apply/about-your-business/sign-in")
      .check(status.is(200))
  )

}
```

### Stage 2: GRS Journey

```scala
// GRSJourneyChain.scala
package uk.gov.hmrc.agentregistration.gatling.chains

import io.gatling.core.Predef._
import io.gatling.http.Predef._

object GRSJourneyChain {

  val initiateApplication = exec(
    http("GET Initiate Agent Application")
      .get("/apply/internal/initiate-agent-application/${agentType}/${businessType}/${userRole}")
      .check(status.is(303))
      .check(header("Location").saveAs("nextUrl"))
  ).pause(1)

  val startGRSJourney = exec(
    http("GET Start GRS Journey")
      .get("/apply/internal/grs/start-journey")
      .check(status.is(303))
      .check(header("Location").saveAs("grsUrl"))
  ).pause(2)

  // Simulate user completing GRS (in real test, would be external)
  val simulateGRSCompletion = exec(
    session => {
      val journeyId = scala.util.Random.alphanumeric.take(20).mkString
      session.set("journeyId", journeyId)
    }
  ).pause(5) // Simulate time spent on GRS pages

  val grsCallback = exec(
    http("GET GRS Journey Callback")
      .get("/apply/internal/grs/journey-callback?journeyId=${journeyId}")
      .check(status.is(303))
  ).pause(1)

  val refusalToDealCheck = exec(
    http("GET Refusal to Deal Check")
      .get("/apply/internal/refusal-to-deal-with-check")
      .check(status.is(303))
  ).pause(1)

  val deceasedCheck = exec(
    http("GET Deceased Check")
      .get("/apply/internal/deceased-check")
      .check(status.is(303))
  ).pause(1)

  val companiesHouseStatusCheck = exec(
    http("GET Companies House Status Check")
      .get("/apply/internal/companies-house-status-check")
      .check(status.is(303))
  ).pause(1)

}
```

### Stage 3: Task List & Details

```scala
// DetailsCollectionChain.scala
package uk.gov.hmrc.agentregistration.gatling.chains

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import uk.gov.hmrc.agentregistration.gatling.Feeders._

object DetailsCollectionChain {

  val viewTaskList = exec(
    http("GET Task List")
      .get("/apply/task-list")
      .check(status.is(200))
  ).pause(2)

  val collectApplicantDetails = feed(emailFeeder)
    .feed(phoneFeeder)
    .exec(
      http("GET Applicant Name Page")
        .get("/apply/applicant/applicant-name")
        .check(status.is(200))
    ).pause(2, 3)
    .exec(
      http("POST Applicant Name")
        .post("/apply/applicant/applicant-name")
        .formParam("firstName", "Mark")
        .formParam("lastName", "Bennett")
        .check(status.is(303))
    ).pause(1)
    .exec(
      http("POST Applicant Phone")
        .post("/apply/applicant/telephone-number")
        .formParam("telephoneNumber", "${applicantPhone}")
        .check(status.is(303))
    ).pause(1)
    .exec(
      http("POST Applicant Email")
        .post("/apply/applicant/email-address")
        .formParam("emailAddress", "${applicantEmail}")
        .check(status.is(303))
    ).pause(2)
    .exec(
      http("GET Email Verification")
        .get("/apply/applicant/verify-email-address")
        .check(status.is(200))
    ).pause(1)
    .exec(
      http("POST Applicant Check Answers")
        .post("/apply/applicant/check-your-answers")
        .formParam("confirmCheckAnswers", "true")
        .check(status.is(303))
    )

  val collectBusinessDetails = feed(businessNameFeeder)
    .exec(
      http("POST Business Name")
        .post("/apply/agent-details/business-name")
        .formParam("businessName", "${businessName}")
        .check(status.is(303))
    ).pause(1)
    .exec(
      http("POST Business Phone")
        .post("/apply/agent-details/telephone-number")
        .formParam("telephoneNumber", "${businessPhone}")
        .check(status.is(303))
    ).pause(1)
    .exec(
      http("POST Business Email")
        .post("/apply/agent-details/email-address")
        .formParam("emailAddress", "${businessEmail}")
        .check(status.is(303))
    ).pause(2)
    .exec(
      http("GET Business Email Verification")
        .get("/apply/agent-details/verify-email-address")
        .check(status.is(200))
    ).pause(1)
    .exec(
      http("GET Address Lookup")
        .get("/apply/agent-details/correspondence-address")
        .check(status.is(200))
    ).pause(2, 3)

  val collectAMLSDetails = feed(amlsFeeder)
    .exec(
      http("POST AMLS Supervisor")
        .post("/apply/anti-money-laundering/supervisor-name")
        .formParam("amlsSupervisor", "${amlsSupervisor}")
        .check(status.is(303))
    ).pause(1)
    .exec(
      http("POST AMLS Registration Number")
        .post("/apply/anti-money-laundering/registration-number")
        .formParam("amlsRegNumber", "${amlsRegNumber}")
        .check(status.is(303))
    ).pause(1)
    .exec(
      http("POST AMLS Expiry Date")
        .post("/apply/anti-money-laundering/supervision-runs-out")
        .formParam("amlsExpiryDate", "${amlsExpiryDate}")
        .check(status.is(303))
    ).pause(1)
    .exec(
      http("GET AMLS Evidence Upload")
        .get("/apply/anti-money-laundering/evidence")
        .check(status.is(200))
    ).pause(1)
    .exec(
      http("GET AMLS Upload Status")
        .get("/apply/anti-money-laundering/evidence/check-upload-status-js")
        .check(status.is(200))
    ).pause(1)
    .exec(
      http("POST AMLS Check Answers")
        .post("/apply/anti-money-laundering/check-your-answers")
        .formParam("confirmCheckAnswers", "true")
        .check(status.is(303))
    )

}
```

### Stage 4: Declaration & Submission

```scala
// SubmissionChain.scala
package uk.gov.hmrc.agentregistration.gatling.chains

import io.gatling.core.Predef._
import io.gatling.http.Predef._

object SubmissionChain {

  val acceptHMRCStandard = exec(
    http("GET HMRC Standard Page")
      .get("/apply/agent-standard/accept-agent-standard")
      .check(status.is(200))
  ).pause(2)
    .exec(
      http("POST Accept HMRC Standard")
        .post("/apply/agent-standard/accept-agent-standard")
        .formParam("acceptHmrcStandard", "true")
        .check(status.is(303))
    )

  val submitDeclaration = exec(
    http("GET Declaration Page")
      .get("/apply/agent-declaration/confirm-declaration")
      .check(status.is(200))
  ).pause(2, 3)
    .exec(
      http("POST Submit Declaration")
        .post("/apply/agent-declaration/confirm-declaration")
        .formParam("confirmDeclaration", "true")
        .check(status.is(303))
        .check(header("Location").saveAs("submissionUrl"))
    )

  val viewSubmissionConfirmation = exec(
    http("GET Application Submitted")
      .get("/apply/application-submitted")
      .check(status.is(200))
  ).pause(1)
    .exec(
      http("GET View Progress")
        .get("/apply/view-application-progress")
        .check(status.is(200))
    )

}
```

---

## Complete Scenario

```scala
// SoleTraderScenario.scala
package uk.gov.hmrc.agentregistration.gatling.scenarios

import io.gatling.core.Predef._
import uk.gov.hmrc.agentregistration.gatling.chains._
import uk.gov.hmrc.agentregistration.gatling.SessionHelpers._

object SoleTraderScenario {

  val soleTraderJourney = scenario("Sole Trader Registration Journey")
    .exec(storePathParams("UkTaxAgent", "SoleTrader", "Owner"))
    .exec(BusinessClassificationChain.selectAgentType)
    .exec(BusinessClassificationChain.selectBusinessType)
    .exec(BusinessClassificationChain.selectUserRole)
    .exec(BusinessClassificationChain.selectSignInType)
    .exec(BusinessClassificationChain.viewSignInPage)
    .exec(GRSJourneyChain.initiateApplication)
    .exec(GRSJourneyChain.startGRSJourney)
    .exec(GRSJourneyChain.simulateGRSCompletion)
    .exec(GRSJourneyChain.grsCallback)
    .exec(GRSJourneyChain.refusalToDealCheck)
    .exec(GRSJourneyChain.deceasedCheck)
    .exec(DetailsCollectionChain.viewTaskList)
    .exec(DetailsCollectionChain.collectApplicantDetails)
    .exec(DetailsCollectionChain.collectBusinessDetails)
    .exec(DetailsCollectionChain.collectAMLSDetails)
    .exec(SubmissionChain.acceptHMRCStandard)
    .exec(SubmissionChain.submitDeclaration)
    .exec(SubmissionChain.viewSubmissionConfirmation)

}
```

---

## Simulation Class

```scala
// RampUpSimulation.scala
package uk.gov.hmrc.agentregistration.gatling.simulations

import io.gatling.core.Predef._
import io.gatling.core.scenario.Simulation
import scala.concurrent.duration._
import uk.gov.hmrc.agentregistration.gatling.HttpProtocol.httpConf
import uk.gov.hmrc.agentregistration.gatling.scenarios.SoleTraderScenario

class RampUpSimulation extends Simulation {

  setUp(
    SoleTraderScenario.soleTraderJourney
      .inject(
        rampUsers(100) during (5 minutes)
      )
      .throttle(
        jumpToRps(10),
        rampRps(10, 100) in (5 minutes)
      )
  ).protocols(httpConf)
    .assertions(
      global.responseTime.percentile3.lte(800),
      global.successfulRequests.percent.gte(99.5)
    )

}
```

```scala
// SustainedLoadSimulation.scala
package uk.gov.hmrc.agentregistration.gatling.simulations

import io.gatling.core.Predef._
import io.gatling.core.scenario.Simulation
import scala.concurrent.duration._
import uk.gov.hmrc.agentregistration.gatling.HttpProtocol.httpConf
import uk.gov.hmrc.agentregistration.gatling.scenarios._

class SustainedLoadSimulation extends Simulation {

  setUp(
    SoleTraderScenario.soleTraderJourney
      .inject(constantUsersPerSec(40) during (15 minutes)),
    LimitedCompanyScenario.limitedCompanyJourney
      .inject(constantUsersPerSec(20) during (15 minutes)),
    PartnershipScenario.partnershipJourney
      .inject(constantUsersPerSec(30) during (15 minutes)),
    IndividualDetailsScenario.individualJourney
      .inject(constantUsersPerSec(10) during (15 minutes))
  ).protocols(httpConf)
    .assertions(
      global.responseTime.percentile3.lte(1000),
      global.responseTime.percentile99.lte(3000),
      global.successfulRequests.percent.gte(99.5),
      forAll.failedRequests.percent.lte(0.5)
    )

}
```

---

## Running the Tests

### Maven (if using Maven)
```bash
mvn gatling:execute -Dgatling.simulationClass=uk.gov.hmrc.agentregistration.gatling.simulations.RampUpSimulation
```

### SBT (if using SBT)
```bash
sbt "Gatling / testOnly uk.gov.hmrc.agentregistration.gatling.simulations.RampUpSimulation"
```

### Docker
```bash
docker run -v ./results:/results gatling/gatling:latest \
  -s uk.gov.hmrc.agentregistration.gatling.simulations.RampUpSimulation
```

---

## Key Points for Implementation

1. **Always extract dynamic IDs** from responses for correlation
2. **Use feeders** for data variation across users
3. **Add realistic think time** (pauses) between requests
4. **Group related requests** into reusable chains
5. **Parameterize scenarios** for different business types
6. **Use assertions** to define success criteria
7. **Monitor resource usage** (memory, connections) during tests
8. **Save results** for analysis and trending


