package openai.models

import io.circe.Decoder
import io.github.iltotore.iron.circe.given
import openai.RefinedTypes.*
import openai.enums.Role

/** Content delta carried by one SSE chunk (`stream: true` responses).
  *
  * For thinking/reasoning models (e.g. Gemma 4 E4B), `reasoning_content`
  * carries chain-of-thought tokens while `content` carries the final answer.
  * The two fields are mutually exclusive within a single delta chunk.
  */
case class DeltaContent(
  role: Option[Role] = None,
  content: Option[String] = None,
  reasoning_content: Option[String] = None,
  /** Tool-call fragments: accumulate by `index`; the first fragment carries id and name. */
  tool_calls: Option[List[ToolCallDelta]] = None,
  /** Spoken-reply fragments (chat with `Modality.Audio`): concatenate `data` bytes and `transcript` text. */
  audio: Option[AudioDelta] = None
) derives Decoder

case class ToolCallDelta(index: Int, id: Option[String], name: Option[String], arguments: Option[String])

object ToolCallDelta:
  given Decoder[ToolCallDelta] = Decoder.instance { c =>
    for
      index <- c.downField("index").as[Option[Int]].map(_.getOrElse(0))
      id    <- c.downField("id").as[Option[String]]
      name  <- c.downField("function").downField("name").as[Option[String]]
      args  <- c.downField("function").downField("arguments").as[Option[String]]
    yield ToolCallDelta(index, id, name, args)
  }

case class StreamChoice(
  index: NonNegativeInt,
  delta: DeltaContent,
  finish_reason: Option[String] = None
) derives Decoder

/** A single server-sent event chunk from a streaming chat completion. */
case class StreamChunk(
  id: String,
  `object`: String,
  created: Long,
  model: String,
  choices: List[StreamChoice],
  /** Token counts for the whole request: on the last chunk, when the request set `stream_options.include_usage`. */
  usage: Option[Usage] = None
) derives Decoder
