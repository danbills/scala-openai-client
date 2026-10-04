package openai.models

import io.circe.{Encoder, Json, JsonObject}
import io.circe.syntax.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.autoRefine
import openai.RefinedTypes.*

case class TextToSpeechRequest(
  input: NonEmptyString,
  model: NonEmptyString = "fastpitch",
  voice: NonEmptyString = "default",
  responseFormat: AudioEncoding = AudioEncoding.Wav,
  /** Delivery instructions (tone, pace), for instruction-following TTS models. */
  instructions: Option[NonEmptyString] = None,
  speed: Option[SpeechSpeed] = None
)

object TextToSpeechRequest:
  given Encoder[TextToSpeechRequest] = Encoder.instance { req =>
    var obj = JsonObject(
      "input"           -> req.input.asJson,
      "model"           -> req.model.asJson,
      "voice"           -> req.voice.asJson,
      "response_format" -> req.responseFormat.speechName.asJson
    )
    req.instructions.foreach(i => obj = obj.add("instructions", i.asJson))
    req.speed.foreach(s => obj = obj.add("speed", s.asJson))
    Json.fromJsonObject(obj)
  }
