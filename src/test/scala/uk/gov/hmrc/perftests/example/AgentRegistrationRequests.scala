/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.perftests.example

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.http.request.builder.HttpRequestBuilder
import uk.gov.hmrc.performance.conf.ServicesConfiguration

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

object AgentRegistrationRequests extends ServicesConfiguration {

  val baseUrl: String    = baseUrlFor("agent-registration")
  val stubsUrl: String   = baseUrlFor("agents-external-stubs")
  val route: String      = "/agent-registration/apply"

  private val emailVerificationBaseUrl = "http://localhost:9890"

  private def normalizeLocation(location: String): String = {
    if (location.startsWith("http")) location
    else if (location.startsWith("/email-verification/")) s"$emailVerificationBaseUrl$location"
    else s"$baseUrl$location"
  }

  val getAgentTypePage: HttpRequestBuilder =
    http("Get Agent Type Page")
      .get(s"$baseUrl$route/about-your-business/agent-type")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postAgentTypeYes: HttpRequestBuilder =
    http("Post Agent Type - Yes")
      .post(s"$baseUrl$route/about-your-business/agent-type")
      .formParam("agentType", "UkTaxAgent")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))
      .check(header("Location").is("/agent-registration/apply/about-your-business/business-type"))

  val getBusinessTypePage: HttpRequestBuilder =
    http("Get Business Type Page")
      .get(s"$baseUrl$route/about-your-business/business-type")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postBusinessTypeSoleTrader: HttpRequestBuilder =
    http("Post Business Type - Sole Trader")
      .post(s"$baseUrl$route/about-your-business/business-type")
      .formParam("businessType", "SoleTrader")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))
      .check(header("Location").is("/agent-registration/apply/about-your-business/user-role"))

  val getBusinessOwnerPage: HttpRequestBuilder =
    http("Get Business Owner Page")
      .get(s"$baseUrl$route/about-your-business/user-role")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postBusinessOwnerTrue: HttpRequestBuilder =
    http("Post Business Owner - True")
      .post(s"$baseUrl$route/about-your-business/user-role")
      .formParam("userRole", "Owner")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))
      .check(header("Location").is("/agent-registration/apply/about-your-business/agent-online-services-account"))

  val getSignInPage: HttpRequestBuilder =
    http("Get Sign In Page")
      .get(s"$baseUrl$route/about-your-business/agent-online-services-account")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postSignIn: HttpRequestBuilder =
    http("Post Sign In")
      .post(s"$baseUrl$route/about-your-business/agent-online-services-account")
      .formParam("typeOfSignIn", "HmrcOnlineServices")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))
      .check(header("Location").is("/agent-registration/apply/about-your-business/sign-in"))

  val getSignInInfoPage: HttpRequestBuilder =
    http("Get Sign In Info Page")
      .get(s"$baseUrl$route/about-your-business/sign-in")
      .check(status.is(200))

  val getContinueToSignIn: HttpRequestBuilder =
    http("Continue To Sign In")
      .get(s"$baseUrl$route/internal/initiate-agent-application/uk-tax-agent/sole-trader/owner")
      .check(status.is(303))

  val getStubsSignInPage: HttpRequestBuilder =
    http("Get Stubs Sign In Page")
      .get(s"$stubsUrl/bas-gateway/sign-in?continue_url=$baseUrl$route/internal/initiate-agent-application/uk-tax-agent/sole-trader/owner&origin=agent-registration-frontend&affinityGroup=agent")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))
      .check(
        bodyString.transform((_) => s"perf-${UUID.randomUUID().toString.take(8)}").saveAs("userId")
      )
      .check(
        bodyString.transform((_) => s"perf-${UUID.randomUUID().toString.take(8)}").saveAs("planetId")
      )

  val postStubsSignIn: HttpRequestBuilder =
    http("Post Stubs Sign In")
      .post(s"$stubsUrl/gg/sign-in?continue=$baseUrl$route/internal/initiate-agent-application/uk-tax-agent/sole-trader/owner&origin=agent-registration-frontend")
      .formParam("userId", "#{userId}")
      .formParam("planetId", "#{planetId}")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))

  val getStubsUserCreatePage: HttpRequestBuilder =
    http("Get Stubs User Create Page")
      .get(s"$stubsUrl/agents-external-stubs/user/create?continue=$baseUrl$route/internal/initiate-agent-application/uk-tax-agent/sole-trader/owner")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))
      .check(
        regex("""action=\"([^\"]+)\" id=\"initialUserDataForm\"""")
          .transform(_.replace("&amp;", "&"))
          .saveAs("stubsUserCreateAction")
      )

  val postStubsUserCreatePage: HttpRequestBuilder =
    http("Post Stubs User Create Page")
      .post(s"$stubsUrl#{stubsUserCreateAction}")
      .formParam("affinityGroup", "Agent")
      .formParam("principalEnrolmentService", "none")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))
      .check(headerRegex("Location", "/agents-external-stubs/user/edit\\?continue=.*"))

  val getStubsUserEditPage: HttpRequestBuilder =
    http("Get Stubs User Edit Page")
      .get(s"$stubsUrl/agents-external-stubs/user/edit?continue=$baseUrl$route/internal/initiate-agent-application/uk-tax-agent/sole-trader/owner")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))
      .check(
        regex("""action=\"([^\"]+)\"""")
          .transform(_.replace("&amp;", "&"))
          .saveAs("stubsUserEditAction")
      )
      .check(css("input[name=groupId]", "value").saveAs("groupId"))
      .check(css("input[name='address.line1']", "value").optional.saveAs("addressLine1"))
      .check(css("input[name='address.line2']", "value").optional.saveAs("addressLine2"))
      .check(css("input[name='address.postcode']", "value").optional.saveAs("addressPostcode"))
      .check(
        regex("""<option value="([^"]+)"[^>]*selected""")
          .optional
          .saveAs("addressCountry")
      )

  val postStubsUserEditPage: HttpRequestBuilder =
    http("Post Stubs User Edit Page")
      .post(s"$stubsUrl#{stubsUserEditAction}")
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("groupId", "#{groupId}")
      .formParam("credentialRole", "User")
      .formParam("credentialStrength", "none")
      .formParam("address.line1", "#{addressLine1}")
      .formParam("address.line2", "#{addressLine2}")
      .formParam("address.postcode", "#{addressPostcode}")
      .formParam("address.countryCode", "#{addressCountry}")
      .check(status.is(303))
      .check(
        headerRegex("Location", ".*/agent-registration/apply/internal/initiate-agent-application/.*")
          .saveAs("initiateAgentApplicationUrl")
      )

  val getInitiateAgentApplicationPage: HttpRequestBuilder =
    http("Get Initiate Agent Application Page")
      .get("#{initiateAgentApplicationUrl}")
      .check(status.is(303))
      .check(
        header("Location")
          .transform(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
          .saveAs("grsTestDataUrl")
      )

  val getGrsTestDataPage: HttpRequestBuilder =
    http("Get GRS Test Data Page")
      .get("#{grsTestDataUrl}")
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("grsRedirectUrl"))
      .check(css("input[name=csrfToken]", "value").optional.saveAs("csrfToken"))

  val getGrsFormIfNeeded: HttpRequestBuilder =
    http("Get GRS Form If Needed")
      .get(session => {
        val url = session("grsRedirectUrl").asOption[String]
          .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
          .orElse(session("grsTestDataUrl").asOption[String])
          .getOrElse("#{grsTestDataUrl}")
        url
      })
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))
      .check(substring("Save Test Data"))
      .check(
        regex("""<form[^>]*action="([^"]+)""")
          .transform(_.replace("&amp;", "&"))
          .saveAs("grsFormAction")
      )

  val postGrsTestDataPage: HttpRequestBuilder =
    http("Post GRS Test Data Page")
      .post(session => {
        val action = session("grsFormAction").asOption[String]
          .map(a => if (a.startsWith("http")) a else s"$baseUrl$a")
          .getOrElse(session("grsTestDataUrl").as[String])
        action
      })
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("registrationStatus", "GrsRegistered")
      .formParam("safeId", "XA0001234512345")
      .formParam("sautr", "1000047685")
      .formParam("firstName", "Test")
      .formParam("lastName", "User")
      .formParam("dateOfBirth", "2006-04-24")
      .formParam("nino", "OA189787A")
      .check(status.is(303))
      .check(header("Location").saveAs("taskListUrl"))

  val getTaskListPage: HttpRequestBuilder =
    http("Get Task List Page")
      .get("#{taskListUrl}")
      .check(status.is(200))

  val getApplicantNamePage: HttpRequestBuilder =
    http("Get Applicant Name Page")
      .get(s"$baseUrl$route/applicant/applicant-name")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postApplicantName: HttpRequestBuilder =
    http("Post Applicant Name")
      .post(s"$baseUrl$route/applicant/applicant-name")
      .formParam("authorisedName", "Test User")
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("submit", "SaveAndContinue")
      .check(status.is(303))
      .check(header("Location").saveAs("nextPageUrl"))

  val getTelephoneNumberPage: HttpRequestBuilder =
    http("Get Telephone Number Page")
      .get(s"$baseUrl$route/applicant/telephone-number")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postTelephoneNumber: HttpRequestBuilder =
    http("Post Telephone Number")
      .post(s"$baseUrl$route/applicant/telephone-number")
      .formParam("telephoneNumber", "07777777777")
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("submit", "SaveAndContinue")
      .check(status.is(303))

  val getEmailAddressPage: HttpRequestBuilder =
    http("Get Email Address Page")
      .get(s"$baseUrl$route/applicant/email-address")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postEmailAddress: HttpRequestBuilder =
    http("Post Email Address")
      .post(s"$baseUrl$route/applicant/email-address")
      .formParam("emailAddress", "test@test.com")
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("submit", "SaveAndContinue")
      .check(status.is(303))
      .check(
        header("Location")
          .transform(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
          .saveAs("verifyEmailPageUrl")
      )

  val getVerifyEmailPage: HttpRequestBuilder =
    http("Get Verify Email Page")
      .get("#{verifyEmailPageUrl}")
      .check(status.in(200, 303))
      .check(
        header("Location")
          .optional
          .saveAs("emailVerificationLinkFromRedirect")
      )
      .check(
        regex("""(https?://[^\"']+/email-verification/journey/[^\"']+/passcode\?[^\"'\s<]+)""")
          .optional
          .saveAs("emailVerificationLinkFromBody")
      )

  val getEmailVerificationPasscodesPage: HttpRequestBuilder =
    http("Get Email Verification Passcodes Page")
      .get(session => {
        val emailVerificationLink = session("emailVerificationLinkFromRedirect").asOption[String]
          .map(normalizeLocation)
          .orElse(session("emailVerificationLinkFromBody").asOption[String].map(_.replace("&amp;", "&")))
          .getOrElse(session("verifyEmailPageUrl").as[String])
        val encodedLink           = URLEncoder.encode(emailVerificationLink, StandardCharsets.UTF_8.name())
        s"$baseUrl/agent-registration/test-only/email-verification-pass-codes?emailVerificationLink=$encodedLink"
      })
      .check(status.is(200))
      .check(
        bodyString.transform { body =>
          val passcodePatterns = Seq(
            """(?i)pass\s*code[^0-9]*([0-9]{6})""".r,
            """\b([0-9]{6})\b""".r
          )
          passcodePatterns
            .view
            .flatMap(_.findFirstMatchIn(body).map(_.group(1)))
            .headOption
            .getOrElse("")
        }.saveAs("emailVerificationCode")
      )

  val getEmailVerificationEntryPage: HttpRequestBuilder =
    http("Get Email Verification Entry Page")
      .get(session => {
        val redirect = session.attributes.get("emailVerificationLinkFromRedirect").collect { case s: String => s }
        val bodyLink = session.attributes.get("emailVerificationLinkFromBody").collect { case s: String => s }
          .map(_.replace("&amp;", "&"))
        val fallback = session.attributes.get("verifyEmailPageUrl").collect { case s: String => s }.getOrElse("")
        val target   = redirect
          .map(normalizeLocation)
          .orElse(bodyLink)
          .getOrElse(fallback)
        io.gatling.commons.validation.Success(target)
      })
      .check(status.is(200))
      .check(substring("Confirmation code"))
      .check(
        regex("""<input[^>]*name=\"csrfToken\"[^>]*value=\"([^\"]+)\"""")
          .saveAs("csrfToken")
      )
      .check(
        regex("""<form[^>]*action=\"([^\"]*?/email-verification/journey/[^\"]*?/passcode[^\"]*)\"""")
          .transform(_.replace("&amp;", "&"))
          .saveAs("emailVerificationFormAction")
      )

  val postEmailVerificationCode: HttpRequestBuilder =
    http("Post Email Verification Code")
      .post(session => {
        val fallback = session.attributes.get("verifyEmailPageUrl").collect { case s: String => s }.getOrElse("")
        val action   = session.attributes.get("emailVerificationFormAction").collect { case s: String => s }
        val redirect = session.attributes.get("emailVerificationLinkFromRedirect").collect { case s: String => s }
        val bodyLink = session.attributes.get("emailVerificationLinkFromBody").collect { case s: String => s }
          .map(_.replace("&amp;", "&"))
        val target   = action
          .map(normalizeLocation)
          .orElse(redirect.map(normalizeLocation))
          .orElse(bodyLink)
          .getOrElse(fallback)
        io.gatling.commons.validation.Success(target)
      })
      .formParam("passcode", "#{emailVerificationCode}")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))

}


