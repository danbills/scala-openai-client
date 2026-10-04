package openai

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import openai.RefinedTypes.*
import openai.enums.Role
import openai.free.{Chat, ChatOp}
import openai.interpreters.{Http4sChat, LiveEnv, OpenAIHttp4s}
import openai.models.*
import org.scalatest.funsuite.AnyFunSuite

/** Live: chat completion against LLM_URL (model from MODEL). Cancels when unset or unreachable. */
class ChatCompletionTest extends AnyFunSuite:

  test("chat completions against a live OpenAI-compatible server") {
    val endpoint = LiveEnv.endpoint("LLM_URL").fold(cancel(_), identity)

    val req = ChatCompletionRequest(
      model = LiveEnv.model("qwen3.8-27b"),
      messages = List(
        ChatMessage(Role.System, "You are a helpful assistant."),
        ChatMessage(Role.User, "Say hello in one word.")
      ),
      max_tokens = Some(16)
    )

    val resp = OpenAIHttp4s.client[IO]
      .use(client => Chat[ChatOp].complete(req).foldMap(Http4sChat(client, endpoint)))
      .unsafeRunSync()

    assert(resp.choices.nonEmpty)
    println(s"Response: ${resp.choices.head.message.text}")
  }
