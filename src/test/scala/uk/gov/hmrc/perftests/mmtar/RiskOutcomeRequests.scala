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

object RiskOutcomeRequests extends ServicesConfiguration with AgentRegistrationHelpers {

  override val baseUrl: String  = baseUrlFor("agent-registration")
  override val stubsUrl: String = baseUrlFor("agents-external-stubs")

  private val hmrcAmlsRegistrationNumber = "XAML00000123456"

  private def debugUrl(label: String, fullUrl: String): io.gatling.commons.validation.Validation[String] = {
    debug(s"[DEBUG] $label = [$fullUrl]")
    io.gatling.commons.validation.Success(fullUrl)
  }

  private def absoluteUrl(url: String): String =
    if (url.startsWith("http")) url else s"$baseUrl$url"

  private val feederKeyAliases: Map[String, Seq[String]] = Map(
    "entity_failure_url" -> Seq("primary_failure_url"),
    "entity_failure_code" -> Seq("primary_failure_code"),
    "individual_failure_url" -> Seq("applicant_individual_failure_url", "sole_trader_failure_url"),
    "individual_identity_url" -> Seq("applicant_individual_identity_url", "sole_trader_identity_url"),
    "individual_dob_url" -> Seq("applicant_individual_dob_url", "sole_trader_dob_url"),
    "individual_nino_url" -> Seq("applicant_individual_nino_url", "sole_trader_nino_url"),
    "individual_sautr_url" -> Seq("applicant_individual_sautr_url", "sole_trader_sautr_url"),
    "individual_check_your_answers_url" -> Seq("applicant_individual_check_your_answers_url", "sole_trader_check_your_answers_url")
  )

  private def sessionString(session: Session, key: String): Option[String] =
    session.attributes.get(key).collect { case value: String if value.nonEmpty => value }

  private def urlFromSession(session: Session, key: String): String =
    absoluteUrl(
      sessionString(session, key)
        .orElse(feederKeyAliases.getOrElse(key, Seq.empty).iterator.flatMap(alias => sessionString(session, alias)).toSeq.headOption)
        .getOrElse(session(key).as[String])
    )

  private def getPage(
    requestName: String,
    urlKey: String,
    expectedMarker: String,
    csrfKey: Option[String] = None,
    actionKey: Option[String] = None
  ): HttpRequestBuilder = {
    var builder =
      http(requestName)
        .get(session => debugUrl(s"$requestName URL", urlFromSession(session, urlKey)))
        .check(status.is(200))
        .check(substring(expectedMarker).exists)

    csrfKey.foreach { key =>
      builder = builder.check(css("input[name=csrfToken]", "value").saveAs(key))
    }

    actionKey.foreach { key =>
      builder = builder.check(bodyString.transform(extractFirstFormAction).saveAs(key))
    }

    builder
  }

  private def getPageNoMarker(
    requestName: String,
    urlKey: String,
    csrfKey: Option[String] = None,
    actionKey: Option[String] = None
  ): HttpRequestBuilder = {
    var builder =
      http(requestName)
        .get(session => debugUrl(s"$requestName URL", urlFromSession(session, urlKey)))
        .check(status.is(200))

    csrfKey.foreach { key =>
      builder = builder.check(css("input[name=csrfToken]", "value").saveAs(key))
    }

    actionKey.foreach { key =>
      builder = builder.check(bodyString.transform(extractFirstFormAction).saveAs(key))
    }

    builder
  }

  private def getRedirectPage(
    requestName: String,
    urlKey: String,
    redirectKey: String
  ): HttpRequestBuilder =
    http(requestName)
      .get(session => debugUrl(s"$requestName URL", urlFromSession(session, urlKey)))
      .disableFollowRedirect
      .check(status.is(303))
      .check(header("Location").transform(normalizeToFrontend).saveAs(redirectKey))

  private def postYesNoForm(
    requestName: String,
    actionKey: String,
    csrfKey: String,
    valueKey: String,
    value: String,
    redirectKey: String
  ): HttpRequestBuilder =
    http(requestName)
      .post(session => debugUrl(s"$requestName URL", absoluteUrl(session(actionKey).as[String])))
      .disableFollowRedirect
      .formParam("csrfToken", s"#{$csrfKey}")
      .formParam(valueKey, value)
      .check(status.is(303))
      .check(header("Location").transform(normalizeToFrontend).saveAs(redirectKey))

