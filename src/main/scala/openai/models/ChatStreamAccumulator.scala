package openai.models

import openai.enums.Role
import scodec.bits.ByteVector

import scala.collection.immutable.SortedMap

/**
 * Pure fold of streamed [[DeltaContent]]s into the whole reply: `content` and `reasoning_content` concatenate,
 * tool-call fragments assemble by `index` (the first fragment carries id and name, `arguments` concatenate), and
 * spoken-reply audio bytes and transcript concatenate.
 */
final case class ChatStreamAccumulator(
  role: Option[Role] = None,
  content: Vector[String] = Vector.empty,
  reasoning: Vector[String] = Vector.empty,
  toolCalls: SortedMap[Int, ToolCallDelta] = SortedMap.empty,
  audioId: Option[String] = None,
  audio: ByteVector = ByteVector.empty,
  transcript: Vector[String] = Vector.empty
):
  def add(d: DeltaContent): ChatStreamAccumulator =
    copy(
      role = role.orElse(d.role),
      content = content ++ d.content,
      reasoning = reasoning ++ d.reasoning_content,
      toolCalls = d.tool_calls.getOrElse(Nil).foldLeft(toolCalls) { (acc, f) =>
        acc.updated(f.index, acc.get(f.index).fold(f)(prev =>
          ToolCallDelta(
            f.index,
            prev.id.orElse(f.id),
            prev.name.orElse(f.name),
            Some(prev.arguments.getOrElse("") + f.arguments.getOrElse(""))
          )))
      },
      audioId = audioId.orElse(d.audio.flatMap(_.id)),
      audio = d.audio.flatMap(_.data).fold(audio)(audio ++ _),
      transcript = transcript ++ d.audio.flatMap(_.transcript)
    )

  def message: ChatMessage =
    ChatMessage(
      role = role.getOrElse(Role.Assistant),
      content = content.mkString,
      reasoning_content = Option.when(reasoning.nonEmpty)(reasoning.mkString),
      tool_calls = Option.when(toolCalls.nonEmpty)(toolCalls.values.toList.map(t =>
        ToolCall(t.id.getOrElse(""), t.name.getOrElse(""), t.arguments.getOrElse("")))),
      audio = Option.when(audioId.nonEmpty || audio.nonEmpty || transcript.nonEmpty)(
        AssistantAudio(audioId.getOrElse(""), audio, transcript.mkString))
    )

object ChatStreamAccumulator:
  def fold(deltas: IterableOnce[DeltaContent]): ChatMessage =
    deltas.iterator.foldLeft(ChatStreamAccumulator())(_.add(_)).message
