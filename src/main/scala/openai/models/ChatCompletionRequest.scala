package openai.models

import io.circe.{Encoder, Json, JsonObject}
import io.circe.syntax.*
import io.github.iltotore.iron.circe.given
import openai.RefinedTypes.*
import openai.enums.StreamEnabled

/** @param include_usage ask for one more chunk at the end of the stream, carrying the request's token counts */
case class StreamOptions(include_usage: Boolean) derives Encoder.AsObject

case class ChatCompletionRequest(
  model: NonEmptyString,
  messages: List[ChatMessage],
  temperature: Option[Temperature] = None,
  max_tokens: Option[MaxTokens] = None,
  stream: Option[StreamEnabled] = None,
  /** Only read by servers when streaming. */
  stream_options: Option[StreamOptions] = None,
  logit_bias: Option[Map[String, Double]] = None,
  chat_template_kwargs: Option[Map[String, Json]] = None,
  grammar: Option[NonEmptyString] = None,
  response_format: Option[Json] = None,
  /** OpenAI `tools` array (function definitions), passed through as JSON. */
  tools: Option[Json] = None,
  tool_choice: Option[Json] = None,
  /** Output modalities; include `Modality.Audio` (with `audio`) for a spoken reply. */
  modalities: Option[List[Modality]] = None,
  audio: Option[AudioOutput] = None
)

object ChatCompletionRequest:
  given Encoder[ChatCompletionRequest] = Encoder.instance { req =>
    var obj = JsonObject(
      "model"    -> req.model.asJson,
      "messages" -> req.messages.asJson
    )
    req.temperature.foreach(t => obj = obj.add("temperature", t.asJson))
    req.max_tokens.foreach(m => obj = obj.add("max_tokens", m.asJson))
    req.stream.foreach(s => obj = obj.add("stream", s.asJson))
    req.stream_options.foreach(o => obj = obj.add("stream_options", o.asJson))
    req.logit_bias.foreach(b => obj = obj.add("logit_bias", b.asJson))
    req.chat_template_kwargs.foreach(k => obj = obj.add("chat_template_kwargs", k.asJson))
    req.grammar.foreach(g => obj = obj.add("grammar", g.asJson))
    req.response_format.foreach(rf => obj = obj.add("response_format", rf))
    req.tools.foreach(t => obj = obj.add("tools", t))
    req.tool_choice.foreach(t => obj = obj.add("tool_choice", t))
    req.modalities.foreach(m => obj = obj.add("modalities", m.asJson))
    req.audio.foreach(a => obj = obj.add("audio", a.asJson))
    Json.fromJsonObject(obj)
  }
