package openai.free

import cats.InjectK
import cats.free.Free
import openai.models.*

/** Chat completions as data: one request in, one response out. Interpretable into any monad. */
enum ChatOp[A]:
  case Complete(request: ChatCompletionRequest) extends ChatOp[ChatCompletionResponse]

/**
 * Smart constructors for [[ChatOp]], polymorphic in the coproduct `G` (Bjarnason, "Composable application
 * architecture with reasonably priced monads"): a program asks for `using Chat[G]` and runs in any `G` that
 * contains `ChatOp`.
 */
final class Chat[G[_]](using InjectK[ChatOp, G]):
  def complete(request: ChatCompletionRequest): Free[G, ChatCompletionResponse] =
    Free.liftInject[G](ChatOp.Complete(request))

  /** The first choice's message, if the server returned any. */
  def ask(request: ChatCompletionRequest): Free[G, Option[ChatMessage]] =
    complete(request).map(_.choices.headOption.map(_.message))

object Chat:
  given [G[_]](using InjectK[ChatOp, G]): Chat[G] = new Chat[G]
  def apply[G[_]](using c: Chat[G]): Chat[G]      = c
