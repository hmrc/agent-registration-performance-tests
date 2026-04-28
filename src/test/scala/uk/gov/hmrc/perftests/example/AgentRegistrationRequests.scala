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
import java.net.URLDecoder
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.util.Try

object AgentRegistrationRequests extends ServicesConfiguration {

  val baseUrl: String    = baseUrlFor("agent-registration")
  val stubsUrl: String   = baseUrlFor("agents-external-stubs")
  val route: String      = "/agent-registration/apply"

  private val emailVerificationBaseUrl = Try(baseUrlFor("email-verification")).getOrElse("http://localhost:9890")

  private def maybeNormalizeLocation(location: String): String =
    Option(location).map(_.replace("&amp;", "&").trim).filter(_.nonEmpty).map(normalizeLocation).orNull

  private def maybeExtractEmailVerificationLink(location: String): String =
    Option(location).map(_.replace("&amp;", "&").trim).filter(_.nonEmpty).flatMap { value =>
      val key = "emailVerificationLink="

      if (value.contains("/agent-registration/test-only/email-verification-pass-codes") && value.contains(key)) {
        val encoded = value.substring(value.indexOf(key) + key.length)
        Try(URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())).toOption
      } else Some(value)
    }.map(maybeNormalizeLocation).orNull

  private def passcodesPageUrlFromSession(session: Session) =
    session.attributes.get("verifyEmailPageUrl").collect {
      case s: String if s.contains("/agent-registration/test-only/email-verification-pass-codes") => maybeNormalizeLocation(s)
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

  private def normalizeLocation(location: String): String = {
    if (location.startsWith("http")) location
    else if (location.startsWith("/email-verification/")) s"$emailVerificationBaseUrl$location"
    else s"$baseUrl$location"
  }

  private def normalizeSignInLocation(location: String): String = {
    val cleaned = Option(location).map(_.replace("&amp;", "&").trim).getOrElse("")

    if (cleaned.startsWith("http")) cleaned
    else if (cleaned.startsWith("/bas-gateway/") || cleaned.startsWith("/gg/") || cleaned.startsWith("/agents-external-stubs/")) s"$stubsUrl$cleaned"
    else if (cleaned.startsWith("/")) s"$baseUrl$cleaned"
    else cleaned
  }

  private def decodeQueryValue(value: String): String =
    Try(URLDecoder.decode(value, StandardCharsets.UTF_8.name())).getOrElse(value)

  private def extractQueryParams(url: String): Map[String, String] =
    Try(new URI(url)).toOption
      .flatMap(uri => Option(uri.getRawQuery))
      .map(
        _.split("&").toSeq.flatMap {
          case pair if pair.contains("=") =>
            pair.split("=", 2) match {
              case Array(key, value) => Some(decodeQueryValue(key) -> decodeQueryValue(value))
              case _                 => None
            }
          case key if key.nonEmpty => Some(decodeQueryValue(key) -> "")
          case _                   => None
        }.toMap
      )
      .getOrElse(Map.empty)

  private def ggSignInUrlFromBasUrl(url: String): Option[String] = {
    val normalized = normalizeSignInLocation(url)
    val params     = extractQueryParams(normalized)

    params.get("continue_url").orElse(params.get("continue")).map { continue =>
      val originParam = params.get("origin").filter(_.nonEmpty).map(origin => s"&origin=$origin").getOrElse("")
      s"${originOf(normalized).getOrElse(stubsUrl)}/gg/sign-in?continue=$continue$originParam"
    }
  }

  private def originOf(url: String): Option[String] =
    Try(new URI(url)).toOption.flatMap { uri =>
      Option(uri.getScheme).flatMap { scheme =>
        Option(uri.getHost).map { host =>
          val portPart = if (uri.getPort > 0) s":${uri.getPort}" else ""
          s"$scheme://$host$portPart"
        }
      }
    }

  private def normalizeEmailVerificationAction(action: String, session: Session): String = {
    val cleaned = Option(action).map(_.replace("&amp;", "&").trim).getOrElse("")

    if (cleaned.startsWith("http")) cleaned
    else if (cleaned.startsWith("/email-verification/")) {
      val emailVerificationOrigin = emailVerificationUrlFromSession(session) match {
        case io.gatling.commons.validation.Success(url: String) => originOf(url)
        case _                                          => None
      }
      s"${emailVerificationOrigin.getOrElse(emailVerificationBaseUrl)}$cleaned"
    } else if (cleaned.startsWith("/")) s"$baseUrl$cleaned"
    else cleaned
  }

  // Classify the returned page so we can see where the journey actually landed.
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
        bodyString.transform(_ => s"perf-${UUID.randomUUID().toString.take(8)}").saveAs("userId")
      )
      .check(
        bodyString.transform(_ => s"perf-${UUID.randomUUID().toString.take(8)}").saveAs("planetId")
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
      .get(session => {
        val url = session("taskListUrl").as[String]
        if (url.startsWith("http")) url else s"$baseUrl$url"
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
        session("applicantDetailsEntryUrl").asOption[String]
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(url => if (url.startsWith("http")) url else s"$baseUrl$url")
          .map(io.gatling.commons.validation.Success(_))
          .getOrElse(io.gatling.commons.validation.Success(s"$baseUrl$route/task-list"))
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
          .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
          .orElse(
            session("applicantDetailsEntryUrl").asOption[String]
              .map(_.trim)
              .filter(_.nonEmpty)
              .map(url => if (url.startsWith("http")) url else s"$baseUrl$url")
          )
          .map(io.gatling.commons.validation.Success(_))
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
          .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
          .getOrElse(s"$baseUrl$route/applicant/applicant-name")
        io.gatling.commons.validation.Success(url)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("applicantDetailsRedirectUrl3"))
      .check(css("input[name=csrfToken]", "value").optional.saveAs("csrfToken"))

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
        regex("""((?:https?://[^\"']+)?/email-verification/journey/[^\"']+/passcode\?[^\"'\s<]+)""")
          .optional
          .saveAs("emailVerificationLinkFromBody")
      )

  val getEmailVerificationPasscodesPage: HttpRequestBuilder =
    http("Get Email Verification Passcodes Page")
      .get(session => {
        passcodesPageUrlFromSession(session)
          .map(io.gatling.commons.validation.Success(_))
          .getOrElse {
            emailVerificationUrlFromSession(session).map { emailVerificationLink =>
              val encodedLink = URLEncoder.encode(emailVerificationLink, StandardCharsets.UTF_8.name())
              s"$baseUrl/agent-registration/test-only/email-verification-pass-codes?emailVerificationLink=$encodedLink"
            }
          }
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
      .get(session => emailVerificationUrlFromSession(session))
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
        action
          .map(a => normalizeEmailVerificationAction(a, session))
          .filter(_.nonEmpty)
          .map(io.gatling.commons.validation.Success(_))
          .getOrElse(emailVerificationUrlFromSession(session))
      })
      .formParam("passcode", "#{emailVerificationCode}")
      .formParam("csrfToken", "#{csrfToken}")
      .check(status.is(303))
      .check(header("Location").saveAs("postEmailVerificationRedirectUrl"))

  val getEmailVerificationRedirectPage: HttpRequestBuilder =
    http("Get Email Verification Redirect Page")
      .get(session => {
        val location = session("postEmailVerificationRedirectUrl").as[String]
        if (location.startsWith("http")) location else s"$baseUrl$location"
      })
      .check(status.is(303))
      .check(header("Location").is("/agent-registration/apply/applicant/check-your-answers"))

  val getApplicantCheckYourAnswersPage: HttpRequestBuilder =
    http("Get Applicant Check Your Answers Page")
      .get(s"$baseUrl$route/applicant/check-your-answers")
      .check(status.is(200))
      .check(substring("Check your answers"))
      .check(regex("""href="/agent-registration/apply/task-list"""").exists)
      .check(css("input[name=csrfToken]", "value").optional.saveAs("csrfToken"))

  // 1) Click "Confirm and continue" from applicant CYA (it's a GET link, not a POST form)
  val goToTaskListFromApplicantCya: HttpRequestBuilder =
    http("Go To Task List From Applicant CYA")
      .get(s"$baseUrl$route/task-list")
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

  // 2) Follow first redirect if present
  val followTaskListRedirect1: HttpRequestBuilder =
    http("Follow Task List Redirect 1")
      .get(session =>
        io.gatling.commons.validation.Success(
          session("taskListRedirect1").asOption[String]
            .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
            .getOrElse(s"$baseUrl$route/task-list")
        )
      )
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("taskListRedirect2"))

  // 3) Follow second redirect if present and ONLY THEN assert task-list HTML
  val followTaskListRedirect2AndExtractAgentDetails: HttpRequestBuilder =
    http("Follow Task List Redirect 2 And Extract Agent Details")
      .get(session =>
        io.gatling.commons.validation.Success(
          session("taskListRedirect2").asOption[String]
            .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
            .getOrElse(s"$baseUrl$route/task-list")
        )
      )
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
      .get(session =>
        io.gatling.commons.validation.Success(
          session("taskListRedirect3").asOption[String]
            .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
            .getOrElse(s"$baseUrl$route/task-list")
        )
      )
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

  // 4) Enter agent-details section (fallback to direct route if link missing)
  val enterAgentDetailsFromTaskList: HttpRequestBuilder =
    http("Enter Agent Details From Task List")
      .get(session =>
        session("agentDetailsEntryUrl").asOption[String]
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(url => if (url.startsWith("http")) url else s"$baseUrl$url")
          .map(io.gatling.commons.validation.Success(_))
          .getOrElse(io.gatling.commons.validation.Failure("Agent details entry URL missing from task-list response"))
      )
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentDetailsRedirectUrl"))

  // When accessing agent details from task list, the application redirects to CYA which then
  // checks for unanswered questions and redirects to the first unanswered page if needed.
  val getAgentDetailsCheckYourAnswersPage: HttpRequestBuilder =
    http("Get Agent Details Check Your Answers Page")
      .get(session => {
        session("agentDetailsEntryUrl").asOption[String]
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(url => if (url.startsWith("http")) url else s"$baseUrl$url")
          .map(io.gatling.commons.validation.Success(_))
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

  // Follow any redirect from the agent details entry point (typically to CYA or first unanswered page)
  val followAgentDetailsInitialRedirect: HttpRequestBuilder =
    http("Follow Agent Details Initial Redirect")
      .get(session => {
        val url = session("agentDetailsRedirectUrl").asOption[String]
          .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
          .getOrElse(s"$baseUrl$route/agent-details/check-your-answers")
        io.gatling.commons.validation.Success(url)
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
          .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
          .getOrElse(s"$baseUrl$route/agent-details/business-name")
        io.gatling.commons.validation.Success(url)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentDetailsRedirectUrl2"))
      .check(css("input[name=csrfToken]", "value").optional.saveAs("csrfToken"))

  val followAgentDetailsRedirectIfNeeded2: HttpRequestBuilder =
    http("Follow Agent Details Redirect If Needed 2")
      .get(session => {
        val url = session("agentDetailsRedirectUrl2").asOption[String]
          .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
          .getOrElse(s"$baseUrl$route/agent-details/business-name")
        io.gatling.commons.validation.Success(url)
      })
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentDetailsRedirectUrl3"))
      .check(css("input[name=csrfToken]", "value").optional.saveAs("csrfToken"))

  val getBusinessNamePage: HttpRequestBuilder =
    http("Get Business Name Page")
      .get(session => {
        // Try to use redirect from agent details section, otherwise use direct URL
        val url = session("agentDetailsRedirectUrl").asOption[String]
          .orElse(session("agentDetailsRedirectUrl2").asOption[String])
          .orElse(session("agentDetailsRedirectUrl3").asOption[String])
          .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
          .getOrElse(s"$baseUrl$route/agent-details/business-name")
        io.gatling.commons.validation.Success(url)
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
      .post(s"$baseUrl$route/agent-details/business-name")
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
      .get(s"$baseUrl$route/agent-details/telephone-number")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postAgentTelephoneNumber: HttpRequestBuilder =
    http("Post Agent Telephone Number")
      .post(s"$baseUrl$route/agent-details/telephone-number")
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
      .get(s"$baseUrl$route/agent-details/email-address")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postAgentEmail: HttpRequestBuilder =
    http("Post Agent Email")
      .post(s"$baseUrl$route/agent-details/email-address")
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
      .get(s"$baseUrl$route/agent-details/correspondence-address")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postAgentCorrespondenceAddress: HttpRequestBuilder =
    http("Post Agent Correspondence Address")
      .post(s"$baseUrl$route/agent-details/correspondence-address")
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
      .get(s"$baseUrl$route/agent-details/check-your-answers")
      .check(status.is(200))
      .check(substring("Check your answers"))
      .check(regex("""href="/agent-registration/apply/task-list"""").exists)

  val goToTaskListFromAgentCya: HttpRequestBuilder =
    http("Go To Task List From Agent CYA")
      .get(s"$baseUrl$route/task-list")
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
      .get(session =>
        io.gatling.commons.validation.Success(
          session("agentTaskListRedirect1").asOption[String]
            .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
            .getOrElse(s"$baseUrl$route/task-list")
        )
      )
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentTaskListRedirect2"))

  val followTaskListRedirectAfterAgentCya2: HttpRequestBuilder =
    http("Follow Task List Redirect After Agent CYA 2")
      .get(session =>
        io.gatling.commons.validation.Success(
          session("agentTaskListRedirect2").asOption[String]
            .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
            .getOrElse(s"$baseUrl$route/task-list")
        )
      )
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentTaskListRedirect3"))

  val followTaskListRedirectAfterAgentCya3: HttpRequestBuilder =
    http("Follow Task List Redirect After Agent CYA 3")
      .get(session =>
        io.gatling.commons.validation.Success(
          session("agentTaskListRedirect3").asOption[String]
            .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
            .getOrElse(s"$baseUrl$route/task-list")
        )
      )
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
      .get(s"$baseUrl$route/anti-money-laundering/supervisor-name")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postAmlSupervisorName: HttpRequestBuilder =
    http("Post AML Supervisor Name")
      .post(s"$baseUrl$route/anti-money-laundering/supervisor-name")
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
      .get(s"$baseUrl$route/anti-money-laundering/registration-number")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postAmlRegistrationNumber: HttpRequestBuilder =
    http("Post AML Registration Number")
      .post(s"$baseUrl$route/anti-money-laundering/registration-number")
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
      .get(s"$baseUrl$route/anti-money-laundering/check-your-answers")
      .check(status.is(200))
      .check(substring("Check your answers"))
      .check(regex("""href="/agent-registration/apply/task-list"""").exists)

  val goToTaskListFromAmlCya: HttpRequestBuilder =
    http("Go To Task List From AML CYA")
      .get(s"$baseUrl$route/task-list")
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
      .get(session =>
        io.gatling.commons.validation.Success(
          session("amlTaskListRedirect1").asOption[String]
            .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
            .getOrElse(s"$baseUrl$route/task-list")
        )
      )
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("amlTaskListRedirect2"))

  val followTaskListRedirectAfterAmlCya2: HttpRequestBuilder =
    http("Follow Task List Redirect After AML CYA 2")
      .get(session =>
        io.gatling.commons.validation.Success(
          session("amlTaskListRedirect2").asOption[String]
            .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
            .getOrElse(s"$baseUrl$route/task-list")
        )
      )
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("amlTaskListRedirect3"))

  val followTaskListRedirectAfterAmlCya3: HttpRequestBuilder =
    http("Follow Task List Redirect After AML CYA 3")
      .get(session =>
        io.gatling.commons.validation.Success(
          session("amlTaskListRedirect3").asOption[String]
            .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
            .getOrElse(s"$baseUrl$route/task-list")
        )
      )
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
      .get(s"$baseUrl$route/agent-standard/accept-agent-standard")
      .check(status.is(200))
      .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))

  val postAgentStandardAccept: HttpRequestBuilder =
    http("Post Agent Standard Accept")
      .post(s"$baseUrl$route/agent-standard/accept-agent-standard")
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
      .get(session =>
        io.gatling.commons.validation.Success(
          session("agentStandardTaskListRedirect1").asOption[String]
            .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
            .getOrElse(s"$baseUrl$route/task-list")
        )
      )
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentStandardTaskListRedirect2"))

  val followTaskListRedirectAfterAgentStandard2: HttpRequestBuilder =
    http("Follow Task List Redirect After Agent Standard 2")
      .get(session =>
        io.gatling.commons.validation.Success(
          session("agentStandardTaskListRedirect2").asOption[String]
            .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
            .getOrElse(s"$baseUrl$route/task-list")
        )
      )
      .check(status.in(200, 303))
      .check(header("Location").optional.saveAs("agentStandardTaskListRedirect3"))

  val followTaskListRedirectAfterAgentStandard3: HttpRequestBuilder =
    http("Follow Task List Redirect After Agent Standard 3")
      .get(session =>
        io.gatling.commons.validation.Success(
          session("agentStandardTaskListRedirect3").asOption[String]
            .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
            .getOrElse(s"$baseUrl$route/task-list")
        )
      )
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
          .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
          .getOrElse(s"$baseUrl$route/list-details/sole-trader")
        io.gatling.commons.validation.Success(url)
      })
      .check(status.is(200))
      .check(bodyString.transform(extractListDetailsContinueLink).saveAs("listDetailsContinueUrl"))

  val postListDetailsSoleTraderContinue: HttpRequestBuilder =
    http("Post List Details Sole Trader Continue")
      .get(session => {
        val url = session("listDetailsContinueUrl").asOption[String]
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
          .getOrElse(s"$baseUrl/agent-registration/sign-out-with-continue")
        io.gatling.commons.validation.Success(url)
      })
      .check(status.is(303))
      .check(header("Location").saveAs("listDetailsSignInRedirectUrl"))

   val followListDetailsSignOutRedirectToSignIn: HttpRequestBuilder =
     http("Follow List Details Sign Out Redirect To Sign In")
       .get(session => {
         val url = session("listDetailsSignInRedirectUrl").as[String]
         if (url.startsWith("http")) io.gatling.commons.validation.Success(url)
         else if (url.startsWith("/bas-gateway/")) io.gatling.commons.validation.Success(s"$stubsUrl$url")
         else io.gatling.commons.validation.Success(s"$baseUrl$url")
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
          io.gatling.commons.validation.Success(url)
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
         io.gatling.commons.validation.Success(url)
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
         session("listDetailsGgSignInAction").asOption[String]
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
       })
       .formParam("userId", "#{individualUserId}")
       .formParam("planetId", "#{planetId}")
       .formParam("csrfToken", "#{csrfToken}")
       .check(status.is(303))
       .check(headerRegex("Location", ".*/agents-external-stubs/user/(create|edit)\\?.*continue=.*").saveAs("userEditPageUrl"))

   val getStubsUserEditPageAfterListDetails: HttpRequestBuilder =
     http("Get Stubs User Edit Page After List Details")
       .get(session => {
         val url = session("userEditPageUrl").as[String]
         val fullUrl = normalizeSignInLocation(url)
         io.gatling.commons.validation.Success(fullUrl)
       })
       .check(status.is(200))
       .check(css("input[name=csrfToken]", "value").saveAs("csrfToken"))
       .check(css("input[name=name]", "value").optional.saveAs("currentUserName"))
        .check(
          regex("""<form[^>]*action=\"([^\"]+)\"[^>]*id=\"userForm\"""")
            .transform(_.replace("&amp;", "&"))
            .saveAs("stubsUserUpdateActionAfterListDetails")
        )
       .check(regex("""id=\"(?:update1|update2)\"""").exists)

    val postStubsUserEditPageAfterListDetails: HttpRequestBuilder =
      http("Post Stubs User Edit Page After List Details")
        .post(session => {
          val url = session("stubsUserUpdateActionAfterListDetails").as[String]
          val fullUrl = normalizeSignInLocation(url)
          io.gatling.commons.validation.Success(fullUrl)
        })
        .formParam("csrfToken", "#{csrfToken}")
        .formParamSeq(session => {
          val isEditFlow = session("userEditPageUrl").as[String].contains("/user/edit")
          if (isEditFlow) {
            Seq("name" -> "Test User")
          } else {
            Seq(
              "affinityGroup" -> "Individual",
              "principalEnrolmentService" -> "HMRC-PT"
            )
          }
        })
        .check(status.is(303))
        .check(headerRegex("Location", ".*/(?:agent-registration/provide-details/match-application/.*|agents-external-stubs/user/edit\\?continue=.*"))
       .check(status.is(303))
       .check(headerRegex("Location", ".*/(?:agent-registration/provide-details/match-application/.*|agents-external-stubs/user/edit\\?continue=.*)"))


}

