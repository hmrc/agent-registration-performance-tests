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

import java.util.UUID

object AgentRegistrationRequests extends ServicesConfiguration {

  val baseUrl: String    = baseUrlFor("agent-registration")
  val stubsUrl: String   = baseUrlFor("agents-external-stubs")
  val route: String      = "/agent-registration/apply"

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

}


