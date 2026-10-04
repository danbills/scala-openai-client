package openai.models

import io.circe.{Decoder, Encoder}
import io.github.iltotore.iron.circe.given
import openai.RefinedTypes.*
import openai.enums.FinishReason

case class Usage(
  prompt_tokens: NonNegativeInt,
  completion_tokens: NonNegativeInt,
  total_tokens: NonNegativeInt
) derives Encoder.AsObject, Decoder

case class Timings(
  prompt_n: NonNegativeInt,
  prompt_ms: Double,
  prompt_per_token_ms: Double,
  prompt_per_second: Double,
  predicted_n: NonNegativeInt,
  predicted_ms: Double,
  predicted_per_token_ms: Double,
  predicted_per_second: Double,
  cache_n: NonNegativeInt
) derives Encoder.AsObject, Decoder

case class Choice(
  index: NonNegativeInt,
  message: ChatMessage,
  finish_reason: FinishReason
) derives Encoder.AsObject, Decoder

case class ChatCompletionResponse(
  id: NonEmptyString,
  model: NonEmptyString,
  `object`: String,
  created: Long,
  system_fingerprint: Option[String] = None,
  choices: List[Choice],
  usage: Option[Usage] = None,
  timings: Option[Timings] = None
) derives Encoder.AsObject, Decoder
