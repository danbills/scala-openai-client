package openai.interpreters

import cats.MonadThrow
import cats.effect.Concurrent
import cats.syntax.all.*
import fs2.Stream
import openai.RefinedTypes.*
import org.http4s.*
import org.http4s.client.Client
import org.typelevel.ci.CIStringSyntax

/** One OpenAI-compatible server. Chat, transcription and speech may each live on a different one. */
final case class Endpoint(baseUrl: BaseUrl, apiKey: NonEmptyString):
  /** An authorized POST to `path` under the base URL, e.g. `/chat/completions`. */
  def post[F[_]](path: String)(using F: MonadThrow[F]): F[Request[F]] =
    val url = baseUrl.stripSuffix("/") + path
    F.fromEither(Uri.fromString(url).leftMap(e => OpenAIError.BadUrl(url, e.message)))
      .map(Request[F](Method.POST, _).putHeaders(Header.Raw(ci"Authorization", s"Bearer $apiKey")))

/** Failures the http4s interpreters raise. */
enum OpenAIError(message: String) extends RuntimeException(message):
  case BadUrl(url: String, reason: String)          extends OpenAIError(s"Invalid URL $url: $reason")
  case Http(status: Int, body: String)              extends OpenAIError(s"HTTP $status: ${body.take(500)}")
  case BadStreamChunk(reason: String, line: String) extends OpenAIError(s"SSE parse error: $reason; line: ${line.take(200)}")

object OpenAIError:
  def from[F[_]: Concurrent](resp: Response[F]): F[Throwable] =
    resp.bodyText.compile.string.map(Http(resp.status.code, _))

private[interpreters] object Responses:
  /** The response, if 2xx; otherwise the stream fails with [[OpenAIError.Http]] carrying the body. */
  def stream[F[_]: Concurrent](client: Client[F], req: Request[F]): Stream[F, Response[F]] =
    Stream.resource(client.run(req)).flatMap { resp =>
      if resp.status.isSuccess then Stream.emit(resp)
      else Stream.eval(OpenAIError.from(resp)).flatMap(Stream.raiseError[F](_))
    }

  /** Server-sent events: the `data:` payloads, up to `[DONE]`. */
  def sseData[F[_]](body: Stream[F, Byte]): Stream[F, String] =
    body
      .through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
      .map(_.trim)
      .filter(_.startsWith("data:"))
      .map(_.stripPrefix("data:").trim)
      .takeWhile(_ != "[DONE]")
