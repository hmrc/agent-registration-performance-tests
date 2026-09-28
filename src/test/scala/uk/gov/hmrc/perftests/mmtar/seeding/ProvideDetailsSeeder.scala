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

package uk.gov.hmrc.perftests.mmtar.seeding

import java.util.UUID
import scala.util.matching.Regex
import ujson.Obj
import SeederSupport._

object ProvideDetailsSeeder {

  final case class Config(
                           backendUrl: String = "http://localhost:22202",
                           frontendUrl: String = "http://localhost:22201",
                           stubsUrl: String = "http://localhost:9099",
                           apps: Int = 50,
                           individuals: Int = 20,
                           applicationSeedMode: String = "submitted-helper",
                           fastForwardSection: String = "LlpPartnersAndOtherRelevantTaxAdvisers6",
                           output: String = "src/test/resources/data/provide-details-concurrency.csv",
                           cleanupManifest: String = "tmp/performance-test-cleanup/provide-details.json",
                           dryRun: Boolean = false
                         )

  private val FastForwardIdPattern: Regex =
    "/agent-registration/test-only/show-agent-application-tile/([^/?#]+)".r

  def main(args: Array[String]): Unit = {
    val parsed = parseArgs(args)

    val config = Config(
      backendUrl = option(
        parsed,
        "backend-url",
        "http://localhost:22202"
      ).stripSuffix("/"),
      frontendUrl = option(
        parsed,
        "frontend-url",
        "http://localhost:22201"
      ).stripSuffix("/"),
      stubsUrl = option(
        parsed,
        "stubs-url",
        "http://localhost:9099"
      ).stripSuffix("/"),
      apps = intOption(parsed, "apps", 50),
      individuals = intOption(parsed, "individuals", 20),
      applicationSeedMode =
        option(parsed, "application-seed-mode", "submitted-helper"),
      fastForwardSection =
        option(
          parsed,
          "fast-forward-section",
          "LlpPartnersAndOtherRelevantTaxAdvisers6"
        ),
      output =
        option(
          parsed,
          "output",
          "src/test/resources/data/provide-details-concurrency.csv"
        ),
      cleanupManifest =
        option(
          parsed,
          "cleanup-manifest",
          "tmp/performance-test-cleanup/provide-details.json"
        ),
      dryRun = parsed.get("dry-run").contains("true")
    )

    require(
      config.apps > 0,
      "--apps must be greater than 0"
    )

    require(
      config.individuals > 0,
      "--individuals must be greater than 0"
    )

    require(
      Set(
        "submitted-helper",
        "upsert-llp-explicit-officers",
        "frontend-fast-forward"
      ).contains(config.applicationSeedMode),
      s"Unsupported --application-seed-mode: ${config.applicationSeedMode}"
    )

    val effectiveIndividuals =
      if (config.applicationSeedMode == "frontend-fast-forward") {
        6
      } else {
        config.individuals
      }

    println("\nProvide-details concurrency seed")
    println(s"  Applications : ${config.apps}")
    println(
      s"  Individuals  : $effectiveIndividuals per application" +
        (if (config.applicationSeedMode == "frontend-fast-forward") {
          " (from fast-forward preset)"
        } else {
          ""
        })
    )
    println("  Seed state   : precreated")
    println(s"  App seed mode: ${config.applicationSeedMode}")

    if (config.applicationSeedMode == "frontend-fast-forward") {
      println(s"  FF section   : ${config.fastForwardSection}")
    }

    println(s"  Total rows   : ${config.apps * effectiveIndividuals}")
    println(s"  Backend      : ${config.backendUrl}")
    println(s"  Frontend     : ${config.frontendUrl}")
    println(s"  Stubs        : ${config.stubsUrl}")
    println(s"  Output       : ${config.output}")
    println(s"  Cleanup      : ${config.cleanupManifest}")

    if (config.dryRun) {
      println("\nDry run — no API calls made. Remove --dry-run to seed.")
      return
    }

    val http = new SeederHttpClient(30)

    readinessCheck(
      http,
      config.backendUrl
    )

    val seededApplicationIds =
      scala.collection.mutable.ArrayBuffer.from(
        readCleanupManifest(config.cleanupManifest)
      )

    def recordCreatedApplication(appId: String): Unit = {
      if (!seededApplicationIds.contains(appId)) {
        seededApplicationIds += appId

        writeCleanupManifest(
          config.cleanupManifest,
          seededApplicationIds.toSeq
        )

        println(s"      Recorded $appId for cleanup")
      }
    }

    val rows = (1 to config.apps).flatMap { appNum =>
      print(
        f"  [$appNum%4d/${config.apps}] Creating application... "
      )

      val (linkId, appId, individuals) =
        config.applicationSeedMode match {

          case "submitted-helper" =>
            val (linkId, appId) =
              createApplication(
                http,
                config.backendUrl
              )

            recordCreatedApplication(appId)

            (1 to config.individuals).foreach { _ =>
              createIndividual(
                http,
                config.backendUrl,
                appId,
                "Test User",
                UUID.randomUUID().toString
              )
            }

            (
              linkId,
              appId,
              (1 to config.individuals)
                .map(i => Obj("_id" -> s"manual-$i"))
            )

          case "upsert-llp-explicit-officers" =>
            val (linkId, appId) =
              createApplicationViaUpsert(
                http,
                config.backendUrl,
                config.individuals,
                recordCreatedApplication
              )

            (1 to config.individuals).foreach { _ =>
              createIndividual(
                http,
                config.backendUrl,
                appId,
                "Test User",
                UUID.randomUUID().toString
              )
            }

            (
              linkId,
              appId,
              (1 to config.individuals)
                .map(i => Obj("_id" -> s"manual-$i"))
            )

          case "frontend-fast-forward" =>
            val appId =
              createApplicationViaFastForward(
                http,
                config.frontendUrl,
                config.fastForwardSection
              )

            recordCreatedApplication(appId)

            val appPayload =
              findApplication(
                http,
                config.backendUrl,
                appId
              )

            val linkId =
              field(
                appPayload,
                "linkId"
              )

            if (linkId.isEmpty) {
              throw new RuntimeException(
                s"Fast-forward created app $appId but linkId was missing in backend payload"
              )
            }

            val individuals =
              findIndividuals(
                http,
                config.backendUrl,
                appId
              )

            if (individuals.isEmpty) {
              throw new RuntimeException(
                s"Fast-forward created app $appId but returned no individuals"
              )
            }

            (
              linkId,
              appId,
              individuals
            )
        }

      val signInUrl =
        buildSignInUrl(
          config.stubsUrl,
          config.frontendUrl,
          linkId
        )

      val result =
        individuals.zipWithIndex.map {
          case (individual, index) =>
            val individualId =
              field(individual, "_id") match {
                case "" =>
                  s"unknown-${index + 1}"

                case value =>
                  value
              }

            val userId =
              if (config.applicationSeedMode == "frontend-fast-forward") {
                s"individual_$individualId"
              } else {
                s"perf-${UUID.randomUUID().toString.replace("-", "").take(8)}"
              }

            val planetId =
              if (config.applicationSeedMode == "frontend-fast-forward") {
                s"MMTAR_$appId"
              } else {
                s"perf-${UUID.randomUUID().toString.replace("-", "").take(8)}"
              }

            Map(
              "journeyId" ->
                f"app-$appNum%04d-individual-${index + 1}%04d",
              "signInPageUrl" ->
                signInUrl,
              "planetId" ->
                planetId,
              "individualUserId" ->
                userId
            )
        }

      println(
        s"✓  linkId=$linkId  appId=${appId.take(12)}...  (${individuals.size} individuals)"
      )

      result
    }

    writeCsv(
      config.output,
      Seq(
        "journeyId",
        "signInPageUrl",
        "planetId",
        "individualUserId"
      ),
      rows
    )

    println(
      s"\nDone. ${rows.size} rows written to ${config.output}\n"
    )
  }

