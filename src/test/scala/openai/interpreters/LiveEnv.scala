package openai.interpreters

import io.github.iltotore.iron.*
import openai.RefinedTypes.*

/** Live suites are opt-in: each server comes from an environment variable, and a suite cancels when its
  * variable is unset or the host does not answer. LLM_URL, WHISPER_URL, TTS_URL; OPENAI_API_KEY, MODEL optional.
  */
object LiveEnv:
  private val apiKey: NonEmptyString =
    sys.env.get("OPENAI_API_KEY").filter(_.nonEmpty).getOrElse("sk-local").refineUnsafe

  /** The endpoint named by `variable`, or why it can't be used. */
  def endpoint(variable: String): Either[String, Endpoint] =
    sys.env.get(variable).filter(_.nonEmpty) match
      case None => Left(s"$variable not set")
      case Some(url) =>
        val uri  = java.net.URI.create(url)
        val port = if uri.getPort > 0 then uri.getPort else if uri.getScheme == "https" then 443 else 80
        if reachable(uri.getHost, port) then Right(Endpoint(url.refineUnsafe, apiKey))
        else Left(s"$variable ($url) not reachable")

  def model(default: String): NonEmptyString = sys.env.get("MODEL").filter(_.nonEmpty).getOrElse(default).refineUnsafe

  private def reachable(host: String, port: Int): Boolean =
    try
      val sock = new java.net.Socket()
      sock.connect(new java.net.InetSocketAddress(host, port), 2000)
      sock.close()
      true
    catch case _: Exception => false
