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

import java.net.{CookieManager, CookiePolicy, URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.time.Duration
import scala.jdk.CollectionConverters._
import scala.util.matching.Regex
import ujson.{Arr, Obj, Value}

final case class SeederResponse(
                                 status: Int,
                                 body: String,
                                 headers: Map[String, Seq[String]],
                                 uri: URI
                               ) {
  def header(name: String): Option[String] =
    headers.collectFirst {
      case (key, values)
        if key.equalsIgnoreCase(name) && values.nonEmpty =>
        values.head
    }
}

final class SeederHttpClient(
                              timeoutSeconds: Long = 30,
                              persistCookies: Boolean = true
                            ) {

  private val clientBuilder =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(timeoutSeconds))
      .followRedirects(HttpClient.Redirect.NEVER)

  private val client =
    if (persistCookies) {
      clientBuilder
        .cookieHandler(
          new CookieManager(
            null,
            CookiePolicy.ACCEPT_ALL
          )
        )
        .build()
    } else {
      clientBuilder.build()
    }

  private def send(
                    request: HttpRequest
                  ): SeederResponse = {

    val response =
      client.send(
        request,
        HttpResponse.BodyHandlers.ofString(
          StandardCharsets.UTF_8
        )
      )

    SeederResponse(
      status = response.statusCode(),
      body = response.body(),
      headers =
        response
          .headers()
          .map()
          .asScala
          .view
          .mapValues(_.asScala.toSeq)
          .toMap,
      uri = response.uri()
    )
  }

  private def request(
                       url: String
                     ): HttpRequest.Builder =
    HttpRequest
      .newBuilder(
        URI.create(url)
      )
      .timeout(
        Duration.ofSeconds(timeoutSeconds)
      )

  def get(
           url: String
         ): SeederResponse =
    send(
      request(url)
        .GET()
        .build()
    )

  def postJson(
                url: String,
                json: Value
              ): SeederResponse =
    send(
      request(url)
        .header(
          "Content-Type",
          "application/json"
        )
        .POST(
          HttpRequest.BodyPublishers.ofString(
            ujson.write(json),
            StandardCharsets.UTF_8
          )
        )
        .build()
    )

  def postEmpty(
                 url: String
               ): SeederResponse =
    send(
      request(url)
        .POST(
          HttpRequest.BodyPublishers.noBody()
        )
        .build()
    )

  def postForm(
                url: String,
                data: Seq[(String, String)]
              ): SeederResponse = {

    val encoded =
      data
        .map {
          case (key, value) =>
            s"${SeederSupport.urlEncode(key)}=${SeederSupport.urlEncode(value)}"
        }
        .mkString("&")

    send(
      request(url)
        .header(
          "Content-Type",
          "application/x-www-form-urlencoded"
        )
        .POST(
          HttpRequest.BodyPublishers.ofString(
            encoded,
            StandardCharsets.UTF_8
          )
        )
        .build()
    )
  }

  def followGet(
                 url: String,
                 maxRedirects: Int = 20
               ): SeederResponse = {

    var current = url
    var redirects = 0
    var response = get(current)

    while (
      SeederSupport.isRedirect(response.status) &&
        redirects < maxRedirects
    ) {
      current =
        SeederSupport.resolveLocation(response)

      response =
        get(current)

      redirects += 1
    }

    if (
      redirects >= maxRedirects &&
        SeederSupport.isRedirect(response.status)
    ) {
      throw new RuntimeException(
        s"Too many redirects while loading $url"
      )
    }

    response
  }
}

object SeederSupport {

  private val CsrfInputPattern: Regex =
    """(?is)<input\b[^>]*\bname\s*=\s*["']csrfToken["'][^>]*>""".r

  private val ValueAttributePattern: Regex =
    """(?is)\bvalue\s*=\s*["']([^"']+)["']""".r

  private val FormActionPattern: Regex =
    """<form[^>]*action=["']([^"']+)""".r