  private def readinessCheck(
                              http: SeederHttpClient,
                              backendUrl: String
                            ): Unit = {

    val url =
      s"$backendUrl/agent-registration/test-only/recent-applications"

    var lastError: Option[Throwable] = None

    (1 to 5).foreach { attempt =>
      if (lastError.isEmpty && attempt > 1) {
        return
      }

      try {
        requireSuccess(
          http.get(url),
          "Backend readiness check"
        )

        lastError = None
      } catch {
        case error: Throwable =>
          lastError = Some(error)

          if (attempt < 5) {
            Thread.sleep(2000)
          }
      }
    }

    lastError.foreach { error =>
      throw new RuntimeException(
        s"Backend readiness check failed at $url: ${error.getMessage}",
        error
      )
    }
  }

  private def createApplication(
                                 http: SeederHttpClient,
                                 backendUrl: String
                               ): (String, String) = {

    val create =
      requireSuccess(
        http.get(
          s"$backendUrl/agent-registration/test-only/create-submitted-application"
        ),
        "Create submitted application"
      )

    val linkId =
      ujson
        .read(create.body)("linkId")
        .str

    Thread.sleep(300)

    val recent =
      requireSuccess(
        http.get(
          s"$backendUrl/agent-registration/test-only/recent-applications"
        ),
        "Fetch recent applications"
      )

    val matchApp =
      ujson
        .read(recent.body)
        .arr
        .collectFirst {
          case obj: Obj if field(obj, "linkId") == linkId =>
            obj
        }
        .getOrElse(
          throw new RuntimeException(
            s"Could not find application with linkId '$linkId' in recent-applications"
          )
        )

    linkId -> field(
      matchApp,
      "_id"
    )
  }

