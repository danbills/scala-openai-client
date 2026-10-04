package openai.audio

import cats.InjectK
import cats.free.Free
import openai.models.AudioClip
import scodec.bits.ByteVector

import scala.concurrent.duration.FiniteDuration

/** How long a take lasts. */
enum RecordLimit:
  case For(duration: FiniteDuration)
  /** Until the interpreter's stop signal fires (Enter, a UI button, voice-activity detection). */
  case UntilStopped

/**
 * The local microphone and speaker as data, separate from the HTTP algebras: a voice program mixes this with
 * `ChatOp`/`AudioOp`/the streaming algebras, and the HTTP interpreters never touch a sound device.
 */
enum AudioDeviceOp[A]:
  case Record(limit: RecordLimit) extends AudioDeviceOp[AudioClip]
  /** Play a whole clip, returning when it has finished. */
  case Play(clip: AudioClip) extends AudioDeviceOp[Unit]
  /**
   * Append raw PCM to the speaker the interpreter holds open for its lifetime, in the `Pcm16` spec it was built
   * with. For streamed audio (spoken chat replies, streamed TTS), where each chunk arrives as its own value.
   */
  case Write(pcm: ByteVector) extends AudioDeviceOp[Unit]

/** Smart constructors for [[AudioDeviceOp]] into any coproduct `G` that contains it. */
final class AudioDevice[G[_]](using InjectK[AudioDeviceOp, G]):
  def record(limit: RecordLimit): Free[G, AudioClip] = Free.liftInject[G](AudioDeviceOp.Record(limit))
  def play(clip: AudioClip): Free[G, Unit]           = Free.liftInject[G](AudioDeviceOp.Play(clip))
  def write(pcm: ByteVector): Free[G, Unit]          = Free.liftInject[G](AudioDeviceOp.Write(pcm))

object AudioDevice:
  given [G[_]](using InjectK[AudioDeviceOp, G]): AudioDevice[G] = new AudioDevice[G]
  def apply[G[_]](using d: AudioDevice[G]): AudioDevice[G]      = d