  def requireSuccess(
                      response: SeederResponse,
                      context: String
                    ): SeederResponse = {

    if (
      response.status < 200 ||
        response.status >= 400
    ) {
      throw new RuntimeException(
        s"$context failed with HTTP ${response.status} at ${response.uri}: ${response.body.take(500)}"
      )
    }

    response
  }

  def requireRedirect(
                       response: SeederResponse,
                       context: String
                     ): SeederResponse = {

    if (!isRedirect(response.status)) {
      throw new RuntimeException(
        s"$context expected HTTP 302/303 but got ${response.status} at ${response.uri}: ${response.body.take(500)}"
      )
    }

    response
  }

  def isRedirect(
                  status: Int
                ): Boolean =
    status == 302 || status == 303

  def urlEncode(
                 value: String
               ): String =
    URLEncoder
      .encode(
        value,
        StandardCharsets.UTF_8
      )
      .replace(
        "+",
        "%20"
      )

  def resolveLocation(
                       response: SeederResponse
                     ): String = {

    val requestUrl =
      response.uri.toString

    val location =
      response
        .header("Location")
        .getOrElse(
          throw new RuntimeException(
            s"Missing Location header from $requestUrl"
          )
        )

    response
      .uri
      .resolve(
        htmlUnescape(location)
      )
      .toString
  }

  def htmlUnescape(
                    value: String
                  ): String =
    value
      .replace(
        "&amp;",
        "&"
      )
      .replace(
        "&#x27;",
        "'"
      )
      .replace(
        "&quot;",
        "\""
      )

  def extractCsrf(
                   body: String
                 ): String =
    CsrfInputPattern
      .findFirstIn(body)
      .flatMap { input =>
        ValueAttributePattern
          .findFirstMatchIn(input)
          .map(_.group(1))
      }
      .getOrElse(
        throw new RuntimeException(
          "Could not extract csrfToken from page"
        )
      )

  def extractFormAction(
                         body: String,
                         currentUrl: String
                       ): String =
    FormActionPattern
      .findFirstMatchIn(body)
      .map { matched =>
        URI
          .create(currentUrl)
          .resolve(
            htmlUnescape(
              matched.group(1)
            )
          )
          .toString
      }
      .getOrElse(
        throw new RuntimeException(
          s"Could not extract form action from $currentUrl"
        )
      )

  def ensureContains(
                      response: SeederResponse,
                      marker: String,
                      description: String
                    ): Unit =
    if (!response.body.contains(marker)) {
      throw new RuntimeException(
        s"Unexpected content for $description at ${response.uri}. Expected marker: '$marker'"
      )
    }

  def unwrap(
              value: Value
            ): String =
    value match {

      case ujson.Str(value) =>
        value

      case obj: ujson.Obj
        if obj.value
          .get("value")
          .exists(_.isInstanceOf[ujson.Str]) =>
        obj("value").str

      case ujson.Null =>
        ""

      case other =>
        other.toString
    }

  def field(
             obj: ujson.Obj,
             name: String
           ): String =
    obj.value
      .get(name)
      .map(unwrap)
      .getOrElse("")

  def csvEscape(
                 value: String
               ): String = {

    val safe =
      Option(value)
        .getOrElse("")

    if (
      safe.exists { char =>
        char == ',' ||
          char == '"' ||
          char == '\n' ||
          char == '\r'
      }
    ) {
      s""""${safe.replace("\"", "\"\"")}""""
    } else {
      safe
    }
  }

  def writeCsv(
                path: String,
                headers: Seq[String],
                rows: Seq[Map[String, String]]
              ): Unit = {

    val target =
      Paths.get(path)

    Option(target.getParent)
      .foreach { parent =>
        Files.createDirectories(parent)
      }

    val lines =
      (
        headers.mkString(",") +:
          rows.map { row =>
            headers
              .map { key =>
                csvEscape(
                  row.getOrElse(
                    key,
                    ""
                  )
                )
              }
              .mkString(",")
          }
        ).mkString("\n") + "\n"

    Files.writeString(
      target,
      lines,
      StandardCharsets.UTF_8
    )

    ()
  }