  private def findApplication(
                               http: SeederHttpClient,
                               backendUrl: String,
                               appId: String
                             ): Obj = {

    val response =
      requireSuccess(
        http.get(
          s"$backendUrl/agent-registration/test-only/application/by-agent-application-id/$appId"
        ),
        s"Find application $appId"
      )

    Obj.from(
      ujson
        .read(response.body)
        .obj
    )
  }

  private def findIndividuals(
                               http: SeederHttpClient,
                               backendUrl: String,
                               appId: String
                             ): Seq[Obj] = {

    val response =
      requireSuccess(
        http.get(
          s"$backendUrl/agent-registration/test-only/individuals/by-agent-application-id/$appId"
        ),
        s"Find individuals for $appId"
      )

    ujson
      .read(response.body)
      .arr
      .map(value => Obj.from(value.obj))
      .toSeq
  }

  private def createApplicationViaUpsert(
                                          http: SeederHttpClient,
                                          backendUrl: String,
                                          individuals: Int,
                                          onApplicationCreated: String => Unit
                                        ): (String, String) = {

    val (linkId, appId) =
      createApplication(
        http,
        backendUrl
      )

    onApplicationCreated(appId)

    val app =
      findApplication(
        http,
        backendUrl,
        appId
      )

    if (field(app, "type") != "AgentApplicationLlp") {
      throw new RuntimeException(
        s"Expected AgentApplicationLlp from helper route but got '${field(app, "type")}'"
      )
    }

    app("numberOfIndividuals") =
      buildExplicitOfficersPayload(individuals)

    requireSuccess(
      http.postJson(
        s"$backendUrl/agent-registration/test-only/application",
        app
      ),
      "Upsert application"
    )

    linkId -> appId
  }

  private def buildExplicitOfficersPayload(
                                            individuals: Int
                                          ): Obj =
    if (individuals <= 5) {
      Obj(
        "type" ->
          "FiveOrLessOfficers",
        "numberOfCompaniesHouseOfficers" ->
          individuals,
        "isCompaniesHouseOfficersListCorrect" ->
          true
      )
    } else {
      Obj(
        "type" ->
          "SixOrMoreOfficers",
        "numberOfCompaniesHouseOfficers" ->
          individuals,
        "numberOfOfficersResponsibleForTaxMatters" ->
          individuals
      )
    }

  private def createIndividual(
                                http: SeederHttpClient,
                                backendUrl: String,
                                appId: String,
                                individualName: String,
                                personReference: String
                              ): Unit = {

    val payload =
      Obj(
        "_id" ->
          UUID.randomUUID().toString,
        "personReference" ->
          personReference,
        "individualName" ->
          individualName,
        "isPersonOfControl" ->
          true,
        "createdAt" ->
          "2059-11-25T16:33:51.880Z",
        "providedDetailsState" ->
          "Precreated",
        "agentApplicationId" ->
          appId
      )

    requireSuccess(
      http.postJson(
        s"$backendUrl/agent-registration/test-only/individual-provided-details",
        payload
      ),
      s"Create individual for $appId"
    )
  }

  private def createApplicationViaFastForward(
                                               http: SeederHttpClient,
                                               frontendUrl: String,
                                               completedSection: String
                                             ): String = {

    val slug =
      completedSection
        .replaceAll(
          "([A-Z])",
          "-$1"
        )
        .toLowerCase
        .stripPrefix("-")

    val url =
      s"$frontendUrl/agent-registration/test-only/fast-forward-to/$slug"

    val response =
      requireRedirect(
        http.get(url),
        "Fast-forward application"
      )

    val location =
      response
        .header("Location")
        .getOrElse(
          throw new RuntimeException(
            s"Missing Location header from $url"
          )
        )

    FastForwardIdPattern
      .findFirstMatchIn(location)
      .map(_.group(1))
      .getOrElse(
        throw new RuntimeException(
          s"Could not extract agentApplicationId from fast-forward redirect location: '$location'"
        )
      )
  }

  private def buildSignInUrl(
                              stubsUrl: String,
                              frontendUrl: String,
                              linkId: String
                            ): String = {

    val continueUrl =
      s"$frontendUrl/agent-registration/provide-details/match-application/$linkId"

    s"$stubsUrl/bas-gateway/sign-in?continue_url=${urlEncode(continueUrl)}&origin=agent-registration-frontend"
  }
}