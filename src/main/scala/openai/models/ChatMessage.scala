package openai.models

import io.circe.{Decoder, Encoder, Json, JsonObject}
import io.circe.syntax.*
import io.github.iltotore.iron.circe.given
import openai.RefinedTypes.*
import openai.enums.Role

sealed trait ContentPart
object ContentPart:
  case class Text(text: String) extends ContentPart
  case class ImageUrl(url: String) extends ContentPart
  case class InputAudio(data: String, format: String = "wav") extends ContentPart

  /** A recorded or synthesized clip as an `input_audio` part. */
  def audio(clip: AudioClip): ContentPart = InputAudio(clip.base64, clip.encoding.chatName)

  given Encoder[ContentPart] = Encoder.instance {
    case Text(t)     => Json.obj("type" -> "text".asJson, "text" -> t.asJson)
    case ImageUrl(u) => Json.obj("type" -> "image_url".asJson, "image_url" -> Json.obj("url" -> u.asJson))
    case InputAudio(d, f) => Json.obj(
      "type" -> "input_audio".asJson,
      "input_audio" -> Json.obj(
        "data" -> d.asJson,
        "format" -> f.asJson
      )
    )
  }

type MessageContent = String | List[ContentPart]

/** One function call the model asked for; `arguments` is the raw JSON string the model produced. */
case class ToolCall(id: String, name: String, arguments: String)

object ToolCall:
  given Encoder[ToolCall] = Encoder.instance { t =>
    Json.obj(
      "id" -> t.id.asJson,
      "type" -> "function".asJson,
      "function" -> Json.obj("name" -> t.name.asJson, "arguments" -> t.arguments.asJson)
    )
  }
  given Decoder[ToolCall] = Decoder.instance { c =>
    for
      id   <- c.downField("id").as[Option[String]].map(_.getOrElse(""))
      name <- c.downField("function").downField("name").as[String]
      args <- c.downField("function").downField("arguments").as[Option[String]].map(_.getOrElse(""))
    yield ToolCall(id, name, args)
  }

case class ChatMessage(
  role: Role,
  content: MessageContent,
  reasoning_content: Option[String] = None,
  /** Assistant turns that call tools. */
  tool_calls: Option[List[ToolCall]] = None,
  /** Tool-result turns (`role = Tool`) answer the call with this id. */
  tool_call_id: Option[String] = None,
  /** Spoken assistant reply (chat with `Modality.Audio`). */
  audio: Option[AssistantAudio] = None
):
  /** The text of the message: the string content, or its text parts joined. */
  def text: String = content match
    case s: String  => s
    case l: List[?] => l.collect { case ContentPart.Text(t) => t }.mkString

object ChatMessage:
  given Encoder[ChatMessage] = Encoder.instance { msg =>
    val contentJson = msg.content match
      case s: String  => s.asJson
      case l: List[?] => l.asInstanceOf[List[ContentPart]].asJson

    var obj = JsonObject(
      "role"    -> msg.role.asJson,
      "content" -> contentJson
    )
    msg.reasoning_content.foreach(r => obj = obj.add("reasoning_content", r.asJson))
    msg.tool_calls.foreach(t => obj = obj.add("tool_calls", t.asJson))
    msg.tool_call_id.foreach(i => obj = obj.add("tool_call_id", i.asJson))
    msg.audio.foreach(a => obj = obj.add("audio", a.asJson))
    Json.fromJsonObject(obj)
  }

  given Decoder[ChatMessage] = Decoder.instance { c =>
    for
      role              <- c.downField("role").as[Role]
      // Decoding complex content back to objects is rarely needed for client code.
      // If the API returns a string, we parse it. If it returns an array, we
      // just default to a string placeholder to keep the decoder simple.
      content           <- c.downField("content").as[Option[String]].map(_.getOrElse("")).orElse(Right("[Complex Content]"))
      reasoning_content <- c.downField("reasoning_content").as[Option[String]]
      tool_calls        <- c.downField("tool_calls").as[Option[List[ToolCall]]]
      tool_call_id      <- c.downField("tool_call_id").as[Option[String]]
      audio             <- c.downField("audio").as[Option[AssistantAudio]]
    yield ChatMessage(role, content, reasoning_content, tool_calls, tool_call_id, audio)
  }