  private def postForm(
    requestName: String,
    actionKey: String,
    csrfKey: String,
    redirectKey: String,
    params: Seq[(String, String)]
  ): HttpRequestBuilder = {
    val base =
      http(requestName)
        .post(session => debugUrl(s"$requestName URL", absoluteUrl(session(actionKey).as[String])))
        .disableFollowRedirect
        .formParam("csrfToken", s"#{$csrfKey}")

    val withParams = params.foldLeft(base) { case (builder, (key, value)) =>
      builder.formParam(key, value)
    }

    withParams
      .check(status.is(303))
      .check(header("Location").transform(normalizeToFrontend).saveAs(redirectKey))
  }

  val getApplicantDirectLogin: HttpRequestBuilder =
    http("Applicant Direct Login")
      .get(session => debugUrl("Applicant Direct Login URL", session("applicant_login_url").as[String]))
      .disableFollowRedirect
      .check(status.is(303))
      .check(header("Location").transform(normalizeToFrontend).saveAs("authenticatedApplicantStartUrl"))

  val getApplicantTaskListPage: HttpRequestBuilder =
    getPage("Applicant Fixable Task List", "authenticatedApplicantStartUrl", "Take action")

  val getApplicantEntityFailurePage: HttpRequestBuilder =
    getPage(
      requestName = "Applicant Entity Failure Page",
      urlKey = "entity_failure_url",
      expectedMarker = "Issues with",
      csrfKey = Some("applicantPrimaryFailureCsrfToken"),
      actionKey = Some("applicantPrimaryFailureFormAction")
    )

  val postApplicantEntityFailureFixedYes: HttpRequestBuilder =
    postYesNoForm(
      requestName = "Applicant Entity Failure Fixed Yes",
      actionKey = "applicantPrimaryFailureFormAction",
      csrfKey = "applicantPrimaryFailureCsrfToken",
      valueKey = "isFixed",
      value = "Yes",
      redirectKey = "applicantTaskListAfterEntityFailureUrl"
    )

  val getApplicantTaskListAfterEntityFailure: HttpRequestBuilder =
    getPage("Applicant Task List After Entity Failure", "applicantTaskListAfterEntityFailureUrl", "Take action")

  val getApplicantAmlsFailurePage: HttpRequestBuilder =
    getPage("Applicant AMLS Failure Page", "amls_failure_url", "Anti-money laundering supervision details")

  val getApplicantAmlsSupervisorPage: HttpRequestBuilder =
    getPage(
      requestName = "Applicant AMLS Supervisor Page",
      urlKey = "amls_supervisor_url",
      expectedMarker = "What is the name of the supervisory body",
      csrfKey = Some("applicantAmlsSupervisorCsrfToken"),
      actionKey = Some("applicantAmlsSupervisorFormAction")
    )

  val postApplicantAmlsSupervisorHmrc: HttpRequestBuilder =
    postForm(
      requestName = "Applicant AMLS Supervisor HMRC",
      actionKey = "applicantAmlsSupervisorFormAction",
      csrfKey = "applicantAmlsSupervisorCsrfToken",
      redirectKey = "applicantAmlsRegistrationRedirectUrl",
      params = Seq(
        "amlsSupervisoryBody" -> "HMRC",
        "submit"             -> "SaveAndContinue"
      )
    )

  val getApplicantAmlsRegistrationPage: HttpRequestBuilder =
    getPage(
      requestName = "Applicant AMLS Registration Page",
      urlKey = "applicantAmlsRegistrationRedirectUrl",
      expectedMarker = "What is the registration number?",
      csrfKey = Some("applicantAmlsRegistrationCsrfToken"),
      actionKey = Some("applicantAmlsRegistrationFormAction")
    )

  val postApplicantAmlsRegistrationNumber: HttpRequestBuilder =
    postForm(
      requestName = "Applicant AMLS Registration Number",
      actionKey = "applicantAmlsRegistrationFormAction",
      csrfKey = "applicantAmlsRegistrationCsrfToken",
      redirectKey = "applicantAmlsCheckYourAnswersUrl",
      params = Seq(
        "amlsRegistrationNumber" -> hmrcAmlsRegistrationNumber,
        "submit"                 -> "SaveAndContinue"
      )
    )

  val getApplicantAmlsEvidenceRedirect: HttpRequestBuilder =
    getRedirectPage("Applicant AMLS Evidence Redirect", "amls_evidence_url", "applicantAmlsCheckYourAnswersUrlFromEvidence")

  val getApplicantAmlsCheckYourAnswersPage: HttpRequestBuilder =
    getPageNoMarker(
      requestName = "Applicant AMLS Check Your Answers Page",
      urlKey = "applicantAmlsCheckYourAnswersUrlFromEvidence",
      csrfKey = Some("applicantAmlsCyaCsrfToken"),
      actionKey = Some("applicantAmlsCyaFormAction")
    )

