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

package uk.gov.hmrc.perftests.mmtar

import io.gatling.core.Predef._
import io.gatling.core.session.Session
import io.gatling.http.Predef._
import io.gatling.http.request.builder.HttpRequestBuilder
import uk.gov.hmrc.performance.conf.ServicesConfiguration

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.util.Try

object AgentRegistrationRequests extends ServicesConfiguration with AgentRegistrationHelpers {

  val baseUrl: String  = baseUrlFor("agent-registration")
  val stubsUrl: String = baseUrlFor("agents-external-stubs")
  val route: String    = "/agent-registration/apply"

  private val emailVerificationBaseUrl =
    Try(baseUrlFor("email-verification")).getOrElse("http://localhost:9890")

  private def debugUrl(label: String, fullUrl: String): io.gatling.commons.validation.Validation[String] = {
    debug(s"[DEBUG] $label = [$fullUrl]")
    io.gatling.commons.validation.Success(fullUrl)
  }

  private def frontendUrl(location: String): String =
    if (location.startsWith("http")) location else s"$baseUrl$location"

  private def stubsOrFrontendUrl(location: String): String =
    if (location.startsWith("http")) location
    else if (location.startsWith("/bas-gateway/")) s"$stubsUrl$location"
    else s"$baseUrl$location"

  private def maybeNormalizeLocation(location: String): String =
    Option(location)
      .map(_.replace("&amp;", "&").trim)
      .filter(_.nonEmpty)
      .map(normalizeLocation)
      .orNull

