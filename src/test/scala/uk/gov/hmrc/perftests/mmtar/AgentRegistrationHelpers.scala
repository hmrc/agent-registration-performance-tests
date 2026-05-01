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

import uk.gov.hmrc.performance.conf.ServicesConfiguration
import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets
import scala.util.Try

trait AgentRegistrationHelpers extends ServicesConfiguration {

    val baseUrl: String
    val stubsUrl: String

    private val emailVerificationBaseUrl =
      Try(baseUrlFor("email-verification")).getOrElse("http://localhost:9890")

    def decodeQueryValue(value: String): String =
      Try(URLDecoder.decode(value, StandardCharsets.UTF_8.name())).getOrElse(value)

    def extractQueryParams(url: String): Map[String, String] =
      Try(new URI(url)).toOption
        .flatMap(uri => Option(uri.getRawQuery))
        .map(
          _.split("&").toSeq.flatMap {
            case pair if pair.contains("=") =>
              pair.split("=", 2) match {
                case Array(key, value) => Some(decodeQueryValue(key) -> decodeQueryValue(value))
                case _                 => None
              }

            case key if key.nonEmpty =>
              Some(decodeQueryValue(key) -> "")

            case _ =>
              None
          }.toMap
        )
        .getOrElse(Map.empty)

    def originOf(url: String): Option[String] =
      Try(new URI(url)).toOption.flatMap { uri =>
        Option(uri.getScheme).flatMap { scheme =>
          Option(uri.getHost).map { host =>
            val portPart = if (uri.getPort > 0) s":${uri.getPort}" else ""
            s"$scheme://$host$portPart"
          }
        }
      }

    def normalizeLocation(location: String): String = {
      val cleaned = Option(location).map(_.replace("&amp;", "&").trim).getOrElse("")

      if (cleaned.startsWith("http")) cleaned
      else if (cleaned.startsWith("/email-verification/")) s"$emailVerificationBaseUrl$cleaned"
      else s"$baseUrl$cleaned"
    }

    def normalizeSignInLocation(location: String): String = {
      val cleaned = Option(location).map(_.replace("&amp;", "&").trim).getOrElse("")

      if (cleaned.startsWith("http")) cleaned
      else if (
        cleaned.startsWith("/bas-gateway/") ||
          cleaned.startsWith("/gg/") ||
          cleaned.startsWith("/agents-external-stubs/")
      ) s"$stubsUrl$cleaned"
      else if (cleaned.startsWith("/")) s"$baseUrl$cleaned"
      else cleaned
    }

    def normalizeToFrontend(location: String): String = {
      val cleaned = Option(location).map(_.replace("&amp;", "&").trim).getOrElse("")

      if (cleaned.startsWith("http")) cleaned
      else s"$baseUrl$cleaned"
    }

    def extractContinueUrl(url: String): Option[String] = {
      val cleaned = Option(url).map(_.replace("&amp;", "&").trim).getOrElse("")

      extractQueryParams(cleaned)
        .get("continue")
        .orElse(extractQueryParams(cleaned).get("continue_url"))
        .map(normalizeLocation)
    }

    def extractInputValue(body: String, name: String): String = {
      val escapedName = java.util.regex.Pattern.quote(name)

      val regex =
        s"""<input[^>]*name="$escapedName"[^>]*value="([^"]*)"[^>]*>""".r

      regex
        .findFirstMatchIn(body)
        .map(_.group(1).replace("&amp;", "&"))
        .getOrElse("")
    }

    def extractSelectedOptionValue(body: String, selectName: String): String = {
      val escapedName = java.util.regex.Pattern.quote(selectName)

      val selectRegex =
        s"""(?s)<select[^>]*name="$escapedName"[^>]*>(.*?)</select>""".r

      val optionRegex =
        """<option[^>]*value="([^"]*)"[^>]*selected[^>]*>""".r

      selectRegex
        .findFirstMatchIn(body)
        .flatMap(m => optionRegex.findFirstMatchIn(m.group(1)))
        .map(_.group(1))
        .getOrElse("")
    }

    def extractFirstFormAction(body: String): String = {
      val formActionRegex =
        """<form[^>]*action="([^"]+)"""".r

      formActionRegex
        .findFirstMatchIn(body)
        .map(_.group(1).replace("&amp;", "&"))
        .getOrElse("")
    }

    def extractFormActionById(body: String, formIds: Seq[String]): String = {
      val idPattern = formIds.mkString("|")

      val formRegex =
        s"""<form[^>]*(?:action="([^"]+)"[^>]*id="(?:$idPattern)"|id="(?:$idPattern)"[^>]*action="([^"]+)")""".r

      formRegex.findFirstMatchIn(body) match {
        case Some(m) =>
          Option(m.group(1))
            .orElse(Option(m.group(2)))
            .map(_.replace("&amp;", "&"))
            .getOrElse("")

        case None =>
          ""
      }
    }

    def ggSignInUrlFromBasUrl(url: String): Option[String] = {
      val normalized = normalizeSignInLocation(url)
      val params     = extractQueryParams(normalized)

      params.get("continue_url").orElse(params.get("continue")).map { continue =>
        val originParam =
          params.get("origin").filter(_.nonEmpty).map(origin => s"&origin=$origin").getOrElse("")

        s"${originOf(normalized).getOrElse(stubsUrl)}/gg/sign-in?continue=$continue$originParam"
      }
    }

    def debug(message: String): Unit =
      if (sys.props.get("debugRequests").contains("true")) {
        println(message)
      }
  }