  val postApplicantAmlsCheckYourAnswers: HttpRequestBuilder =
    postForm(
      requestName = "Applicant AMLS Check Your Answers",
      actionKey = "applicantAmlsCyaFormAction",
      csrfKey = "applicantAmlsCyaCsrfToken",
      redirectKey = "applicantTaskListAfterAmlsUrl",
      params = Seq("submit" -> "SaveAndContinue")
    )

  val getApplicantTaskListAfterAmls: HttpRequestBuilder =
    getPage("Applicant Task List After AMLS", "applicantTaskListAfterAmlsUrl", "Take action")

  val getApplicantSoleTraderFailurePage: HttpRequestBuilder =
    getPage(
      requestName = "Applicant Sole Trader Failure Page",
      urlKey = "individual_failure_url",
      expectedMarker = "Issues with your sole trader business",
      csrfKey = Some("applicantSoleTraderFailureCsrfToken"),
      actionKey = Some("applicantSoleTraderFailureFormAction")
    )

  val postApplicantSoleTraderFailureFixedYes: HttpRequestBuilder =
    postYesNoForm(
      requestName = "Applicant Sole Trader Failure Fixed Yes",
      actionKey = "applicantSoleTraderFailureFormAction",
      csrfKey = "applicantSoleTraderFailureCsrfToken",
      valueKey = "isFixed",
      value = "Yes",
      redirectKey = "applicantTaskListAfterSoleTraderFailureUrl"
    )

  val getApplicantTaskListAfterSoleTraderFailure: HttpRequestBuilder =
    getPage("Applicant Task List After Sole Trader Failure", "applicantTaskListAfterSoleTraderFailureUrl", "Take action")

  val getApplicantSoleTraderIdentityPage: HttpRequestBuilder =
    getPageNoMarker("Applicant Sole Trader Identity Page", "individual_identity_url")

  val getApplicantSoleTraderDobPage: HttpRequestBuilder =
    getPageNoMarker(
      requestName = "Applicant Sole Trader Date Of Birth Page",
      urlKey = "individual_dob_url",
      csrfKey = Some("applicantSoleTraderDobCsrfToken"),
      actionKey = Some("applicantSoleTraderDobFormAction")
    )

  val postApplicantSoleTraderDob: HttpRequestBuilder =
    postForm(
      requestName = "Applicant Sole Trader Date Of Birth",
      actionKey = "applicantSoleTraderDobFormAction",
      csrfKey = "applicantSoleTraderDobCsrfToken",
      redirectKey = "applicantSoleTraderDobRedirectUrl",
      params = Seq(
        "dateOfBirth.day"   -> "24",
        "dateOfBirth.month" -> "04",
        "dateOfBirth.year"  -> "2006",
        "submit"            -> "SaveAndContinue"
      )
    )

  val getApplicantSoleTraderNinoPage: HttpRequestBuilder =
    getPage(
      requestName = "Applicant Sole Trader Nino Page",
      urlKey = "individual_nino_url",
      expectedMarker = "Do you have a National Insurance number?",
      csrfKey = Some("applicantSoleTraderNinoCsrfToken"),
      actionKey = Some("applicantSoleTraderNinoFormAction")
    )

  val postApplicantSoleTraderNino: HttpRequestBuilder =
    postForm(
      requestName = "Applicant Sole Trader Nino",
      actionKey = "applicantSoleTraderNinoFormAction",
      csrfKey = "applicantSoleTraderNinoCsrfToken",
      redirectKey = "applicantSoleTraderNinoRedirectUrl",
      params = Seq(
        "individualNino.hasNino" -> "Yes",
        "individualNino.nino"    -> "AA123456A",
        "submit"                 -> "SaveAndContinue"
      )
    )

  val getApplicantSoleTraderSaUtrPage: HttpRequestBuilder =
    getPage(
      requestName = "Applicant Sole Trader SA UTR Page",
      urlKey = "individual_sautr_url",
      expectedMarker = "Do you have a Self Assessment Unique Taxpayer Reference?",
      csrfKey = Some("applicantSoleTraderSaUtrCsrfToken"),
      actionKey = Some("applicantSoleTraderSaUtrFormAction")
    )

  val postApplicantSoleTraderSaUtr: HttpRequestBuilder =
    postForm(
      requestName = "Applicant Sole Trader SA UTR",
      actionKey = "applicantSoleTraderSaUtrFormAction",
      csrfKey = "applicantSoleTraderSaUtrCsrfToken",
      redirectKey = "applicantSoleTraderSaUtrRedirectUrl",
      params = Seq(
        "individualSaUtr.hasSaUtr" -> "Yes",
        "individualSaUtr.saUtr"    -> "1234567890",
        "submit"                   -> "SaveAndContinue"
      )
    )

