package openai.free

import cats.InjectK
import cats.free.Free
import openai.models.*

/** One event of a `stream: true` chat: a [[Delta]] per SSE chunk, in order, then exactly one [[Done]]. */
enum ChatStreamEvent:
  case Delta(delta: DeltaContent)
  /** The whole reply, assembled from the deltas (see [[ChatStreamAccumulator]]), tool calls included. */
  case Done(message: ChatMessage, finishReason: Option[String])

/**
 * Streaming chat as data. An op yields one [[ChatStreamEvent]] at a time: its interpreter targets
 * `fs2.Stream[F, *]` as the monad, so the rest of the program runs once per event (like the list monad).
 * Programs using this algebra therefore need a `Stream` interpreter; the coproduct type says so.
 */
enum ChatStreamOp[A]:
  case Events(request: ChatCompletionRequest) extends ChatStreamOp[ChatStreamEvent]

/** Smart constructors for [[ChatStreamOp]] into any coproduct `G` that also holds [[StreamControlOp]]. */
final class ChatStreaming[G[_]](using InjectK[ChatStreamOp, G], StreamControl[G]):
  def events(request: ChatCompletionRequest): Free[G, ChatStreamEvent] =
    Free.liftInject[G](ChatStreamOp.Events(request))

  /** Fan out: the rest of the program runs once per delta; the final [[ChatStreamEvent.Done]] is dropped. */
  def deltas(request: ChatCompletionRequest): Free[G, DeltaContent] =
    events(request).flatMap:
      case ChatStreamEvent.Delta(d) => Free.pure(d)
      case _: ChatStreamEvent.Done  => StreamControl[G].halt

  /**
   * Run `f` for each delta, then rejoin: the rest of the program runs exactly once, with the assembled reply.
   * Stream the tokens to a UI, then carry on with the whole answer (e.g. a tool-call loop).
   */
  def onDeltas(request: ChatCompletionRequest)(f: DeltaContent => Free[G, Unit]): Free[G, ChatStreamEvent.Done] =
    events(request).flatMap:
      case ChatStreamEvent.Delta(d)   => f(d).flatMap(_ => StreamControl[G].halt)
      case done: ChatStreamEvent.Done => Free.pure(done)

object ChatStreaming:
  given [G[_]](using InjectK[ChatStreamOp, G], StreamControl[G]): ChatStreaming[G] = new ChatStreaming[G]
  def apply[G[_]](using s: ChatStreaming[G]): ChatStreaming[G] = s
