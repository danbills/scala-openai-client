package openai.interpreters

import cats.effect.Concurrent
import cats.syntax.all.*
import cats.~>
import fs2.{Pull, Stream}
import io.circe.parser.decode
import io.circe.syntax.*
import openai.enums.StreamEnabled
import openai.free.{ChatOp, ChatStreamEvent, ChatStreamOp, ChatStreamingOp, StreamInterpreters}
import openai.models.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client

/** `/chat/completions` over http4s. `Concurrent[F]` is for decoding JSON bodies; nothing here needs `Async`. */
object Http4sChat:

  def apply[F[_]: Concurrent](client: Client[F], endpoint: Endpoint): ChatOp ~> F = new (ChatOp ~> F):
    def apply[A](op: ChatOp[A]): F[A] = op match
      case ChatOp.Complete(request) =>
        endpoint.post[F]("/chat/completions").flatMap { req =>
          client.expectOr[ChatCompletionResponse](req.withEntity(request.copy(stream = None).asJson))(OpenAIError.from)
        }

  /**
   * `stream: true` chat: a [[ChatStreamEvent.Delta]] per SSE chunk, in order, then one [[ChatStreamEvent.Done]]
   * with the reply assembled by [[ChatStreamAccumulator]]. Back-pressured by the response body; the connection
   * closes when the stream ends or is interrupted.
   */
  def stream[F[_]: Concurrent](client: Client[F], endpoint: Endpoint): ChatStreamOp ~> Stream[F, *] =
    new (ChatStreamOp ~> Stream[F, *]):
      def apply[A](op: ChatStreamOp[A]): Stream[F, A] = op match
        case ChatStreamOp.Events(request) =>
          Stream
            .eval(endpoint.post[F]("/chat/completions"))
            .flatMap(req => Responses.stream(client, req.withEntity(request.copy(stream = Some(StreamEnabled.Yes)).asJson)))
            .flatMap(resp => events(Responses.sseData(resp.body).evalMap(chunk[F])))

  /** [[stream]] with `Halt`: everything a [[openai.free.ChatStreamingOp]] program needs. */
  def streaming[F[_]: Concurrent](client: Client[F], endpoint: Endpoint): ChatStreamingOp ~> Stream[F, *] =
    stream(client, endpoint).or(StreamInterpreters.control[F])

  private def chunk[F[_]](line: String)(using F: Concurrent[F]): F[StreamChunk] =
    F.fromEither(decode[StreamChunk](line).leftMap(e => OpenAIError.BadStreamChunk(e.getMessage, line)))

  /** Chunks with no choices (usage, timings) carry no delta and are skipped. */
  private def events[F[_]](chunks: Stream[F, StreamChunk]): Stream[F, ChatStreamEvent] =
    def go(s: Stream[F, StreamChunk], acc: ChatStreamAccumulator, finish: Option[String])
        : Pull[F, ChatStreamEvent, Unit] =
      s.pull.uncons1.flatMap:
        case None => Pull.output1(ChatStreamEvent.Done(acc.message, finish))
        case Some((c, rest)) =>
          c.choices.headOption match
            case None         => go(rest, acc, finish)
            case Some(choice) =>
              Pull.output1(ChatStreamEvent.Delta(choice.delta)) >>
                go(rest, acc.add(choice.delta), choice.finish_reason.orElse(finish))
    go(chunks, ChatStreamAccumulator(), None).stream