  val getApplicantSoleTraderCheckYourAnswersPage: HttpRequestBuilder =
    getPageNoMarker(
      requestName = "Applicant Sole Trader Check Your Answers Page",
      urlKey = "individual_check_your_answers_url",
      csrfKey = Some("applicantSoleTraderCyaCsrfToken"),
      actionKey = Some("applicantSoleTraderCyaFormAction")
    )

  val postApplicantSoleTraderCheckYourAnswers: HttpRequestBuilder =
    postForm(
      requestName = "Applicant Sole Trader Check Your Answers",
      actionKey = "applicantSoleTraderCyaFormAction",
      csrfKey = "applicantSoleTraderCyaCsrfToken",
      redirectKey = "applicantTaskListAfterSoleTraderIdentityUrl",
      params = Seq("submit" -> "SaveAndContinue")
    )

  val getApplicantTaskListAfterSoleTraderIdentity: HttpRequestBuilder =
    getPage("Applicant Task List After Sole Trader Identity", "applicantTaskListAfterSoleTraderIdentityUrl", "Take action")

  val getApplicantDeclarationPage: HttpRequestBuilder =
    getPage(
      requestName = "Applicant Fixable Declaration Page",
      urlKey = "declaration_url",
      expectedMarker = "Declaration",
      csrfKey = Some("applicantDeclarationCsrfToken"),
      actionKey = Some("applicantDeclarationFormAction")
    )

  val postApplicantDeclarationAcceptAndSend: HttpRequestBuilder =
    postForm(
      requestName = "Applicant Fixable Declaration Accept And Send",
      actionKey = "applicantDeclarationFormAction",
      csrfKey = "applicantDeclarationCsrfToken",
      redirectKey = "applicantStatusRedirectUrl",
      params = Seq("submit" -> "AcceptAndSend")
    )

  val getApplicantResubmittedStatusPage: HttpRequestBuilder =
    getPage(
      requestName = "Applicant Resubmitted Status Page",
      urlKey = "applicantStatusRedirectUrl",
      expectedMarker = "You have resubmitted your application"
    )

  val getApplicantPreResubmittedStatusPage: HttpRequestBuilder =
    getPage(
      requestName = "Applicant Pre-Resubmitted Status Page",
      urlKey = "authenticatedApplicantStartUrl",
      expectedMarker = "You have resubmitted your application"
    )

  val getIndividualDirectLogin: HttpRequestBuilder =
    http("Individual Direct Login")
      .get(session => debugUrl("Individual Direct Login URL", session("individual_login_url").as[String]))
      .disableFollowRedirect
      .check(status.is(303))
      .check(header("Location").transform(normalizeToFrontend).saveAs("authenticatedIndividualStartUrl"))

  val getIndividualOutcomeFixablePage: HttpRequestBuilder =
    getPage("Individual Outcome Fixable Page", "authenticatedIndividualStartUrl", "You do not meet the registration conditions yet")

  val getIndividualOutcomeApprovedPage: HttpRequestBuilder =
    getPage("Individual Outcome Approved Page", "authenticatedIndividualStartUrl", "You have finished this process")

  val getIndividualOutcomeNonFixablePage: HttpRequestBuilder =
    getPage("Individual Outcome Non-Fixable Page", "authenticatedIndividualStartUrl", "Records show that you do not meet the registration conditions")

  val getIndividualTaskListPage: HttpRequestBuilder =
    getPage("Individual Fixable Task List", "task_list_url", "Take action: You have not met the registration conditions")

  val getIndividualTaskListRedirectToConfirmation: HttpRequestBuilder =
    getRedirectPage("Individual Task List Redirect To Confirmation", "authenticatedIndividualStartUrl", "individualConfirmationRedirectUrl")

  val getIndividualFailureDetailsPage: HttpRequestBuilder =
    getPage(
      requestName = "Individual Failure Details Page",
      urlKey = "failure_details_url",
      expectedMarker = "Save and continue",
      csrfKey = Some("individualFailureCsrfToken"),
      actionKey = Some("individualFailureFormAction")
    )

  val postIndividualFailureFixedYes: HttpRequestBuilder =
    postYesNoForm(
      requestName = "Individual Failure Fixed Yes",
      actionKey = "individualFailureFormAction",
      csrfKey = "individualFailureCsrfToken",
      valueKey = "isFixed",
      value = "Yes",
      redirectKey = "individualTaskListAfterFailureUrl"
    )

