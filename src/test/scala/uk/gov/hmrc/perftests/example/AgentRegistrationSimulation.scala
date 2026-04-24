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

import uk.gov.hmrc.performance.simulation.PerformanceTestRunner
import uk.gov.hmrc.perftests.example.AgentRegistrationRequests._

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
    postSignIn
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
    postGrsTestDataPage
  )

  runSimulation()
}
