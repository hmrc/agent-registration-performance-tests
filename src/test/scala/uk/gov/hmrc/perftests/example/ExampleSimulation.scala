/*
 * Copyright 2023 HM Revenue & Customs
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
import uk.gov.hmrc.perftests.example.ExampleRequests._

class ExampleSimulation extends PerformanceTestRunner {

  setup("home-page", "Home Page") withRequests navigateToHomePage

  setup("post-vat-return-period", "Post vat return period") withRequests postVatReturnPeriod

  setup("get-turnover-page", "Get turnover page") withRequests getTurnoverPage

  runSimulation()
}
