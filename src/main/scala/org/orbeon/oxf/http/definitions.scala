/**
 * Copyright (C) 2012 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.http

import java.io.{ByteArrayInputStream, InputStream}

import enumeratum._
import org.apache.commons.io.IOUtils
import org.orbeon.io.IOUtils._
import org.orbeon.oxf.util.StringUtils._

import scala.collection.{immutable ⇒ i}
import scala.concurrent.duration._

trait Content {
  def inputStream   : InputStream
  def contentType   : Option[String]
  def contentLength : Option[Long]
  def title         : Option[String] // this is only for portlet and should be moved out
}

sealed trait StreamedContentOrRedirect
sealed trait BufferedContentOrRedirect

case class StreamedContent(
  inputStream   : InputStream,
  contentType   : Option[String],
  contentLength : Option[Long],
  title         : Option[String]
) extends StreamedContentOrRedirect with Content {
  def close(): Unit = runQuietly(inputStream.close())
}

object StreamedContent {

  def fromBytes(bytes: Array[Byte], contentType: Option[String], title: Option[String] = None) =
    StreamedContent(
      inputStream   = new ByteArrayInputStream(bytes),
      contentType   = contentType,
      contentLength = Some(bytes.size.toLong),
      title         = title
    )

  def fromStreamAndHeaders(inputStream: InputStream, headers: Map[String, i.Seq[String]], title: Option[String] = None) =
    StreamedContent(
      inputStream   = inputStream,
      contentType   = Headers.firstItemIgnoreCase(headers, Headers.ContentType),
      contentLength = Headers.firstLongHeaderIgnoreCase(headers, Headers.ContentLength),
      title         = title
    )

  val Empty =
    StreamedContent(
      inputStream   = EmptyInputStream,
      contentType   = None,
      contentLength = None,
      title         = None
    )
}

case class BufferedContent(
  body        : Array[Byte],
  contentType : Option[String],
  title       : Option[String]
) extends BufferedContentOrRedirect with Content {
  def inputStream   = new ByteArrayInputStream(body)
  def contentLength = Some(body.size)
}

object BufferedContent {
  def apply(content: StreamedContent): BufferedContent =
    BufferedContent(useAndClose(content.inputStream)(IOUtils.toByteArray), content.contentType, content.title)
}

case class Redirect(
  location   : String,
  exitPortal : Boolean = false
) extends StreamedContentOrRedirect with BufferedContentOrRedirect {
  require(location ne null, "Missing redirect location")
}

case class HttpClientSettings(
  staleCheckingEnabled           : Boolean,
  soTimeout                      : Int,
  chunkRequests                  : Boolean,

  proxyHost                      : Option[String],
  proxyPort                      : Option[Int],
  proxyExclude                   : Option[String],

  sslHostnameVerifier            : String,
  sslKeystoreURI                 : Option[String],
  sslKeystorePassword            : Option[String],
  sslKeystoreType                : Option[String],

  proxySSL                       : Boolean,
  proxyUsername                  : Option[String],
  proxyPassword                  : Option[String],
  proxyNTLMHost                  : Option[String],
  proxyNTLMDomain                : Option[String],

  expiredConnectionsPollingDelay : Option[FiniteDuration],
  idleConnectionsDelay           : Option[FiniteDuration]
)

object HttpClientSettings {

  def apply(param: String ⇒ String): HttpClientSettings = {

    def booleanParamWithDefault(name: String, default: Boolean) =
      param(name).trimAllToOpt map (_ == "true") getOrElse default

    def intParam(name: String) =
      param(name).trimAllToOpt map (_.toInt)

    def longParam(name: String) =
      param(name).trimAllToOpt map (_.toLong)

    def intParamWithDefault(name: String, default: Int) =
      intParam(name) getOrElse default

    def stringParam(name: String) =
      param(name).trimAllToOpt

    def stringParamWithDefault(name: String, default: String) =
      stringParam(name) getOrElse default

    HttpClientSettings(
      staleCheckingEnabled           = booleanParamWithDefault(StaleCheckingEnabledProperty, StaleCheckingEnabledDefault),
      soTimeout                      = intParamWithDefault(SOTimeoutProperty, SOTimeoutPropertyDefault),
      chunkRequests                  = booleanParamWithDefault(ChunkRequestsProperty, ChunkRequestsDefault),

      proxyHost                      = stringParam(ProxyHostProperty),
      proxyPort                      = intParam(ProxyPortProperty),
      proxyExclude                   = stringParam(ProxyExcludeProperty),

      sslHostnameVerifier            = stringParamWithDefault(SSLHostnameVerifierProperty, SSLHostnameVerifierDefault),
      sslKeystoreURI                 = stringParam(SSLKeystoreURIProperty),
      sslKeystorePassword            = stringParam(SSLKeystorePasswordProperty),
      sslKeystoreType                = stringParam(SSLKeystoreTypeProperty),

      proxySSL                       = booleanParamWithDefault(ProxySSLProperty, ProxySSLPropertyDefault),
      proxyUsername                  = stringParam(ProxyUsernameProperty),
      proxyPassword                  = stringParam(ProxyPasswordProperty),
      proxyNTLMHost                  = stringParam(ProxyNTLMHostProperty),
      proxyNTLMDomain                = stringParam(ProxyNTLMDomainProperty),

      expiredConnectionsPollingDelay = longParam(ExpiredConnectionsPollingDelayProperty) filter (_ > 0) map (_.milliseconds),
      idleConnectionsDelay           = longParam(IdleConnectionsDelayProperty)           filter (_ > 0) map (_.milliseconds)
    )
  }

  val StaleCheckingEnabledProperty                  = "oxf.http.stale-checking-enabled"
  val SOTimeoutProperty                             = "oxf.http.so-timeout"
  val ChunkRequestsProperty                         = "oxf.http.chunk-requests"
  val ProxyHostProperty                             = "oxf.http.proxy.host"
  val ProxyPortProperty                             = "oxf.http.proxy.port"
  val ProxyExcludeProperty                          = "oxf.http.proxy.exclude"
  val SSLHostnameVerifierProperty                   = "oxf.http.ssl.hostname-verifier"
  val SSLKeystoreURIProperty                        = "oxf.http.ssl.keystore.uri"
  val SSLKeystorePasswordProperty                   = "oxf.http.ssl.keystore.password"
  val SSLKeystoreTypeProperty                       = "oxf.http.ssl.keystore.type"
  val ProxySSLProperty                              = "oxf.http.proxy.use-ssl"
  val ProxyUsernameProperty                         = "oxf.http.proxy.username"
  val ProxyPasswordProperty                         = "oxf.http.proxy.password"
  val ProxyNTLMHostProperty                         = "oxf.http.proxy.ntlm.host"
  val ProxyNTLMDomainProperty                       = "oxf.http.proxy.ntlm.domain"
  val ExpiredConnectionsPollingDelayProperty        = "oxf.http.expired-connections-polling-delay"
  val IdleConnectionsDelayProperty                  = "oxf.http.idle-connections-delay"

  val StaleCheckingEnabledDefault                   = true
  val SOTimeoutPropertyDefault                      = 0
  val ChunkRequestsDefault                          = false
  val ProxySSLPropertyDefault                       = false
  val SSLHostnameVerifierDefault                    = "strict"
  val expiredConnectionsPollingDelayPropertyDefault = 5000
}

case class Credentials(username: String, password: Option[String], preemptiveAuth: Boolean, domain: Option[String]) {
  require(username.nonBlank)
  def getPrefix: String = Option(password) map (username + ":" + _ + "@") getOrElse username + "@"
}

object Credentials {
  def apply(username: String, password: String, preemptiveAuth: String, domain: String): Credentials =
    Credentials(
      username.trimAllToEmpty,
      password.trimAllToOpt,
      ! (preemptiveAuth.trimAllToOpt contains "false"),
      domain.trimAllToOpt
    )
}

trait HttpResponse {
  def statusCode   : Int
  def headers      : Map[String, List[String]]
  def lastModified : Option[Long]
  def content      : StreamedContent
  def disconnect() : Unit
}

object StatusCode {
  val Ok                    = 200
  val NotModified           = 304
  val Unauthorized          = 401
  val Forbidden             = 403
  val MethodNotAllowed      = 405
  val BadRequest            = 400
  val NotFound              = 404
  val Conflict              = 409
  val Gone                  = 410
  val RequestEntityTooLarge = 413
  val Locked                = 423
  val InternalServerError   = 500
  val ServiceUnavailable    = 503
}

sealed abstract class HttpMethod extends EnumEntry

object HttpMethod extends Enum[HttpMethod] {

  val values = findValues

  case object GET     extends HttpMethod
  case object POST    extends HttpMethod
  case object PUT     extends HttpMethod
  case object DELETE  extends HttpMethod
  case object HEAD    extends HttpMethod
  case object OPTIONS extends HttpMethod
  case object TRACE   extends HttpMethod
  case object LOCK    extends HttpMethod
  case object UNLOCK  extends HttpMethod
}

trait HttpClient {

  def connect(
    url         : String,
    credentials : Option[Credentials],
    cookieStore : org.apache.http.client.CookieStore,
    method      : HttpMethod,
    headers     : Map[String, List[String]],
    content     : Option[StreamedContent]
  ): HttpResponse

  def shutdown(): Unit
}

object EmptyInputStream extends InputStream {
  def read: Int = -1
}