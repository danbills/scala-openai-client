package openai.demo

import io.github.iltotore.iron.*
import openai.RefinedTypes.*
import openai.interpreters.{Endpoint, OpenAIHttp4s}

/** Servers for the demos, from the environment, defaulting to the usual local ports:
  * LLM_URL (8080), WHISPER_URL (8766), TTS_URL (8767), OPENAI_API_KEY ("sk-local"), MODEL.
  */
object DemoEnv:
  private def env(name: String, default: String): String =
    sys.env.get(name).filter(_.nonEmpty).getOrElse(default)

  val apiKey: NonEmptyString = env("OPENAI_API_KEY", "sk-local").refineUnsafe
  val chat: Endpoint         = Endpoint(env("LLM_URL", "http://localhost:8080/v1").refineUnsafe, apiKey)
  val transcription: Endpoint = Endpoint(env("WHISPER_URL", "http://localhost:8766/v1").refineUnsafe, apiKey)
  val speech: Endpoint       = Endpoint(env("TTS_URL", "http://localhost:8767/v1").refineUnsafe, apiKey)
  val endpoints: OpenAIHttp4s.Endpoints = OpenAIHttp4s.Endpoints(chat, transcription, speech)

  def model(default: String): NonEmptyString = env("MODEL", default).refineUnsafe