  val getIndividualTaskListAfterFailure: HttpRequestBuilder =
    getPage("Individual Task List After Failure", "individualTaskListAfterFailureUrl", "Take action: You have not met the registration conditions")

  val getIndividualIdentityPage: HttpRequestBuilder =
    getPageNoMarker("Individual Identity Page", "identity_url")

  val getIndividualDobPage: HttpRequestBuilder =
    getPageNoMarker(
      requestName = "Individual Date Of Birth Page",
      urlKey = "dob_url",
      csrfKey = Some("individualDobCsrfToken"),
      actionKey = Some("individualDobFormAction")
    )

  val postIndividualDob: HttpRequestBuilder =
    postForm(
      requestName = "Individual Date Of Birth Submit",
      actionKey = "individualDobFormAction",
      csrfKey = "individualDobCsrfToken",
      redirectKey = "individualDobRedirectUrl",
      params = Seq(
        "dateOfBirth.day"   -> "24",
        "dateOfBirth.month" -> "04",
        "dateOfBirth.year"  -> "2006",
        "submit"            -> "SaveAndContinue"
      )
    )

  val getIndividualNinoPage: HttpRequestBuilder =
    getPage(
      requestName = "Individual Nino Page",
      urlKey = "nino_url",
      expectedMarker = "Do you have a National Insurance number?",
      csrfKey = Some("individualNinoCsrfToken"),
      actionKey = Some("individualNinoFormAction")
    )

  val postIndividualNino: HttpRequestBuilder =
    postForm(
      requestName = "Individual Nino Submit",
      actionKey = "individualNinoFormAction",
      csrfKey = "individualNinoCsrfToken",
      redirectKey = "individualNinoRedirectUrl",
      params = Seq(
        "individualNino.hasNino" -> "Yes",
        "individualNino.nino"    -> "AA123456A",
        "submit"                 -> "SaveAndContinue"
      )
    )

  val getIndividualSaUtrPage: HttpRequestBuilder =
    getPage(
      requestName = "Individual SA UTR Page",
      urlKey = "sautr_url",
      expectedMarker = "Do you have a Self Assessment Unique Taxpayer Reference?",
      csrfKey = Some("individualSaUtrCsrfTokenRiskOutcome"),
      actionKey = Some("individualSaUtrFormActionRiskOutcome")
    )

  val postIndividualSaUtr: HttpRequestBuilder =
    postForm(
      requestName = "Individual SA UTR Submit",
      actionKey = "individualSaUtrFormActionRiskOutcome",
      csrfKey = "individualSaUtrCsrfTokenRiskOutcome",
      redirectKey = "individualSaUtrRedirectUrl",
      params = Seq(
        "individualSaUtr.hasSaUtr" -> "Yes",
        "individualSaUtr.saUtr"    -> "1234567890",
        "submit"                   -> "SaveAndContinue"
      )
    )

  val getIndividualCheckYourAnswersPage: HttpRequestBuilder =
    getPageNoMarker(
      requestName = "Individual Check Your Answers Page",
      urlKey = "check_your_answers_url",
      csrfKey = Some("individualCyaCsrfToken"),
      actionKey = Some("individualCyaFormAction")
    )

  val postIndividualCheckYourAnswers: HttpRequestBuilder =
    postForm(
      requestName = "Individual Check Your Answers Submit",
      actionKey = "individualCyaFormAction",
      csrfKey = "individualCyaCsrfToken",
      redirectKey = "individualTaskListAfterDetailsUrl",
      params = Seq("submit" -> "SaveAndContinue")
    )

  val getIndividualTaskListAfterDetails: HttpRequestBuilder =
    getPage("Individual Task List After Details", "individualTaskListAfterDetailsUrl", "Take action: You have not met the registration conditions")

  val getIndividualDeclarationPage: HttpRequestBuilder =
    getPage(
      requestName = "Individual Declaration Page",
      urlKey = "declaration_url",
      expectedMarker = "You are about to submit your responses",
      csrfKey = Some("individualDeclarationCsrfToken"),
      actionKey = Some("individualDeclarationFormAction")
    )

  val postIndividualDeclarationAcceptAndSend: HttpRequestBuilder =
    postForm(
      requestName = "Individual Declaration Accept And Send",
      actionKey = "individualDeclarationFormAction",
      csrfKey = "individualDeclarationCsrfToken",
      redirectKey = "individualConfirmationRedirectUrl",
      params = Seq("submit" -> "AcceptAndSend")
    )

  val getIndividualConfirmationPage: HttpRequestBuilder =
    getPageNoMarker("Individual Confirmation Page", "individualConfirmationRedirectUrl")
      .check(substring("You have finished this process").exists)
}



