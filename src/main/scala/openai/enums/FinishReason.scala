package openai.enums

import io.circe.{Decoder, Encoder}

enum FinishReason(val value: String):
  case Stop          extends FinishReason("stop")
  case Length        extends FinishReason("length")
  case ContentFilter extends FinishReason("content_filter")
  case ToolCalls     extends FinishReason("tool_calls")

object FinishReason:
  given Encoder[FinishReason] = Encoder.encodeString.contramap(_.value)
  given Decoder[FinishReason] = Decoder.decodeString.emap: s =>
    FinishReason.values.find(_.value == s).toRight(s"Invalid finish_reason: $s")
