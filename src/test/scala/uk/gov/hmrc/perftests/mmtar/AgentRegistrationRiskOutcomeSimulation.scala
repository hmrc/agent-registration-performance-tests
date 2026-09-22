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

import com.typesafe.config.ConfigFactory
import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import uk.gov.hmrc.perftests.mmtar.RiskOutcomeRequests._

import scala.concurrent.duration._

class AgentRegistrationRiskOutcomeSimulation extends Simulation {

  private case class ScenarioDefinition(
    id: String,
    defaultWeight: Double,
    chain: ChainBuilder
  )

  private val config = ConfigFactory.load()

  private def stringSetting(propertyName: String, configPath: String): String =
    sys.props.get(propertyName).filter(_.nonEmpty).getOrElse(config.getString(configPath))

  private def doubleSetting(propertyName: String, configPath: String): Double =
    sys.props.get(propertyName).flatMap(value => scala.util.Try(value.toDouble).toOption).getOrElse(config.getDouble(configPath))

  private def intSetting(propertyName: String, configPath: String): Int =
    sys.props.get(propertyName).flatMap(value => scala.util.Try(value.toInt).toOption).getOrElse(config.getInt(configPath))

  private val feederPool        = stringSetting("riskOutcome.pool", "riskOutcome.pool")
  private val totalPeakJps      = doubleSetting("riskOutcome.totalPeakJps", "riskOutcome.totalPeakJps")
  private val rampUpMinutes     = intSetting("riskOutcome.rampUpMinutes", "riskOutcome.rampUpMinutes")
  private val steadyMinutes     = intSetting("riskOutcome.steadyMinutes", "riskOutcome.steadyMinutes")
  private val rampDownMinutes   = intSetting("riskOutcome.rampDownMinutes", "riskOutcome.rampDownMinutes")
  private val failureThreshold  = doubleSetting("riskOutcome.failurePercentageThreshold", "riskOutcome.failurePercentageThreshold")

  private val httpProtocol = http.disableFollowRedirect

  private def feederFor(scenarioId: String) =
    csv(s"data/risk-outcomes/$feederPool/$scenarioId.csv").queue

  private def weightFor(scenarioId: String, defaultWeight: Double): Double =
    sys.props
      .get(s"riskOutcome.weight.$scenarioId")
      .flatMap(value => scala.util.Try(value.toDouble).toOption)
      .getOrElse(defaultWeight)

  private def validateStartUrl(expectedKey: String, actualKey: String): ChainBuilder =
    exec { session =>
      val expected = session(expectedKey).as[String]
      val actual   = session(actualKey).as[String]

      if (expected == actual) session
      else session.markAsFailed.set("riskOutcomeStartUrlMismatch", s"expected=$expected actual=$actual")
    }

  private val applicantEntityResubmissionChain: ChainBuilder =
    exitBlockOnFail(
      exec(
        getApplicantDirectLogin,
        validateStartUrl("applicant_start_url", "authenticatedApplicantStartUrl"),
        getApplicantTaskListPage,
        getApplicantEntityFailurePage,
        postApplicantEntityFailureFixedYes,
        getApplicantTaskListAfterEntityFailure,
        getApplicantDeclarationPage,
        postApplicantDeclarationAcceptAndSend,
        getApplicantResubmittedStatusPage
      )
    )

  private val applicantAmlsResubmissionChain: ChainBuilder =
    exitBlockOnFail(
      exec(
        getApplicantDirectLogin,
        validateStartUrl("applicant_start_url", "authenticatedApplicantStartUrl"),
        getApplicantTaskListPage,
        getApplicantAmlsFailurePage,
        getApplicantAmlsSupervisorPage,
        postApplicantAmlsSupervisorHmrc,
        getApplicantAmlsRegistrationPage,
        postApplicantAmlsRegistrationNumber,
        getApplicantAmlsEvidenceRedirect,
        getApplicantAmlsCheckYourAnswersPage,
        postApplicantAmlsCheckYourAnswers,
        getApplicantTaskListAfterAmls,
        getApplicantDeclarationPage,
        postApplicantDeclarationAcceptAndSend,
        getApplicantResubmittedStatusPage
      )
    )

  private val applicantSoleTraderResubmissionChain: ChainBuilder =
    exitBlockOnFail(
      exec(
        getApplicantDirectLogin,
        validateStartUrl("applicant_start_url", "authenticatedApplicantStartUrl"),
        getApplicantTaskListPage,
        getApplicantSoleTraderFailurePage,
        postApplicantSoleTraderFailureFixedYes,
        getApplicantTaskListAfterSoleTraderFailure,
        getApplicantSoleTraderIdentityPage,
        getApplicantSoleTraderDobPage,
        postApplicantSoleTraderDob,
        getApplicantSoleTraderNinoPage,
        postApplicantSoleTraderNino,
        getApplicantSoleTraderSaUtrPage,
        postApplicantSoleTraderSaUtr,
        getApplicantSoleTraderCheckYourAnswersPage,
        postApplicantSoleTraderCheckYourAnswers,
        getApplicantTaskListAfterSoleTraderIdentity,
        getApplicantDeclarationPage,
        postApplicantDeclarationAcceptAndSend,
        getApplicantResubmittedStatusPage
      )
    )

  private val applicantResubmittedStatusChain: ChainBuilder =
    exitBlockOnFail(
      exec(
        getApplicantDirectLogin,
        validateStartUrl("applicant_start_url", "authenticatedApplicantStartUrl"),
        getApplicantPreResubmittedStatusPage
      )
    )