  def writeJson(
                 path: String,
                 value: Value
               ): Unit = {

    val target =
      Paths.get(path)

    Option(target.getParent)
      .foreach { parent =>
        Files.createDirectories(parent)
      }

    Files.writeString(
      target,
      ujson.write(
        value,
        indent = 2
      ),
      StandardCharsets.UTF_8
    )

    ()
  }

  def writeCleanupManifest(
                            path: String,
                            applicationIds: Seq[String]
                          ): Unit = {

    val file =
      Paths.get(path)

    Option(file.getParent)
      .foreach { parent =>
        Files.createDirectories(parent)
      }

    val json =
      Obj(
        "agentApplicationIds" ->
          Arr.from(
            applicationIds
              .distinct
              .map(ujson.Str(_))
          )
      )

    Files.writeString(
      file,
      ujson.write(
        json,
        indent = 2
      ),
      StandardCharsets.UTF_8
    )

    ()
  }

  def readCleanupManifest(
                           path: String
                         ): Seq[String] = {

    val file =
      Paths.get(path)

    if (!Files.exists(file)) {
      Seq.empty
    } else {
      val content =
        Files.readString(
          file,
          StandardCharsets.UTF_8
        ).trim

      if (content.isEmpty) {
        Seq.empty
      } else {
        val json =
          ujson.read(content)

        json.obj
          .get("agentApplicationIds")
          .map(
            _.arr
              .map(_.str)
              .toSeq
          )
          .getOrElse(
            Seq.empty
          )
      }
    }
  }

  def waitFor(
               description: String,
               attempts: Int = 60,
               delayMillis: Long = 2000
             )(
               predicate: => Boolean
             ): Unit = {

    var lastError: Option[Throwable] =
      None

    var attempt =
      1

    while (attempt <= attempts) {
      try {
        if (predicate) {
          return
        }

        lastError =
          None

      } catch {
        case error: Throwable =>
          lastError =
            Some(error)
      }

      if (attempt < attempts) {
        Thread.sleep(delayMillis)
      }

      attempt += 1
    }

    lastError match {
      case Some(error) =>
        throw new RuntimeException(
          s"Timed out waiting for $description: ${error.getMessage}",
          error
        )

      case None =>
        throw new RuntimeException(
          s"Timed out waiting for $description"
        )
    }
  }

  def parseArgs(
                 args: Array[String]
               ): Map[String, String] = {

    val builder =
      Map.newBuilder[String, String]

    var i =
      0

    while (i < args.length) {
      val arg =
        args(i)

      if (!arg.startsWith("--")) {
        throw new IllegalArgumentException(
          s"Unexpected argument: $arg"
        )
      }

      val key =
        arg.stripPrefix("--")

      if (key == "dry-run") {
        builder += key -> "true"
        i += 1
      } else {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException(
            s"Missing value for $arg"
          )
        }

        builder += key -> args(i + 1)

        i += 2
      }
    }

    builder.result()
  }

  def option(
              map: Map[String, String],
              key: String,
              default: String
            ): String =
    map.getOrElse(
      key,
      default
    )

  def intOption(
                 map: Map[String, String],
                 key: String,
                 default: Int
               ): Int =
    map
      .get(key)
      .fold(default)(_.toInt)

  def doubleOption(
                    map: Map[String, String],
                    key: String,
                    default: Double
                  ): Double =
    map
      .get(key)
      .fold(default)(_.toDouble)

  def optionalInt(
                   map: Map[String, String],
                   key: String
                 ): Option[Int] =
    map
      .get(key)
      .filter(_.nonEmpty)
      .map(_.toInt)
}