  private def maybeExtractEmailVerificationLink(location: String): String =
    Option(location).map(_.replace("&amp;", "&").trim).filter(_.nonEmpty).flatMap { value =>
      val key = "emailVerificationLink="

      if (value.contains("/agent-registration/test-only/email-verification-pass-codes") && value.contains(key)) {
        val encoded = value.substring(value.indexOf(key) + key.length)
        Try(URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())).toOption
      } else {
        Some(value)
      }
    }.map(maybeNormalizeLocation).orNull

  private def passcodesPageUrlFromSession(session: Session): Option[String] =
    session.attributes.get("verifyEmailPageUrl").collect {
      case s: String if s.contains("/agent-registration/test-only/email-verification-pass-codes") =>
        maybeNormalizeLocation(s)
    }

  private def emailVerificationUrlFromSession(session: Session) = {
    val redirect = session.attributes.get("emailVerificationLinkFromRedirect").collect { case s: String => s }
    val bodyLink = session.attributes.get("emailVerificationLinkFromBody").collect { case s: String => s }
    val fallback = session.attributes.get("verifyEmailPageUrl").collect {
      case s: String if s.contains("/email-verification/") => s
    }

    redirect
      .orElse(bodyLink)
      .orElse(fallback)
      .map(maybeExtractEmailVerificationLink)
      .filter(url => url != null && url.contains("/email-verification/journey/") && url.contains("/passcode"))
      .map(io.gatling.commons.validation.Success(_))
      .getOrElse(io.gatling.commons.validation.Failure("Email verification passcode URL not found in session"))
  }

  private def normalizeEmailVerificationAction(action: String, session: Session): String = {
    val cleaned = Option(action).map(_.replace("&amp;", "&").trim).getOrElse("")

    if (cleaned.startsWith("http")) {
      cleaned
    } else if (cleaned.startsWith("/email-verification/")) {
      val emailVerificationOrigin = emailVerificationUrlFromSession(session) match {
        case io.gatling.commons.validation.Success(url: String) => originOf(url)
        case _                                                  => None
      }

      s"${emailVerificationOrigin.getOrElse(emailVerificationBaseUrl)}$cleaned"
    } else if (cleaned.startsWith("/")) {
      s"$baseUrl$cleaned"
    } else {
      cleaned
    }
  }

  private def detectAgentDetailsLandingPage(body: String): String = {
    val normalized = Option(body).getOrElse("").toLowerCase

    if (normalized.contains("/agent-registration/apply/agent-details/business-name") && normalized.contains("name=\"businessname\""))
      "agent-details-business-name"
    else if (normalized.contains("/agent-registration/apply/agent-details/check-your-answers") && normalized.contains("check your answers"))
      "agent-details-check-your-answers"
    else if (normalized.contains("/agent-registration/apply/about-your-business/agent-type") || normalized.contains("is your agent business based in the uk"))
      "about-your-business-agent-type"
    else
      "unknown"
  }

  private val agentDetailsTaskLinkRegex =
    """<a[^>]*href="([^"]*/agent-registration/apply/agent-details/[^"]*)"[^>]*>""".r

  private val applicantDetailsTaskLinkRegex =
    """<a[^>]*href=['"]([^'"]*(?:/agent-registration)?/apply/applicant(?:/[^'"]*)?)['"][^>]*>""".r

  private val listDetailsTaskLinkRegex =
    """<a[^>]*href=['"]([^'"]*(?:/agent-registration)?/apply/list-details/sole-trader[^'"]*)['"][^>]*>""".r

  private val listDetailsContinueLinkRegex =
    """<a[^>]*href=['"]([^'"]*/agent-registration/sign-out-with-continue[^'"]*)['"][^>]*>""".r

  private def extractAgentDetailsTaskLink(body: String): String =
    agentDetailsTaskLinkRegex.findFirstMatchIn(Option(body).getOrElse(""))
      .map(_.group(1).trim)
      .getOrElse("")

  private def extractApplicantDetailsTaskLink(body: String): String =
    applicantDetailsTaskLinkRegex.findFirstMatchIn(Option(body).getOrElse(""))
      .map(_.group(1).trim)
      .getOrElse("")

  private def extractListDetailsTaskLink(body: String): String =
    listDetailsTaskLinkRegex.findFirstMatchIn(Option(body).getOrElse(""))
      .map(_.group(1).trim)
      .getOrElse("")

  private def extractListDetailsContinueLink(body: String): String =
    listDetailsContinueLinkRegex.findFirstMatchIn(Option(body).getOrElse(""))
      .map(_.group(1).replace("&amp;", "&").trim)
      .getOrElse("")

  private val agentsExternalStubsBaseUrl: String =
    if (stubsUrl.contains("localhost")) stubsUrl
    else s"$stubsUrl/agents-external-stubs"

  private def normalizeAgentsExternalStubsAction(action: String): String = {
    val cleaned = action.replace("&amp;", "&").trim

    if (cleaned.startsWith("http")) cleaned
    else if (cleaned.startsWith("/agents-external-stubs/")) s"$stubsUrl$cleaned"
    else if (cleaned.startsWith("/")) s"$agentsExternalStubsUserBaseUrl$cleaned"
    else s"$agentsExternalStubsUserBaseUrl/$cleaned"
  }

  private val agentsExternalStubsUserBaseUrl: String =
    if (stubsUrl.endsWith("/agents-external-stubs")) stubsUrl
    else s"$stubsUrl/agents-external-stubs"

  // --------------------------------------------------
  // Initial application setup
  // --------------------------------------------------

  val getAgentTypePage: HttpRequestBuilder =
    http("Get Agent Type Page")
      .get(_ => debugUrl("Get Agent Type Page URL", s"$baseUrl$route/about-your-business/agent-type"))
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postAgentTypeYes: HttpRequestBuilder =
    http("Post Agent Type - Yes")
      .post(_ => debugUrl("Post Agent Type - Yes URL", s"$baseUrl$route/about-your-business/agent-type"))
      .formParam("agentType", "UkTaxAgent")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))
      .check(header("Location").is("/agent-registration/apply/about-your-business/business-type"))

  val getBusinessTypePage: HttpRequestBuilder =
    http("Get Business Type Page")
      .get(_ => debugUrl("Get Business Type Page URL", s"$baseUrl$route/about-your-business/business-type"))
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postBusinessTypeSoleTrader: HttpRequestBuilder =
    http("Post Business Type - Sole Trader")
      .post(_ => debugUrl("Post Business Type - Sole Trader URL", s"$baseUrl$route/about-your-business/business-type"))
      .formParam("businessType", "SoleTrader")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))
      .check(header("Location").is("/agent-registration/apply/about-your-business/user-role"))

  val getBusinessOwnerPage: HttpRequestBuilder =
    http("Get Business Owner Page")
      .get(_ => debugUrl("Get Business Owner Page URL", s"$baseUrl$route/about-your-business/user-role"))
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postBusinessOwnerTrue: HttpRequestBuilder =
    http("Post Business Owner - True")
      .post(_ => debugUrl("Post Business Owner - True URL", s"$baseUrl$route/about-your-business/user-role"))
      .formParam("userRole", "Owner")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))
      .check(header("Location").is("/agent-registration/apply/about-your-business/agent-online-services-account"))

  val getSignInPage: HttpRequestBuilder =
    http("Get Sign In Page")
      .get(_ => debugUrl("Get Sign In Page URL", s"$baseUrl$route/about-your-business/agent-online-services-account"))
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postSignIn: HttpRequestBuilder =
    http("Post Sign In")
      .post(_ => debugUrl("Post Sign In URL", s"$baseUrl$route/about-your-business/agent-online-services-account"))
      .formParam("typeOfSignIn", "HmrcOnlineServices")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))
      .check(header("Location").is("/agent-registration/apply/about-your-business/sign-in"))

  val getSignInInfoPage: HttpRequestBuilder =
    http("Get Sign In Info Page")
      .get(_ => debugUrl("Get Sign In Info Page URL", s"$baseUrl$route/about-your-business/sign-in"))
      .check(status.is(200))

  val getContinueToSignIn: HttpRequestBuilder =
    http("Continue To Sign In")
      .get(_ => debugUrl("Continue To Sign In URL", s"$baseUrl$route/internal/initiate-agent-application/uk-tax-agent/sole-trader/owner"))
      .check(status.is(303))

  val getStubsSignInPage: HttpRequestBuilder =
    http("Get Stubs Sign In Page")
      .get(_ =>
        debugUrl(
          "Get Stubs Sign In Page URL",
          s"$agentsExternalStubsBaseUrl/bas-gateway/sign-in?continue_url=$baseUrl$route/internal/initiate-agent-application/uk-tax-agent/sole-trader/owner&origin=agent-registration-frontend&affinityGroup=agent"
        )
      )
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))
      .check(
        bodyString.transform(_ => s"perf-${UUID.randomUUID().toString.take(8)}").saveAs("userId")
      )
      .check(
        bodyString.transform(_ => s"perf-${UUID.randomUUID().toString.take(8)}").saveAs("planetId")
      )

  val postStubsSignIn: HttpRequestBuilder =
    http("Post Stubs Sign In")
      .post(_ =>
        debugUrl(
          "Post Stubs Sign In URL",
          s"$agentsExternalStubsBaseUrl/gg/sign-in?continue=$baseUrl$route/internal/initiate-agent-application/uk-tax-agent/sole-trader/owner&origin=agent-registration-frontend"
        )
      )
      .formParam("userId", "#{userId}")
      .formParam("planetId", "#{planetId}")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))

  val getStubsUserCreatePage: HttpRequestBuilder =
    http("Get Stubs User Create Page")
      .get(_ =>
        debugUrl(
          "Get Stubs User Create Page URL",
          s"$agentsExternalStubsUserBaseUrl/user/create?continue=$baseUrl$route/internal/initiate-agent-application/uk-tax-agent/sole-trader/owner"
        )
      )
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))
      .check(
        regex("""action=\"([^\"]+)\" id=\"initialUserDataForm\"""")
          .transform(_.replace("&amp;", "&"))
          .saveAs("stubsUserCreateAction")
      )

  val postStubsUserCreatePage: HttpRequestBuilder =
    http("Post Stubs User Create Page")
      .post(session => {
        val action  = session("stubsUserCreateAction").as[String]
        val fullUrl = normalizeAgentsExternalStubsAction(action)

        debugUrl("Post Stubs User Create Page URL", fullUrl)
      })
      .formParam("affinityGroup", "Agent")
      .formParam("principalEnrolmentService", "none")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))
      .check(headerRegex("Location", "/agents-external-stubs/user/edit\\?continue=.*"))

  val getStubsUserEditPage: HttpRequestBuilder =
    http("Get Stubs User Edit Page")
      .get(_ =>
        debugUrl(
          "Get Stubs User Edit Page URL",
          s"$agentsExternalStubsUserBaseUrl/user/edit?continue=$baseUrl$route/internal/initiate-agent-application/uk-tax-agent/sole-trader/owner"
        )
      )
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
      .post(session => {
        val action  = session("stubsUserEditAction").as[String]
        val fullUrl = normalizeAgentsExternalStubsAction(action)

        debugUrl("Post Stubs User Edit Page URL", fullUrl)
      })
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
      .get(session => debugUrl("Get Initiate Agent Application Page URL", session("initiateAgentApplicationUrl").as[String]))
      .check(status.is(303))
      .check(
        header("Location")
          .transform { loc =>
            val fullLocation = frontendUrl(loc)
            debug(s"[DEBUG] Location after initiate agent application = [$fullLocation]")
            fullLocation
          }
          .saveAs("grsTestDataUrl")
      )

  val getGrsTestDataPage: HttpRequestBuilder =
    http("Get GRS Test Data Page")
      .get(session => debugUrl("Get GRS Test Data Page URL", session("grsTestDataUrl").as[String]))
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("grsRedirectUrl"))
      .check(css("input[name=csrfToken]", "value").optional.saveAs("csrfToken"))

  val getGrsFormIfNeeded: HttpRequestBuilder =
    http("Get GRS Form If Needed")
      .get(session => {
        val url = session("grsRedirectUrl").asOption[String]
          .map(frontendUrl)
          .orElse(session("grsTestDataUrl").asOption[String])
          .getOrElse(session("grsTestDataUrl").as[String])
        debugUrl("Get GRS Form If Needed URL", url)
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
        debugUrl("Post GRS Test Data Page URL", action)
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
      .get(session => {
        val url     = session("taskListUrl").as[String]
        val fullUrl = frontendUrl(url)
        debugUrl("Get Task List Page URL", fullUrl)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("taskListInitialRedirectUrl"))
      .check(
        bodyString.transform { body =>
          try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/debug"))
            val content = if (body.nonEmpty) body else "EMPTY_BODY"
            java.nio.file.Files.write(java.nio.file.Paths.get("target/debug/task-list-initial.html"), content.getBytes("UTF-8"))
          } catch { case _: Exception => }
          body
        }.exists
      )
      .check(bodyString.transform(extractApplicantDetailsTaskLink).saveAs("applicantDetailsEntryUrl"))
      .check(bodyString.transform(extractAgentDetailsTaskLink).optional.saveAs("agentDetailsEntryUrl"))

  val enterApplicantDetailsFromTaskList: HttpRequestBuilder =
    http("Enter Applicant Details From Task List")
      .get(session => {
        val fullUrl = session("applicantDetailsEntryUrl").asOption[String]
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/task-list")
        debugUrl("Enter Applicant Details From Task List URL", fullUrl)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("applicantDetailsRedirectUrl"))
      .check(
        bodyString.transform { body =>
          try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/debug"))
            val content = if (body.nonEmpty) body else "EMPTY_BODY"
            java.nio.file.Files.write(java.nio.file.Paths.get("target/debug/task-list-before-applicant-entry.html"), content.getBytes("UTF-8"))
            java.nio.file.Files.write(java.nio.file.Paths.get("target/debug/task-list-initial.html"), content.getBytes("UTF-8"))
          } catch { case _: Exception => }
          body
        }.exists
      )
      .check(bodyString.transform(extractApplicantDetailsTaskLink).optional.saveAs("applicantDetailsEntryUrl"))

  val followApplicantDetailsInitialRedirect: HttpRequestBuilder =
    http("Follow Applicant Details Initial Redirect")
      .get(session => {
        session("applicantDetailsRedirectUrl").asOption[String]
          .map(frontendUrl)
          .orElse(
            session("applicantDetailsEntryUrl").asOption[String]
              .map(_.trim)
              .filter(_.nonEmpty)
              .map(frontendUrl)
          )
          .map(url => debugUrl("Follow Applicant Details Initial Redirect URL", url))
          .getOrElse(io.gatling.commons.validation.Failure("Applicant details entry URL missing from task-list response"))
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("applicantDetailsRedirectUrl2"))
      .check(css("input[name=csrfToken]", "value").optional.saveAs("csrfToken"))

  val followApplicantDetailsRedirectIfNeeded: HttpRequestBuilder =
    http("Follow Applicant Details Redirect If Needed")
      .get(session => {
        val url = session("applicantDetailsRedirectUrl2").asOption[String]
          .orElse(session("applicantDetailsRedirectUrl").asOption[String])
          .orElse(session("applicantDetailsEntryUrl").asOption[String])
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/applicant/applicant-name")
        debugUrl("Follow Applicant Details Redirect If Needed URL", url)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("applicantDetailsRedirectUrl3"))
      .check(css("input[name=csrfToken]", "value").optional.saveAs("csrfToken"))

  val getApplicantNamePage: HttpRequestBuilder =
    http("Get Applicant Name Page")
      .get(_ => debugUrl("Get Applicant Name Page URL", s"$baseUrl$route/applicant/applicant-name"))
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postApplicantName: HttpRequestBuilder =
    http("Post Applicant Name")
      .post(_ => debugUrl("Post Applicant Name URL", s"$baseUrl$route/applicant/applicant-name"))
      .formParam("authorisedName", "Test User")
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("submit", "SaveAndContinue")
      .check(status.is(303))
      .check(header("Location").saveAs("nextPageUrl"))

  val getTelephoneNumberPage: HttpRequestBuilder =
    http("Get Telephone Number Page")
      .get(_ => debugUrl("Get Telephone Number Page URL", s"$baseUrl$route/applicant/telephone-number"))
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postTelephoneNumber: HttpRequestBuilder =
    http("Post Telephone Number")
      .post(_ => debugUrl("Post Telephone Number URL", s"$baseUrl$route/applicant/telephone-number"))
      .formParam("telephoneNumber", "07777777777")
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("submit", "SaveAndContinue")
      .check(status.is(303))

  val getEmailAddressPage: HttpRequestBuilder =
    http("Get Email Address Page")
      .get(_ => debugUrl("Get Email Address Page URL", s"$baseUrl$route/applicant/email-address"))
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postEmailAddress: HttpRequestBuilder =
    http("Post Email Address")
      .post(_ => debugUrl("Post Email Address URL", s"$baseUrl$route/applicant/email-address"))
      .formParam("emailAddress", "test@test.com")
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("submit", "SaveAndContinue")
      .check(status.is(303))
      .check(
        header("Location")
          .transform { loc =>
            val fullLocation = frontendUrl(loc)
            debug(s"[DEBUG] Location after Post Email Address = [$fullLocation]")
            fullLocation
          }
          .saveAs("verifyEmailPageUrl")
      )

  val getVerifyEmailPage: HttpRequestBuilder =
    http("Get Verify Email Page")
      .get(session => debugUrl("Get Verify Email Page URL", session("verifyEmailPageUrl").as[String]))
      .check(status.in(200, 303))
      .check(
        header("Location")
          .optional
          .saveAs("emailVerificationLinkFromRedirect")
      )
      .check(
        regex("""((?:https?://[^\"']+)?/email-verification/journey/[^\"']+/passcode\?[^\"'\s<]+)""")
          .optional
          .saveAs("emailVerificationLinkFromBody")
      )

  val getEmailVerificationPasscodesPage: HttpRequestBuilder =
    http("Get Email Verification Passcodes Page")
      .get(session => {
        val validation = passcodesPageUrlFromSession(session)
          .map(io.gatling.commons.validation.Success(_))
          .getOrElse {
            emailVerificationUrlFromSession(session).map { emailVerificationLink =>
              val encodedLink = URLEncoder.encode(emailVerificationLink, StandardCharsets.UTF_8.name())
              s"$baseUrl/agent-registration/test-only/email-verification-pass-codes?emailVerificationLink=$encodedLink"
            }
          }

        validation.flatMap(url => debugUrl("Get Email Verification Passcodes Page URL", url))
      })
      .check(status.is(200))
      .check(
        bodyString.transform { body =>
          val passcodePatterns = Seq(
            """id="email-verification-passcode"[^>]*>([A-Z0-9]{6})<""".r,
            """data-clip="([A-Z0-9]{6})""".r,
            """Copy passcode `([A-Z0-9]{6})`""".r,
            """Passcode `([A-Z0-9]{6})` copied""".r
          )
          passcodePatterns
            .view
            .flatMap(_.findFirstMatchIn(body).map(_.group(1)))
            .map(_.trim.toUpperCase)
            .headOption
            .getOrElse("")
        }.saveAs("emailVerificationCode")
      )

  val getEmailVerificationEntryPage: HttpRequestBuilder =
    http("Get Email Verification Entry Page")
      .get(session => emailVerificationUrlFromSession(session).flatMap(url => debugUrl("Get Email Verification Entry Page URL", url)))
      .check(status.is(200))
      .check(regex("""<input[^>]*name=\"passcode\"""").exists)
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
        val action = session.attributes.get("emailVerificationFormAction").collect { case s: String => s }
        val validation = action
          .map(a => normalizeEmailVerificationAction(a, session))
          .filter(_.nonEmpty)
          .map(io.gatling.commons.validation.Success(_))
          .getOrElse(emailVerificationUrlFromSession(session))

        validation.flatMap(url => debugUrl("Post Email Verification Code URL", url))
      })
      .formParam("passcode", "#{emailVerificationCode}")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))
      .check(header("Location").saveAs("postEmailVerificationRedirectUrl"))

  val getEmailVerificationRedirectPage: HttpRequestBuilder =
    http("Get Email Verification Redirect Page")
      .get(session => {
        val location = session("postEmailVerificationRedirectUrl").as[String]
        val fullUrl  = frontendUrl(location)
        debugUrl("Get Email Verification Redirect Page URL", fullUrl)
      })
      .check(status.is(303))
      .check(header("Location").is("/agent-registration/apply/applicant/check-your-answers"))

  val getApplicantCheckYourAnswersPage: HttpRequestBuilder =
    http("Get Applicant Check Your Answers Page")
      .get(_ => debugUrl("Get Applicant Check Your Answers Page URL", s"$baseUrl$route/applicant/check-your-answers"))
      .check(status.is(200))
      .check(substring("Check your answers"))
      .check(regex("""href="/agent-registration/apply/task-list"""").exists)
      .check(css("input[name=csrfToken]", "value").optional.saveAs("csrfToken"))

  val goToTaskListFromApplicantCya: HttpRequestBuilder =
    http("Go To Task List From Applicant CYA")
      .get(_ => debugUrl("Go To Task List From Applicant CYA URL", s"$baseUrl$route/task-list"))
      .header("Referer", s"$baseUrl$route/applicant/check-your-answers")
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("taskListRedirect1"))
      .check(
        bodyString.transform { body =>
          try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/debug"))
            val content = if (body.nonEmpty) body else "EMPTY_BODY"
            java.nio.file.Files.write(java.nio.file.Paths.get("target/debug/task-list-after-applicant-cya-final.html"), content.getBytes("UTF-8"))
          } catch { case _: Exception => }
          body
        }.exists
      )
      .check(bodyString.transform(extractAgentDetailsTaskLink).optional.saveAs("agentDetailsEntryUrl"))

  val followTaskListRedirect1: HttpRequestBuilder =
    http("Follow Task List Redirect 1")
      .get(session => {
        val fullUrl = session("taskListRedirect1").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/task-list")
        debugUrl("Follow Task List Redirect 1 URL", fullUrl)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("taskListRedirect2"))

  val followTaskListRedirect2AndExtractAgentDetails: HttpRequestBuilder =
    http("Follow Task List Redirect 2 And Extract Agent Details")
      .get(session => {
        val fullUrl = session("taskListRedirect2").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/task-list")
        debugUrl("Follow Task List Redirect 2 And Extract Agent Details URL", fullUrl)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("taskListRedirect3"))
      .check(
        bodyString.transform { body =>
          try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/debug"))
            java.nio.file.Files.write(java.nio.file.Paths.get("target/debug/task-list-after-applicant-cya-final.html"), body.getBytes("UTF-8"))
          } catch { case _: Exception => }
          body
        }.exists
      )
      .check(bodyString.transform(extractAgentDetailsTaskLink).optional.saveAs("agentDetailsEntryUrl"))

  val followTaskListRedirect3AndExtractAgentDetails: HttpRequestBuilder =
    http("Follow Task List Redirect 3 And Extract Agent Details")
      .get(session => {
        val fullUrl = session("taskListRedirect3").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/task-list")
        debugUrl("Follow Task List Redirect 3 And Extract Agent Details URL", fullUrl)
      })
      .check(status.is(200))
      .check(
        bodyString.transform { body =>
          try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/debug"))
            java.nio.file.Files.write(java.nio.file.Paths.get("target/debug/task-list-after-applicant-cya-final.html"), body.getBytes("UTF-8"))
          } catch { case _: Exception => }
          body
        }.exists
      )
      .check(bodyString.transform(extractAgentDetailsTaskLink).saveAs("agentDetailsEntryUrl"))
      .check(regex("""href=['"][^'"]*(?:/agent-registration)?/apply/agent-details(?:/[^'"]*)?['"]""").exists)

  val enterAgentDetailsFromTaskList: HttpRequestBuilder =
    http("Enter Agent Details From Task List")
      .get(session =>
        session("agentDetailsEntryUrl").asOption[String]
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(frontendUrl)
          .map(url => debugUrl("Enter Agent Details From Task List URL", url))
          .getOrElse(io.gatling.commons.validation.Failure("Agent details entry URL missing from task-list response"))
      )
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentDetailsRedirectUrl"))

  val getAgentDetailsCheckYourAnswersPage: HttpRequestBuilder =
    http("Get Agent Details Check Your Answers Page")
      .get(session => {
        session("agentDetailsEntryUrl").asOption[String]
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(frontendUrl)
          .map(url => debugUrl("Get Agent Details Check Your Answers Page URL", url))
          .getOrElse(io.gatling.commons.validation.Failure("Agent details entry URL missing from task-list response"))
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentDetailsRedirectUrl"))
      .check(
        regex("""<link[^>]*rel="canonical"[^>]*href="([^"]+)"""")
          .optional
          .saveAs("agentDetailsCanonicalUrl")
      )
      .check(regex("""<title>([^<]+)</title>""").optional.saveAs("agentDetailsPageTitle"))
      .check(regex("""<h1[^>]*>(.*?)</h1>""").optional.saveAs("agentDetailsPageH1"))
      .check(
        bodyString.transform { body =>
          try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/debug"))
            java.nio.file.Files.write(java.nio.file.Paths.get("target/debug/agent-details-entry-response.html"), body.getBytes("UTF-8"))
          } catch { case _: Exception => }
          detectAgentDetailsLandingPage(body)
        }.saveAs("agentDetailsDetectedPage")
      )
      .check(
        regex("""href="([^"]*/agent-registration/apply/agent-details/business-name[^"]*)"""")
          .optional
          .saveAs("businessNameUrlFromAgentDetails")
      )
      .check(
        regex("""href="([^"]*/agent-registration/apply/agent-details/[^""]*)"""")
          .optional
          .saveAs("agentDetailsNextUrl")
      )

  val followAgentDetailsInitialRedirect: HttpRequestBuilder =
    http("Follow Agent Details Initial Redirect")
      .get(session => {
        val url = session("agentDetailsRedirectUrl").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/agent-details/check-your-answers")
        debugUrl("Follow Agent Details Initial Redirect URL", url)
      })
      .check(status.is(200))
      .check(
        bodyString.transform { body =>
          try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/debug"))
            java.nio.file.Files.write(java.nio.file.Paths.get("target/debug/agent-details-after-redirect.html"), body.getBytes("UTF-8"))
          } catch { case _: Exception => }
          body
        }.exists
      )
      .check(css("input[name=csrfToken]", "value").optional.saveAs("csrfToken"))

  val followAgentDetailsRedirectIfNeeded: HttpRequestBuilder =
    http("Follow Agent Details Redirect If Needed")
      .get(session => {
        val url = session("agentDetailsRedirectUrl").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/agent-details/business-name")
        debugUrl("Follow Agent Details Redirect If Needed URL", url)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentDetailsRedirectUrl2"))
      .check(css("input[name=csrfToken]", "value").optional.saveAs("csrfToken"))

  val followAgentDetailsRedirectIfNeeded2: HttpRequestBuilder =
    http("Follow Agent Details Redirect If Needed 2")
      .get(session => {
        val url = session("agentDetailsRedirectUrl2").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/agent-details/business-name")
        debugUrl("Follow Agent Details Redirect If Needed 2 URL", url)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentDetailsRedirectUrl3"))
      .check(css("input[name=csrfToken]", "value").optional.saveAs("csrfToken"))

  val getBusinessNamePage: HttpRequestBuilder =
    http("Get Business Name Page")
      .get(session => {
        val url = session("agentDetailsRedirectUrl").asOption[String]
          .orElse(session("agentDetailsRedirectUrl2").asOption[String])
          .orElse(session("agentDetailsRedirectUrl3").asOption[String])
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/agent-details/business-name")
        debugUrl("Get Business Name Page URL", url)
      })
      .check(status.is(200))
      .check(
        bodyString.transform { body =>
          try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/debug"))
            java.nio.file.Files.write(java.nio.file.Paths.get("target/debug/get-business-name.html"), body.getBytes("UTF-8"))
          } catch { case _: Exception => }
          body
        }.exists
      )
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postBusinessName: HttpRequestBuilder =
    http("Post Business Name")
      .post(_ => debugUrl("Post Business Name URL", s"$baseUrl$route/agent-details/business-name"))
      .formParam("agentBusinessName", "Test User")
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("submit", "SaveAndContinue")
      .check(status.is(303))
      .check(
        header("Location")
          .in(
            "/agent-registration/apply/agent-details/telephone-number",
            "/agent-registration/apply/agent-details/check-your-answers"
          )
          .saveAs("postBusinessNameRedirectUrl")
      )

  val getAgentTelephoneNumberPage: HttpRequestBuilder =
    http("Get Agent Telephone Number Page")
      .get(_ => debugUrl("Get Agent Telephone Number Page URL", s"$baseUrl$route/agent-details/telephone-number"))
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postAgentTelephoneNumber: HttpRequestBuilder =
    http("Post Agent Telephone Number")
      .post(_ => debugUrl("Post Agent Telephone Number URL", s"$baseUrl$route/agent-details/telephone-number"))
      .formParam("agentTelephoneNumber", "01234567890")
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("submit", "SaveAndContinue")
      .check(status.is(303))
      .check(header("Location")
        .in(
          "/agent-registration/apply/agent-details/email-address",
          "/agent-registration/apply/agent-details/check-your-answers"))

  val getAgentEmailPage: HttpRequestBuilder =
    http("Get Agent Email Page")
      .get(_ => debugUrl("Get Agent Email Page URL", s"$baseUrl$route/agent-details/email-address"))
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postAgentEmail: HttpRequestBuilder =
    http("Post Agent Email")
      .post(_ => debugUrl("Post Agent Email URL", s"$baseUrl$route/agent-details/email-address"))
      .formParam("agentEmailAddress", "test@test.com")
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("submit", "SaveAndContinue")
      .check(status.is(303))
      .check(
        header("Location")
          .in(
            "/agent-registration/apply/agent-details/verify-email-address",
            "/agent-registration/apply/agent-details/check-your-answers"
          )
          .saveAs("postAgentEmailRedirectUrl")
      )

  val getAgentCorrespondenceAddressPage: HttpRequestBuilder =
    http("Get Agent Correspondence Address Page")
      .get(_ => debugUrl("Get Agent Correspondence Address Page URL", s"$baseUrl$route/agent-details/correspondence-address"))
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postAgentCorrespondenceAddress: HttpRequestBuilder =
    http("Post Agent Correspondence Address")
      .post(_ => debugUrl("Post Agent Correspondence Address URL", s"$baseUrl$route/agent-details/correspondence-address"))
      .formParam("agentCorrespondenceAddress", "1 Test Street, Test Area, TE1 1ST, GB")
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("submit", "SaveAndContinue")
      .check(status.is(303))
      .check(
        header("Location")
          .in(
            "/agent-registration/apply/agent-details/check-your-answers",
            "/agent-registration/apply/task-list"
          )
          .saveAs("postAgentCorrespondenceAddressRedirectUrl")
      )

  val getAgentCheckYourAnswersPage: HttpRequestBuilder =
    http("Get Agent Check Your Answers Page")
      .get(_ => debugUrl("Get Agent Check Your Answers Page URL", s"$baseUrl$route/agent-details/check-your-answers"))
      .check(status.is(200))
      .check(substring("Check your answers"))
      .check(regex("""href="/agent-registration/apply/task-list"""").exists)

  val goToTaskListFromAgentCya: HttpRequestBuilder =
    http("Go To Task List From Agent CYA")
      .get(_ => debugUrl("Go To Task List From Agent CYA URL", s"$baseUrl$route/task-list"))
      .header("Referer", s"$baseUrl$route/agent-details/check-your-answers")
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentTaskListRedirect1"))
      .check(
        bodyString.transform { body =>
          try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/debug"))
            val content = if (body.nonEmpty) body else "EMPTY_BODY"
            java.nio.file.Files.write(java.nio.file.Paths.get("target/debug/task-list-after-agent-cya-final.html"), content.getBytes("UTF-8"))
          } catch { case _: Exception => }
          body
        }.exists
      )

  val followTaskListRedirectAfterAgentCya1: HttpRequestBuilder =
    http("Follow Task List Redirect After Agent CYA 1")
      .get(session => {
        val fullUrl = session("agentTaskListRedirect1").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/task-list")
        debugUrl("Follow Task List Redirect After Agent CYA 1 URL", fullUrl)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentTaskListRedirect2"))

  val followTaskListRedirectAfterAgentCya2: HttpRequestBuilder =
    http("Follow Task List Redirect After Agent CYA 2")
      .get(session => {
        val fullUrl = session("agentTaskListRedirect2").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/task-list")
        debugUrl("Follow Task List Redirect After Agent CYA 2 URL", fullUrl)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentTaskListRedirect3"))

  val followTaskListRedirectAfterAgentCya3: HttpRequestBuilder =
    http("Follow Task List Redirect After Agent CYA 3")
      .get(session => {
        val fullUrl = session("agentTaskListRedirect3").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/task-list")
        debugUrl("Follow Task List Redirect After Agent CYA 3 URL", fullUrl)
      })
      .check(status.is(200))
      .check(
        bodyString.transform { body =>
          try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/debug"))
            java.nio.file.Files.write(java.nio.file.Paths.get("target/debug/task-list-after-agent-cya-final.html"), body.getBytes("UTF-8"))
          } catch { case _: Exception => }
          body
        }.exists
      )

  val getAmlSupervisorNamePage: HttpRequestBuilder =
    http("Get AML Supervisor Name Page")
      .get(_ => debugUrl("Get AML Supervisor Name Page URL", s"$baseUrl$route/anti-money-laundering/supervisor-name"))
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postAmlSupervisorName: HttpRequestBuilder =
    http("Post AML Supervisor Name")
      .post(_ => debugUrl("Post AML Supervisor Name URL", s"$baseUrl$route/anti-money-laundering/supervisor-name"))
      .formParam("amlsSupervisoryBody", "HMRC")
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("submit", "SaveAndContinue")
      .check(status.is(303))
      .check(
        header("Location")
          .in(
            "/agent-registration/apply/anti-money-laundering/registration-number",
            "/agent-registration/apply/anti-money-laundering/check-your-answers"
          )
          .saveAs("postAmlSupervisorNameRedirectUrl")
      )

  val getAmlRegistrationNumberPage: HttpRequestBuilder =
    http("Get AML Registration Number Page")
      .get(_ => debugUrl("Get AML Registration Number Page URL", s"$baseUrl$route/anti-money-laundering/registration-number"))
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postAmlRegistrationNumber: HttpRequestBuilder =
    http("Post AML Registration Number")
      .post(_ => debugUrl("Post AML Registration Number URL", s"$baseUrl$route/anti-money-laundering/registration-number"))
      .formParam("amlsRegistrationNumber", "XAML00000123456")
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("submit", "SaveAndContinue")
      .check(status.is(303))
      .check(
        header("Location")
          .in(
            "/agent-registration/apply/anti-money-laundering/check-your-answers",
            "/agent-registration/apply/task-list"
          )
          .saveAs("postAmlRegistrationNumberRedirectUrl")
      )

  val getAmlCheckYourAnswersPage: HttpRequestBuilder =
    http("Get AML Check Your Answers Page")
      .get(_ => debugUrl("Get AML Check Your Answers Page URL", s"$baseUrl$route/anti-money-laundering/check-your-answers"))
      .check(status.is(200))
      .check(substring("Check your answers"))
      .check(regex("""href="/agent-registration/apply/task-list"""").exists)

  val goToTaskListFromAmlCya: HttpRequestBuilder =
    http("Go To Task List From AML CYA")
      .get(_ => debugUrl("Go To Task List From AML CYA URL", s"$baseUrl$route/task-list"))
      .header("Referer", s"$baseUrl$route/anti-money-laundering/check-your-answers")
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("amlTaskListRedirect1"))
      .check(
        bodyString.transform { body =>
          try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/debug"))
            val content = if (body.nonEmpty) body else "EMPTY_BODY"
            java.nio.file.Files.write(java.nio.file.Paths.get("target/debug/task-list-after-aml-cya-final.html"), content.getBytes("UTF-8"))
          } catch { case _: Exception => }
          body
        }.exists
      )

  val followTaskListRedirectAfterAmlCya1: HttpRequestBuilder =
    http("Follow Task List Redirect After AML CYA 1")
      .get(session => {
        val fullUrl = session("amlTaskListRedirect1").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/task-list")
        debugUrl("Follow Task List Redirect After AML CYA 1 URL", fullUrl)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("amlTaskListRedirect2"))

  val followTaskListRedirectAfterAmlCya2: HttpRequestBuilder =
    http("Follow Task List Redirect After AML CYA 2")
      .get(session => {
        val fullUrl = session("amlTaskListRedirect2").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/task-list")
        debugUrl("Follow Task List Redirect After AML CYA 2 URL", fullUrl)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("amlTaskListRedirect3"))

  val followTaskListRedirectAfterAmlCya3: HttpRequestBuilder =
    http("Follow Task List Redirect After AML CYA 3")
      .get(session => {
        val fullUrl = session("amlTaskListRedirect3").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/task-list")
        debugUrl("Follow Task List Redirect After AML CYA 3 URL", fullUrl)
      })
      .check(status.is(200))
      .check(
        bodyString.transform { body =>
          try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/debug"))
            java.nio.file.Files.write(java.nio.file.Paths.get("target/debug/task-list-after-aml-cya-final.html"), body.getBytes("UTF-8"))
          } catch { case _: Exception => }
          body
        }.exists
      )

  val getAgentStandardAcceptPage: HttpRequestBuilder =
    http("Get Agent Standard Accept Page")
      .get(_ => debugUrl("Get Agent Standard Accept Page URL", s"$baseUrl$route/agent-standard/accept-agent-standard"))
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postAgentStandardAccept: HttpRequestBuilder =
    http("Post Agent Standard Accept")
      .post(_ => debugUrl("Post Agent Standard Accept URL", s"$baseUrl$route/agent-standard/accept-agent-standard"))
      .formParam("submit", "AgreeAndContinue")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))
      .check(
        header("Location")
          .in(
            "/agent-registration/apply/task-list"
          )
          .saveAs("agentStandardTaskListRedirect1")
      )

  val followTaskListRedirectAfterAgentStandard1: HttpRequestBuilder =
    http("Follow Task List Redirect After Agent Standard 1")
      .get(session => {
        val fullUrl = session("agentStandardTaskListRedirect1").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/task-list")
        debugUrl("Follow Task List Redirect After Agent Standard 1 URL", fullUrl)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentStandardTaskListRedirect2"))

  val followTaskListRedirectAfterAgentStandard2: HttpRequestBuilder =
    http("Follow Task List Redirect After Agent Standard 2")
      .get(session => {
        val fullUrl = session("agentStandardTaskListRedirect2").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/task-list")
        debugUrl("Follow Task List Redirect After Agent Standard 2 URL", fullUrl)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentStandardTaskListRedirect3"))

  val followTaskListRedirectAfterAgentStandard3: HttpRequestBuilder =
    http("Follow Task List Redirect After Agent Standard 3")
      .get(session => {
        val fullUrl = session("agentStandardTaskListRedirect3").asOption[String]
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/task-list")
        debugUrl("Follow Task List Redirect After Agent Standard 3 URL", fullUrl)
      })
      .check(status.is(200))
      .check(
        bodyString.transform { body =>
          try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/debug"))
            java.nio.file.Files.write(java.nio.file.Paths.get("target/debug/task-list-after-agent-standard-final.html"), body.getBytes("UTF-8"))
          } catch { case _: Exception => }
          body
        }.exists
      )
      .check(bodyString.transform(extractListDetailsTaskLink).optional.saveAs("listDetailsEntryUrl"))

  val getListDetailsSoleTraderPage: HttpRequestBuilder =
    http("Get List Details Sole Trader Page")
      .get(session => {
        val url = session("listDetailsEntryUrl").asOption[String]
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(frontendUrl)
          .getOrElse(s"$baseUrl$route/list-details/sole-trader")
        debugUrl("Get List Details Sole Trader Page URL", url)
      })
      .check(status.is(200))
      .check(bodyString.transform(extractListDetailsContinueLink).saveAs("listDetailsContinueUrl"))

  val postListDetailsSoleTraderContinue: HttpRequestBuilder =
    http("Post List Details Sole Trader Continue")
      .get(session => {
        val url = session("listDetailsContinueUrl").asOption[String]
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(frontendUrl)
          .getOrElse(s"$baseUrl/agent-registration/sign-out-with-continue")
        debugUrl("Post List Details Sole Trader Continue URL", url)
      })
      .check(status.is(303))
      .check(header("Location").saveAs("listDetailsSignInRedirectUrl"))

  val followListDetailsSignOutRedirectToSignIn: HttpRequestBuilder =
    http("Follow List Details Sign Out Redirect To Sign In")
      .get(session => {
        val url     = session("listDetailsSignInRedirectUrl").as[String]
        val fullUrl = stubsOrFrontendUrl(url)
        debugUrl("Follow List Details Sign Out Redirect To Sign In URL", fullUrl)
      })
      .check(status.is(303))
      .check(header("Location").transform(normalizeSignInLocation).saveAs("signInPageUrl"))
      .check(header("Location").transform(normalizeSignInLocation).saveAs("listDetailsBasSignInUrl"))

  val getSignInPageAfterListDetails: HttpRequestBuilder =
    http("Get Sign In Page After List Details")
      .get(session => {
        val url = session("listDetailsBasSignInUrl").asOption[String]
          .orElse(session("signInPageUrl").asOption[String])
          .map(normalizeSignInLocation)
          .getOrElse(s"$stubsUrl/bas-gateway/sign-in")
        debugUrl("Get Sign In Page After List Details URL", url)
      })
      .check(status.in(200, 303))
      .check(
        header("Location")
          .transform(normalizeSignInLocation)
          .optional
          .saveAs("listDetailsSignInNextUrl")
      )
      .check(
        regex("""<form[^>]*action=\"([^\"]+)\"[^>]*id=\"loginForm\"""")
          .transform(normalizeSignInLocation)
          .optional
          .saveAs("listDetailsGgSignInAction")
      )

  val getGgSignInPageAfterListDetails: HttpRequestBuilder =
    http("Get GG Sign In Page After List Details")
      .get(session => {
        val url = session("listDetailsSignInNextUrl").asOption[String]
          .orElse(session("listDetailsBasSignInUrl").asOption[String])
          .orElse(session("signInPageUrl").asOption[String])
          .map(normalizeSignInLocation)
          .getOrElse(s"$stubsUrl/bas-gateway/sign-in")
        debugUrl("Get GG Sign In Page After List Details URL", url)
      })
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))
      .check(
        regex("""<form[^>]*action=\"([^\"]+)\"[^>]*id=\"loginForm\"""")
          .transform(normalizeSignInLocation)
          .optional
          .saveAs("listDetailsGgSignInAction")
      )
      .check(
        bodyString.transform(_ => s"perf-${UUID.randomUUID().toString.take(8)}").saveAs("individualUserId")
      )

  val postSignInWithIndividualUser: HttpRequestBuilder =
    http("Post Sign In With Individual User")
      .post(session => {
        val validation = session("listDetailsGgSignInAction").asOption[String]
          .filter(_.nonEmpty)
          .map(io.gatling.commons.validation.Success(_))
          .getOrElse {
            session("listDetailsBasSignInUrl").asOption[String]
              .orElse(session("signInPageUrl").asOption[String])
              .map(normalizeSignInLocation)
              .flatMap(ggSignInUrlFromBasUrl)
              .map(io.gatling.commons.validation.Success(_))
              .getOrElse(io.gatling.commons.validation.Failure("Unable to derive GG sign-in URL from BAS sign-in redirect"))
          }

        validation.flatMap(url => debugUrl("Post Sign In With Individual User URL", url))
      })
      .formParam("userId", "#{individualUserId}")
      .formParam("planetId", "#{planetId}")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))
      .check(headerRegex("Location", "(.*/agents-external-stubs/user/(?:create|edit)\\?.*continue=.*)").saveAs("userEditPageUrl"))

  // --------------------------------------------------
  // Prove identity
  // --------------------------------------------------

  val getStubsUserEditPageAfterListDetails: HttpRequestBuilder =
    http("Get Stubs User Edit Page After List Details")
      .get(session => {
        val fullUrl = normalizeSignInLocation(session("userEditPageUrl").as[String])
        debugUrl("Get Stubs User Edit Page After List Details URL", fullUrl)
      })
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))
      .check(
        bodyString.transform { body =>
          extractFormActionById(body, Seq("userForm", "initialUserDataForm"))
        }.saveAs("stubsUserUpdateActionAfterListDetails")
      )
      .check(bodyString.transform(body => extractInputValue(body, "name")).saveAs("stubsUserNameAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "nino")).saveAs("stubsNinoAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "dateOfBirth.day")).saveAs("stubsDobDayAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "dateOfBirth.month")).saveAs("stubsDobMonthAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "dateOfBirth.year")).saveAs("stubsDobYearAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "groupId")).saveAs("groupIdAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "address.line1")).saveAs("addressLine1AfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "address.line2")).saveAs("addressLine2AfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "address.line3")).saveAs("addressLine3AfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "address.line4")).saveAs("addressLine4AfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "address.postcode")).saveAs("addressPostcodeAfterListDetails"))
      .check(bodyString.transform(body => extractSelectedOptionValue(body, "credentialRole")).saveAs("credentialRoleAfterListDetails"))
      .check(bodyString.transform(body => extractSelectedOptionValue(body, "credentialStrength")).saveAs("credentialStrengthAfterListDetails"))
      .check(bodyString.transform(body => extractSelectedOptionValue(body, "confidenceLevel")).saveAs("confidenceLevelAfterListDetails"))
      .check(bodyString.transform(body => extractSelectedOptionValue(body, "address.countryCode")).saveAs("addressCountryAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "assignedPrincipalEnrolments[0].key")).saveAs("principalEnrolmentKeyAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "assignedPrincipalEnrolments[0].identifiers[0].key")).saveAs("principalEnrolmentIdentifierKeyAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "assignedPrincipalEnrolments[0].identifiers[0].value")).saveAs("principalEnrolmentIdentifierValueAfterListDetails"))

  val postStubsUserCreatePageAfterListDetails: HttpRequestBuilder =
    http("Post Stubs User Create Page After List Details")
      .post(session => {
        val url     = session("stubsUserUpdateActionAfterListDetails").as[String]
        val fullUrl = normalizeSignInLocation(url)

        debug(s"[DEBUG] stubs user create/update action after list details = [$url]")
        debugUrl("Post Stubs User Create Page After List Details URL", fullUrl)
      })
      .disableFollowRedirect
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("affinityGroup", "Individual")
      .formParam("principalEnrolmentService", "HMRC-PT")
      .check(status.is(303))
      .check(
        header("Location")
          .transform { loc =>
            val fullLocation = normalizeSignInLocation(loc)

            debug(s"[DEBUG] Location after stubs user create after list details = [$fullLocation]")

            fullLocation
          }
          .saveAs("stubsUserEditPageAfterCreateUrl")
      )

  val getStubsUserEditPageAfterCreate: HttpRequestBuilder =
    http("Get Stubs User Edit Page After Create")
      .get(session => {
        val fullUrl = normalizeSignInLocation(session("stubsUserEditPageAfterCreateUrl").as[String])
        debugUrl("Get Stubs User Edit Page After Create URL", fullUrl)
      })
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))
      .check(
        bodyString.transform { body =>
          extractFormActionById(body, Seq("userForm"))
        }.saveAs("stubsUserUpdateActionAfterCreate")
      )
      .check(bodyString.transform(body => extractInputValue(body, "nino")).saveAs("stubsNinoAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "dateOfBirth.day")).saveAs("stubsDobDayAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "dateOfBirth.month")).saveAs("stubsDobMonthAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "dateOfBirth.year")).saveAs("stubsDobYearAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "groupId")).saveAs("groupIdAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "address.line1")).saveAs("addressLine1AfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "address.line2")).saveAs("addressLine2AfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "address.line3")).saveAs("addressLine3AfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "address.line4")).saveAs("addressLine4AfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "address.postcode")).saveAs("addressPostcodeAfterListDetails"))
      .check(bodyString.transform(body => extractSelectedOptionValue(body, "credentialRole")).saveAs("credentialRoleAfterListDetails"))
      .check(bodyString.transform(body => extractSelectedOptionValue(body, "credentialStrength")).saveAs("credentialStrengthAfterListDetails"))
      .check(bodyString.transform(body => extractSelectedOptionValue(body, "confidenceLevel")).saveAs("confidenceLevelAfterListDetails"))
      .check(bodyString.transform(body => extractSelectedOptionValue(body, "address.countryCode")).saveAs("addressCountryAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "assignedPrincipalEnrolments[0].key")).saveAs("principalEnrolmentKeyAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "assignedPrincipalEnrolments[0].identifiers[0].key")).saveAs("principalEnrolmentIdentifierKeyAfterListDetails"))
      .check(bodyString.transform(body => extractInputValue(body, "assignedPrincipalEnrolments[0].identifiers[0].value")).saveAs("principalEnrolmentIdentifierValueAfterListDetails"))

  val postStubsUserUpdatePageAfterCreate: HttpRequestBuilder =
    http("Post Stubs User Update Page After Create")
      .post(session => {
        val url     = session("stubsUserUpdateActionAfterCreate").as[String]
        val fullUrl = normalizeSignInLocation(url)

        debug(s"[DEBUG] stubs user update action after create = [$url]")
        debugUrl("Post Stubs User Update Page After Create URL", fullUrl)
      })
      .disableFollowRedirect
      .formParam("csrfToken", "#{csrfToken}")
      .formParam("name", "Test User")
      .formParam("credentialRole", "#{credentialRoleAfterListDetails}")
      .formParam("credentialStrength", "#{credentialStrengthAfterListDetails}")
      .formParam("confidenceLevel", "#{confidenceLevelAfterListDetails}")
      .formParam("nino", "#{stubsNinoAfterListDetails}")
      .formParam("dateOfBirth.day", "#{stubsDobDayAfterListDetails}")
      .formParam("dateOfBirth.month", "#{stubsDobMonthAfterListDetails}")
      .formParam("dateOfBirth.year", "#{stubsDobYearAfterListDetails}")
      .formParam("groupId", "#{groupIdAfterListDetails}")
      .formParam("address.line1", "#{addressLine1AfterListDetails}")
      .formParam("address.line2", "#{addressLine2AfterListDetails}")
      .formParam("address.line3", "#{addressLine3AfterListDetails}")
      .formParam("address.line4", "#{addressLine4AfterListDetails}")
      .formParam("address.postcode", "#{addressPostcodeAfterListDetails}")
      .formParam("address.countryCode", "#{addressCountryAfterListDetails}")
      .formParam("assignedPrincipalEnrolments[0].key", "#{principalEnrolmentKeyAfterListDetails}")
      .formParam("assignedPrincipalEnrolments[0].identifiers[0].key", "#{principalEnrolmentIdentifierKeyAfterListDetails}")
      .formParam("assignedPrincipalEnrolments[0].identifiers[0].value", "#{principalEnrolmentIdentifierValueAfterListDetails}")
      .formParam("assignedPrincipalEnrolments[1].key", "")
      .formParam("assignedPrincipalEnrolments[1].identifiers[0].key", "")
      .formParam("assignedPrincipalEnrolments[1].identifiers[0].value", "")
      .formParam("assignedDelegatedEnrolments[0].key", "")
      .formParam("assignedDelegatedEnrolments[0].identifiers[0].key", "")
      .formParam("assignedDelegatedEnrolments[0].identifiers[0].value", "")
      .formParam("utr", "")
      .check(status.is(303))
      .check(
        header("Location")
          .transform { loc =>
            val fullLocation = normalizeToFrontend(loc)

            debug(s"[DEBUG] Location after stubs user update after create = [$fullLocation]")

            fullLocation
          }
          .saveAs("matchApplicationUrlFromContinue")
      )

  val getMatchApplicationPage: HttpRequestBuilder =
    http("Get Match Application Page")
      .get(session => {
        val url     = session("matchApplicationUrlFromContinue").as[String]
        val fullUrl = normalizeSignInLocation(url)

        debug(s"[DEBUG] matchApplicationUrlFromContinue = [$url]")
        debugUrl("Get Match Application Page URL", fullUrl)
      })
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("matchApplicationCsrfToken"))

  val postConfirmMatchToIndividualProvidedDetailsYes: HttpRequestBuilder =
    http("Post Confirm Match To Individual Provided Details Yes")
      .post(session => {
        val rawUrl = session("matchApplicationUrlFromContinue").as[String]

        val fullUrl =
          extractContinueUrl(rawUrl).getOrElse(normalizeSignInLocation(rawUrl))

        debug(s"[DEBUG] raw matchApplicationUrlFromContinue for POST = [$rawUrl]")
        debugUrl("Post Confirm Match To Individual Provided Details Yes URL", fullUrl)
      })
      .disableFollowRedirect
      .formParam("csrfToken", "#{matchApplicationCsrfToken}")
      .formParam("confirmMatchToIndividualProvidedDetails", "Yes")
      .formParam("submit", "SaveAndContinue")
      .check(status.is(303))
      .check(
        header("Location")
          .transform { loc =>
            val fullLocation = normalizeToFrontend(loc)
            debug(s"[DEBUG] Location after Post Confirm Match To Individual Provided Details Yes = [$fullLocation]")
            fullLocation
          }
          .saveAs("provideDetailsCheckYourAnswersUrl")
      )

  val getProvideDetailsCheckYourAnswersAfterMatch: HttpRequestBuilder =
    http("Get Provide Details Check Your Answers After Match")
      .get(session => {
        val fullUrl = normalizeSignInLocation(session("provideDetailsCheckYourAnswersUrl").as[String])
        debugUrl("Get Provide Details Check Your Answers After Match URL", fullUrl)
      })
      .disableFollowRedirect
      .check(status.is(303))
      .check(
        header("Location")
          .transform { loc =>
            val fullLocation = normalizeToFrontend(loc)

            debug(s"[DEBUG] Location from provide details CYA = [$fullLocation]")

            fullLocation
          }
          .saveAs("individualSaUtrPageUrl")
      )

  val getIndividualSaUtrPage: HttpRequestBuilder =
    http("Get Individual SA UTR Page")
      .get(session => {
        val fullUrl = normalizeSignInLocation(session("individualSaUtrPageUrl").as[String])
        debugUrl("Get Individual SA UTR Page URL", fullUrl)
      })
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("individualSaUtrCsrfToken"))
      .check(
        bodyString.transform(extractFirstFormAction).saveAs("individualSaUtrFormAction")
      )

  val postIndividualSaUtrYes: HttpRequestBuilder =
    http("Post Individual SA UTR Yes")
      .post(session => {
        val fullUrl = normalizeSignInLocation(session("individualSaUtrFormAction").as[String])
        debugUrl("Post Individual SA UTR Yes URL", fullUrl)
      })
      .disableFollowRedirect
      .formParam("csrfToken", "#{individualSaUtrCsrfToken}")
      .formParam("individualSaUtr.hasSaUtr", "Yes")
      .formParam("individualSaUtr.saUtr", "1234567890")
      .formParam("submit", "SaveAndContinue")
      .check(status.is(303))
      .check(
        header("Location")
          .transform { loc =>
            val fullLocation = normalizeToFrontend(loc)
            debug(s"[DEBUG] Location after Post Individual SA UTR Yes = [$fullLocation]")
            fullLocation
          }
          .saveAs("provideDetailsCheckYourAnswersAfterUtrUrl")
      )

  val getProvideDetailsCheckYourAnswersAfterUtr: HttpRequestBuilder =
    http("Get Provide Details Check Your Answers After UTR")
      .get(session => {
        val fullUrl = normalizeSignInLocation(session("provideDetailsCheckYourAnswersAfterUtrUrl").as[String])
        debugUrl("Get Provide Details Check Your Answers After UTR URL", fullUrl)
      })
      .disableFollowRedirect
      .check(status.is(303))
      .check(
        header("Location")
          .transform { loc =>
            val fullLocation = normalizeToFrontend(loc)

            debug(s"[DEBUG] Location from provide details CYA after UTR = [$fullLocation]")

            fullLocation
          }
          .saveAs("ucrIdentifiersUrl")
      )

  val getUnifiedCustomerRegistryIdentifiers: HttpRequestBuilder =
    http("Get Unified Customer Registry Identifiers")
      .get(session => {
        val fullUrl = normalizeSignInLocation(session("ucrIdentifiersUrl").as[String])
        debugUrl("Get Unified Customer Registry Identifiers URL", fullUrl)
      })
      .disableFollowRedirect
      .check(status.is(303))
      .check(
        header("Location")
          .transform { loc =>
            val fullLocation = normalizeToFrontend(loc)

            debug(s"[DEBUG] Location after UCR identifiers = [$fullLocation]")

            fullLocation
          }
          .saveAs("provideDetailsCheckYourAnswersAfterUcrUrl")
      )

  val getProvideDetailsCheckYourAnswersAfterUcr: HttpRequestBuilder =
    http("Get Provide Details Check Your Answers After UCR")
      .get(session => {
        val fullUrl = normalizeSignInLocation(session("provideDetailsCheckYourAnswersAfterUcrUrl").as[String])
        debugUrl("Get Provide Details Check Your Answers After UCR URL", fullUrl)
      })
      .disableFollowRedirect
      .check(status.is(303))
      .check(
        header("Location")
          .transform { loc =>
            val fullLocation = normalizeToFrontend(loc)

            debug(s"[DEBUG] Location from provide details CYA after UCR = [$fullLocation]")

            fullLocation
          }
          .saveAs("confirmationPageUrl")
      )

  val getConfirmationPage: HttpRequestBuilder =
    http("Get Confirmation Page")
      .get(session => {
        val fullUrl = normalizeSignInLocation(session("confirmationPageUrl").as[String])
        debugUrl("Get Confirmation Page URL", fullUrl)
      })
      .check(status.is(200))
      .check(
        bodyString.transform { body =>
          val signBackInLinkRegex =
            """<a[^>]*href="([^"]+)"[^>]*>\s*Sign back into your application\s*</a>""".r

          signBackInLinkRegex.findFirstMatchIn(body) match {
            case Some(m) => m.group(1).replace("&amp;", "&")
            case None    => ""
          }
        }.saveAs("signBackIntoApplicationUrl")
      )

  val getSignBackIntoApplication: HttpRequestBuilder =
    http("Get Sign Back Into Application")
      .get(session => {
        val fullUrl = normalizeSignInLocation(session("signBackIntoApplicationUrl").as[String])
        debugUrl("Get Sign Back Into Application URL", fullUrl)
      })
      .check(status.in(303, 200))
      .check(header("Location").saveAs("basGatewaySignInUrl"))

  val getBasGatewaySignInPage: HttpRequestBuilder =
    http("Get BAS Gateway Sign In Page")
      .get(session => {
        val fullUrl = normalizeSignInLocation(session("basGatewaySignInUrl").as[String])
        debugUrl("Get BAS Gateway Sign In Page URL", fullUrl)
      })
      .disableFollowRedirect
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("basGatewaySignInRedirectUrl"))
      .check(css("input[name=csrfToken]", "value").optional.saveAs("basGatewayCsrfToken"))
      .check(
        bodyString.transform(extractFirstFormAction).optional.saveAs("basGatewaySignInFormAction")
      )

  val followBasGatewaySignInRedirect: HttpRequestBuilder =
    http("Follow BAS Gateway Sign In Redirect")
      .get(session => {
        val fullUrl = session("basGatewaySignInRedirectUrl").asOption[String]
          .map(normalizeSignInLocation)
          .getOrElse(normalizeSignInLocation(session("basGatewaySignInUrl").as[String]))

        debugUrl("Follow BAS Gateway Sign In Redirect URL", fullUrl)
      })
      .disableFollowRedirect
      .check(status.is(303))
      .check(
        header("Location")
          .transform { loc =>
            val fullLocation = normalizeSignInLocation(loc)

            debug(s"[DEBUG] Location after task-list redirect to sign-in = [$fullLocation]")

            fullLocation
          }
          .saveAs("finalBasGatewaySignInPageUrl")
      )

  val getFinalBasGatewaySignInPage: HttpRequestBuilder =
    http("Get Final BAS Gateway Sign In Page")
      .get(session => {
        val fullUrl = normalizeSignInLocation(session("finalBasGatewaySignInPageUrl").as[String])
        debugUrl("Get Final BAS Gateway Sign In Page URL", fullUrl)
      })
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").optional.saveAs("basGatewayCsrfToken"))
      .check(
        bodyString.transform(extractFirstFormAction).saveAs("basGatewaySignInFormAction")
      )

  val postBasGatewaySignIn: HttpRequestBuilder =
    http("Post BAS Gateway Sign In")
      .post(session => {
        val fullUrl = normalizeSignInLocation(session("basGatewaySignInFormAction").as[String])
        debugUrl("Post BAS Gateway Sign In URL", fullUrl)
      })
      .formParam("csrfToken", "#{basGatewayCsrfToken}")
      .formParam("userId", "#{userId}")
      .formParam("planetId", "#{planetId}")
      .check(status.is(303))
      .check(
        header("Location")
          .transform { loc =>
            val fullLocation = normalizeToFrontend(loc)
            debug(s"[DEBUG] Location after Post BAS Gateway Sign In = [$fullLocation]")
            fullLocation
          }
          .saveAs("taskListUrlAfterFinalSignIn")
      )

  val getTaskListAfterFinalSignIn: HttpRequestBuilder =
    http("Get Task List After Final Sign In")
      .get(session => {
        val fullUrl = normalizeSignInLocation(session("taskListUrlAfterFinalSignIn").as[String])
        debugUrl("Get Task List After Final Sign In URL", fullUrl)
      })
      .check(status.is(200))

  // --------------------------------------------------
  // Declaration and submission
  // --------------------------------------------------

  val getAgentDeclarationPage: HttpRequestBuilder =
    http("Get Agent Declaration Page")
      .get(_ => debugUrl("Get Agent Declaration Page URL", s"$baseUrl$route/agent-declaration/confirm-declaration"))
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("agentDeclarationCsrfToken"))
      .check(
        bodyString.transform { body =>
          val action = extractFirstFormAction(body)

          if (action.nonEmpty) action
          else "/agent-registration/apply/agent-declaration/confirm-declaration"
        }.saveAs("agentDeclarationFormAction")
      )

  val postAgentDeclarationAcceptAndSend: HttpRequestBuilder =
    http("Post Agent Declaration Accept And Send")
      .post(session => {
        val fullUrl = normalizeSignInLocation(session("agentDeclarationFormAction").as[String])
        debugUrl("Post Agent Declaration Accept And Send URL", fullUrl)
      })
      .disableFollowRedirect
      .formParam("csrfToken", "#{agentDeclarationCsrfToken}")
      .formParam("submit", "AcceptAndSend")
      .check(status.is(303))
      .check(
        header("Location")
          .transform { loc =>
            val fullLocation = normalizeToFrontend(loc)
            debug(s"[DEBUG] Location after Post Agent Declaration Accept And Send = [$fullLocation]")
            fullLocation
          }
          .saveAs("applicationStatusUrl")
      )

  val getApplicationStatusPage: HttpRequestBuilder =
    http("Get Application Status Page")
      .get(session => {
        val fullUrl = normalizeSignInLocation(session("applicationStatusUrl").as[String])
        debugUrl("Get Application Status Page URL", fullUrl)
      })
      .check(status.is(200))
      .check(substring("You’ve applied for an agent services account"))
}