  private val individualFixConfirmOnlyChain: ChainBuilder =
    exitBlockOnFail(
      exec(
        getIndividualDirectLogin,
        validateStartUrl("individual_start_url", "authenticatedIndividualStartUrl"),
        getIndividualOutcomeFixablePage,
        getIndividualTaskListPage,
        getIndividualFailureDetailsPage,
        postIndividualFailureFixedYes,
        getIndividualTaskListAfterFailure,
        getIndividualDeclarationPage,
        postIndividualDeclarationAcceptAndSend,
        getIndividualConfirmationPage
      )
    )

  private val individualFixDetailsChain: ChainBuilder =
    exitBlockOnFail(
      exec(
        getIndividualDirectLogin,
        validateStartUrl("individual_start_url", "authenticatedIndividualStartUrl"),
        getIndividualOutcomeFixablePage,
        getIndividualTaskListPage,
        getIndividualFailureDetailsPage,
        postIndividualFailureFixedYes,
        getIndividualTaskListAfterFailure,
        getIndividualIdentityPage,
        getIndividualDobPage,
        postIndividualDob,
        getIndividualNinoPage,
        postIndividualNino,
        getIndividualSaUtrPage,
        postIndividualSaUtr,
        getIndividualCheckYourAnswersPage,
        postIndividualCheckYourAnswers,
        getIndividualTaskListAfterDetails,
        getIndividualDeclarationPage,
        postIndividualDeclarationAcceptAndSend,
        getIndividualConfirmationPage
      )
    )

  private val individualAlreadyConfirmedChain: ChainBuilder =
    exitBlockOnFail(
      exec(
        getIndividualDirectLogin,
        validateStartUrl("individual_start_url", "authenticatedIndividualStartUrl"),
        getIndividualTaskListRedirectToConfirmation,
        getIndividualConfirmationPage
      )
    )

  private val individualNonFixableControlChain: ChainBuilder =
    exitBlockOnFail(
      exec(
        getIndividualDirectLogin,
        validateStartUrl("individual_start_url", "authenticatedIndividualStartUrl"),
        getIndividualOutcomeNonFixablePage
      )
    )

  private val individualApprovedControlChain: ChainBuilder =
    exitBlockOnFail(
      exec(
        getIndividualDirectLogin,
        validateStartUrl("individual_start_url", "authenticatedIndividualStartUrl"),
        getIndividualOutcomeApprovedPage
      )
    )

  private val scenarioDefinitions = Seq(
    ScenarioDefinition("APP-ST-FIX-RESUB", 8, applicantSoleTraderResubmissionChain),
    ScenarioDefinition("APP-LTD-FIX-RESUB-2", 6, applicantEntityResubmissionChain),
    ScenarioDefinition("APP-LLP-FIX-RESUB-2", 6, applicantEntityResubmissionChain),
    ScenarioDefinition("APP-GP-FIX-RESUB-2", 5, applicantEntityResubmissionChain),
    ScenarioDefinition("APP-SP-FIX-RESUB-2", 5, applicantEntityResubmissionChain),
    ScenarioDefinition("APP-LP-FIX-RESUB-2", 5, applicantEntityResubmissionChain),
    ScenarioDefinition("APP-SLP-FIX-RESUB-2", 5, applicantEntityResubmissionChain),
    ScenarioDefinition("APP-AMLS-FIX-RESUB", 4, applicantAmlsResubmissionChain),
    ScenarioDefinition("APP-RESUBMITTED-STATUS", 1, applicantResubmittedStatusChain),
    ScenarioDefinition("IND-FIX-DETAILS", 8, individualFixDetailsChain),
    ScenarioDefinition("IND-FIX-CONFIRM-ONLY", 6, individualFixConfirmOnlyChain),
    ScenarioDefinition("IND-FIX-ALREADY-CONFIRMED", 1, individualAlreadyConfirmedChain),
    ScenarioDefinition("IND-NONFIXABLE-CONTROL", 1, individualNonFixableControlChain),
    ScenarioDefinition("IND-APPROVED-CONTROL", 1, individualApprovedControlChain),
    ScenarioDefinition("APP-LTD-FIX-RESUB-6", 3, applicantEntityResubmissionChain),
    ScenarioDefinition("APP-LLP-FIX-RESUB-6", 3, applicantEntityResubmissionChain),
    ScenarioDefinition("APP-GP-FIX-RESUB-6", 2, applicantEntityResubmissionChain)
  )

  private val enabledScenarios = {
    val withEffectiveWeights = scenarioDefinitions.map(definition => definition -> weightFor(definition.id, definition.defaultWeight))
    val filtered             = withEffectiveWeights.filter(_._2 > 0)
    require(filtered.nonEmpty, "At least one risk-outcome scenario must have a positive weight")
    filtered
  }

  private val totalWeight = enabledScenarios.map(_._2).sum

  private def peakRate(weight: Double): Double =
    totalPeakJps * weight / totalWeight

  private val injections = enabledScenarios.map { case (definition, effectiveWeight) =>
    val scenarioPeakRate = peakRate(effectiveWeight)

    scenario(definition.id)
      .feed(feederFor(definition.id))
      .exec(definition.chain)
      .inject(
        rampUsersPerSec(0.0).to(scenarioPeakRate).during(rampUpMinutes.minutes),
        constantUsersPerSec(scenarioPeakRate).during(steadyMinutes.minutes),
        rampUsersPerSec(scenarioPeakRate).to(0.0).during(rampDownMinutes.minutes)
      )
  }

  setUp(injections: _*)
    .protocols(httpProtocol)
    .assertions(
      global.failedRequests.percent.lte(failureThreshold)
    )
}


