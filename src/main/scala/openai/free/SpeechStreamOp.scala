package openai.free

import cats.InjectK
import cats.free.Free
import openai.models.TextToSpeechRequest
import scodec.bits.ByteVector

/** One event of streamed TTS: a [[Bytes]] per body chunk as it arrives, then exactly one [[Done]]. */
enum SpeechEvent:
  case Bytes(bytes: ByteVector)
  case Done

/**
 * Streamed TTS as data, so playback can start before synthesis ends. Like [[ChatStreamOp]] it is multi-valued:
 * it needs a `Stream[F, *]` interpreter. Ask for `AudioEncoding.Pcm16` when the bytes go straight to a speaker:
 * raw PCM chunks concatenate, container formats do not split cleanly.
 */
enum SpeechStreamOp[A]:
  case Events(request: TextToSpeechRequest) extends SpeechStreamOp[SpeechEvent]

/** Smart constructors for [[SpeechStreamOp]] into any coproduct `G` that also holds [[StreamControlOp]]. */
final class SpeechStreaming[G[_]](using InjectK[SpeechStreamOp, G], StreamControl[G]):
  def events(request: TextToSpeechRequest): Free[G, SpeechEvent] =
    Free.liftInject[G](SpeechStreamOp.Events(request))

  /** Fan out: the rest of the program runs once per chunk of audio. */
  def bytes(request: TextToSpeechRequest): Free[G, ByteVector] =
    events(request).flatMap:
      case SpeechEvent.Bytes(b) => Free.pure(b)
      case SpeechEvent.Done     => StreamControl[G].halt

  /** Run `f` on each chunk (e.g. write it to a speaker), then continue exactly once when synthesis ends. */
  def onBytes(request: TextToSpeechRequest)(f: ByteVector => Free[G, Unit]): Free[G, Unit] =
    events(request).flatMap:
      case SpeechEvent.Bytes(b) => f(b).flatMap(_ => StreamControl[G].halt)
      case SpeechEvent.Done     => Free.pure(())

object SpeechStreaming:
  given [G[_]](using InjectK[SpeechStreamOp, G], StreamControl[G]): SpeechStreaming[G] = new SpeechStreaming[G]
  def apply[G[_]](using s: SpeechStreaming[G]): SpeechStreaming[G] = s
