package openai.models

import fs2.io.file.Path
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import openai.RefinedTypes.*
import scodec.bits.ByteVector

/**
 * How audio bytes are encoded. Wire names differ per endpoint: `/audio/speech` calls 16-bit PCM `pcm`, chat
 * completions call it `pcm16`.
 */
enum AudioEncoding:
  case Wav, Mp3, Flac, Opus, Aac
  /** Raw little-endian 16-bit PCM. There is no header, so rate and channel count travel here (OpenAI: 24 kHz mono). */
  case Pcm16(sampleRate: SampleRate = 24000, channels: ChannelCount = 1)

  /** `response_format` for `/audio/speech`. */
  def speechName: String = this match
    case Pcm16(_, _) => "pcm"
    case _           => fileExtension

  /** `format` for chat `input_audio` parts and the chat `audio` output parameter. */
  def chatName: String = this match
    case Pcm16(_, _) => "pcm16"
    case _           => fileExtension

  def fileExtension: String = this match
    case Wav         => "wav"
    case Mp3         => "mp3"
    case Flac        => "flac"
    case Opus        => "opus"
    case Aac         => "aac"
    case Pcm16(_, _) => "pcm"

/** Audio in memory: the common currency between the microphone, chat `input_audio`, TTS and transcription. */
final case class AudioClip(bytes: ByteVector, encoding: AudioEncoding):
  def base64: String = bytes.toBase64

/** Where transcription audio comes from. */
enum AudioInput:
  case FromFile(path: Path)
  case Inline(clip: AudioClip)

/** Output modalities a chat request asks for. */
enum Modality(val value: String):
  case Text  extends Modality("text")
  case Audio extends Modality("audio")

object Modality:
  given Encoder[Modality] = Encoder.encodeString.contramap(_.value)

/** Chat `audio` parameter: speak the reply in this voice and encoding (with `modalities` containing `Audio`). */
final case class AudioOutput(voice: NonEmptyString, format: AudioEncoding = AudioEncoding.Pcm16())

object AudioOutput:
  given Encoder[AudioOutput] = Encoder.instance(o => Json.obj("voice" -> o.voice.asJson, "format" -> o.format.chatName.asJson))

private[models] object Base64:
  val decoder: Decoder[ByteVector] = Decoder.decodeString.emap(ByteVector.fromBase64Descriptive(_))

/**
 * A spoken assistant reply. Encoded back into history as `{"id": ...}` only: the server keeps its own copy, so a
 * multi-turn voice conversation does not resend the audio.
 */
final case class AssistantAudio(id: String, data: ByteVector, transcript: String, expiresAt: Option[Long] = None)

object AssistantAudio:
  given Encoder[AssistantAudio] = Encoder.instance(a => Json.obj("id" -> a.id.asJson))
  given Decoder[AssistantAudio] = Decoder.instance { c =>
    for
      id         <- c.downField("id").as[Option[String]].map(_.getOrElse(""))
      data       <- c.downField("data").as[Option[ByteVector]](using Decoder.decodeOption(using Base64.decoder))
      transcript <- c.downField("transcript").as[Option[String]].map(_.getOrElse(""))
      expiresAt  <- c.downField("expires_at").as[Option[Long]]
    yield AssistantAudio(id, data.getOrElse(ByteVector.empty), transcript, expiresAt)
  }

/** Audio carried by one streamed chat chunk: a slice of the bytes and/or of the transcript. */
final case class AudioDelta(id: Option[String] = None, data: Option[ByteVector] = None, transcript: Option[String] = None)

object AudioDelta:
  given Decoder[AudioDelta] = Decoder.instance { c =>
    for
      id         <- c.downField("id").as[Option[String]]
      data       <- c.downField("data").as[Option[ByteVector]](using Decoder.decodeOption(using Base64.decoder))
      transcript <- c.downField("transcript").as[Option[String]]
    yield AudioDelta(id, data, transcript)
  }
