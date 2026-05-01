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

import uk.gov.hmrc.performance.simulation.PerformanceTestRunner
import uk.gov.hmrc.perftests.mmtar.AgentRegistrationRequests._

class AgentRegistrationSimulation extends PerformanceTestRunner {

  setup("select-agent-type", "Select Agent Type") withRequests (
    getAgentTypePage,
    postAgentTypeYes
  )

  setup("select-business-type", "Select Business Type") withRequests (
    getBusinessTypePage,
    postBusinessTypeSoleTrader
  )

  setup("select-business-owner", "Select Business Owner") withRequests (
    getBusinessOwnerPage,
    postBusinessOwnerTrue
  )

  setup("sign-in", "Sign In") withRequests (
    getSignInPage,
    postSignIn,
    getSignInInfoPage,
    getContinueToSignIn,
    getStubsSignInPage,
    postStubsSignIn,
    getStubsUserCreatePage,
    postStubsUserCreatePage,
    getStubsUserEditPage,
    postStubsUserEditPage,
    getInitiateAgentApplicationPage,
    getGrsTestDataPage,
    getGrsFormIfNeeded,
    postGrsTestDataPage,
    getTaskListPage
  )

  setup("continue-to-sign-in", "Continue To Sign In") withRequests (
    getSignInInfoPage,
    getContinueToSignIn
  )

  setup("stubs-sign-in", "Stubs Sign In") withRequests (
    getStubsSignInPage,
    postStubsSignIn
  )

  setup("stubs-user-create", "Stubs User Create") withRequests (
    getStubsUserCreatePage,
    postStubsUserCreatePage
  )

  setup("stubs-user-edit", "Stubs User Edit") withRequests (
    getStubsUserEditPage,
    postStubsUserEditPage
  )

  setup("grs-test-data", "GRS Test Data") withRequests (
    getInitiateAgentApplicationPage,
    getGrsTestDataPage,
    getGrsFormIfNeeded,
    postGrsTestDataPage,
    getTaskListPage
  )

   setup("applicant-contact-details", "Applicant Contact Details") withRequests (
     getTaskListPage,
     enterApplicantDetailsFromTaskList,
     followApplicantDetailsInitialRedirect,
     followApplicantDetailsRedirectIfNeeded,
     getApplicantNamePage,
     postApplicantName,
     getTelephoneNumberPage,
     postTelephoneNumber,
     getEmailAddressPage,
     postEmailAddress,
     getApplicantCheckYourAnswersPage,
     goToTaskListFromApplicantCya,
     followTaskListRedirect1,
     followTaskListRedirect2AndExtractAgentDetails,
     followTaskListRedirect3AndExtractAgentDetails
   )

  setup("agent-account-details", "Agent Account Details") withRequests (
    getAgentDetailsCheckYourAnswersPage,
    followAgentDetailsInitialRedirect,
    getBusinessNamePage,
    postBusinessName,
    getAgentTelephoneNumberPage,
    postAgentTelephoneNumber,
    getAgentEmailPage,
    postAgentEmail,
    getAgentCorrespondenceAddressPage,
    postAgentCorrespondenceAddress,
    getAgentCheckYourAnswersPage,
    goToTaskListFromAgentCya,
    followTaskListRedirectAfterAgentCya1,
    followTaskListRedirectAfterAgentCya2,
    followTaskListRedirectAfterAgentCya3
  )

  setup("amls-details", "AMLS Details") withRequests (
    getAmlSupervisorNamePage,
    postAmlSupervisorName,
    getAmlRegistrationNumberPage,
    postAmlRegistrationNumber,
    getAmlCheckYourAnswersPage,
    goToTaskListFromAmlCya,
    followTaskListRedirectAfterAmlCya1,
    followTaskListRedirectAfterAmlCya2,
    followTaskListRedirectAfterAmlCya3
  )

  setup("agent-standards", "Agent Standards") withRequests (
    getAgentStandardAcceptPage,
    postAgentStandardAccept,
    followTaskListRedirectAfterAgentStandard1,
    followTaskListRedirectAfterAgentStandard2,
    followTaskListRedirectAfterAgentStandard3
  )

  setup("list-details", "List Details") withRequests (
    getListDetailsSoleTraderPage,
    postListDetailsSoleTraderContinue,
    followListDetailsSignOutRedirectToSignIn
  )

  setup("prove-identity", "Prove Identity") withRequests (
    getSignInPageAfterListDetails,
    getGgSignInPageAfterListDetails,
    postSignInWithIndividualUser,
    getStubsUserEditPageAfterListDetails,
    postStubsUserCreatePageAfterListDetails,
    getStubsUserEditPageAfterCreate,
    postStubsUserUpdatePageAfterCreate,
    getMatchApplicationPage,
    postConfirmMatchToIndividualProvidedDetailsYes,
    getProvideDetailsCheckYourAnswersAfterMatch,
    getIndividualSaUtrPage,
    postIndividualSaUtrYes,
    getProvideDetailsCheckYourAnswersAfterUtr,
    getUnifiedCustomerRegistryIdentifiers,
    getProvideDetailsCheckYourAnswersAfterUcr,
    getConfirmationPage
  )

  setup("sign-back-in-to-application", "Sign back in to application") withRequests (
    getSignBackIntoApplication,
    getBasGatewaySignInPage,
    followBasGatewaySignInRedirect,
    getFinalBasGatewaySignInPage,
    postBasGatewaySignIn,
    getTaskListAfterFinalSignIn,
  )

  setup("sign-declaration", "Sign Declaration") withRequests (
    getAgentDeclarationPage,
    postAgentDeclarationAcceptAndSend,
    getApplicationStatusPage
  )

  runSimulation()
}
