package openai.audio

import fs2.concurrent.Channel
import cats.effect.{Concurrent, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import cats.~>
import fs2.io.process.{ProcessBuilder, Processes}
import fs2.{Chunk, Stream}
import io.github.iltotore.iron.autoRefine
import openai.models.{AudioClip, AudioEncoding}
import scodec.bits.ByteVector

final case class AudioDeviceError(command: String, exitCode: Int)
    extends RuntimeException(s"$command exited with $exitCode")

/**
 * How [[ProcessAudioDevice]] reaches the sound card. The defaults shell out to ALSA's `arecord`/`aplay`
 * (pipewire-alsa routes them on a desktop) and to `ffplay` for compressed formats; swap the commands for other
 * platforms or for tests.
 *
 * @param capture   what the microphone records; takes come back as WAV in this spec
 * @param speaker   the spec of the PCM speaker held open for `Write`; `None` opens none and `Write` fails
 * @param record    the recorder for a spec and an optional sample count, writing raw PCM to stdout
 * @param play      a player reading the given encoding on stdin
 */
final case class ProcessAudioConfig(
  capture: AudioEncoding.Pcm16 = AudioEncoding.Pcm16(16000, 1),
  speaker: Option[AudioEncoding.Pcm16] = Some(AudioEncoding.Pcm16()),
  record: (AudioEncoding.Pcm16, Option[Long]) => ProcessBuilder = ProcessAudioConfig.arecord,
  play: AudioEncoding => ProcessBuilder = ProcessAudioConfig.aplay
)

object ProcessAudioConfig:
  private def pcmArgs(spec: AudioEncoding.Pcm16): List[String] =
    List("-t", "raw", "-f", "S16_LE", "-r", spec.sampleRate.toString, "-c", spec.channels.toString)

  def arecord(spec: AudioEncoding.Pcm16, samples: Option[Long]): ProcessBuilder =
    ProcessBuilder("arecord", ("-q" :: pcmArgs(spec)) ++ samples.toList.flatMap(n => List("-s", n.toString)))

  def aplay(encoding: AudioEncoding): ProcessBuilder = encoding match
    case AudioEncoding.Wav          => ProcessBuilder("aplay", List("-q", "-t", "wav", "-"))
    case spec: AudioEncoding.Pcm16  => ProcessBuilder("aplay", ("-q" :: pcmArgs(spec)) :+ "-")
    case _                          => ProcessBuilder("ffplay", List("-nodisp", "-autoexit", "-loglevel", "quiet", "-i", "-"))

/**
 * [[AudioDeviceOp]] through external processes. Needs only `Concurrent` and `Processes` (no `Async`, no timer):
 * fixed-length takes ask the recorder for an exact sample count, and `UntilStopped` takes end when `stop` completes.
 */
object ProcessAudioDevice:

  /**
   * @param stop completes to end a `RecordLimit.UntilStopped` take; evaluated afresh for each take (e.g. read a
   *             line from the console). Without it such takes fail.
   */
  def apply[F[_]: Concurrent: Processes](
    config: ProcessAudioConfig = ProcessAudioConfig(),
    stop: Option[F[Unit]] = None
  ): Resource[F, AudioDeviceOp ~> F] =
    config.speaker.traverse(openSpeaker(config, _)).map { speaker =>
      new (AudioDeviceOp ~> F):
        def apply[A](op: AudioDeviceOp[A]): F[A] = op match
          case AudioDeviceOp.Record(limit) => record(config, limit, stop)
          case AudioDeviceOp.Play(clip)    => play(config, clip)
          case AudioDeviceOp.Write(pcm)    =>
            speaker match
              case None     => Concurrent[F].raiseError(new IllegalStateException("no speaker configured for Write"))
              case Some(ch) => ch.send(pcm).flatMap(_.leftMap(_ => new IllegalStateException("speaker closed")).liftTo[F])
    }

  private def record[F[_]: Concurrent: Processes](
    config: ProcessAudioConfig,
    limit: RecordLimit,
    stop: Option[F[Unit]]
  ): F[AudioClip] =
    val spec = config.capture
    val pcm: F[ByteVector] = limit match
      case RecordLimit.For(d) =>
        val cmd = config.record(spec, Some(d.toMillis * spec.sampleRate / 1000))
        cmd.spawn[F].use { proc =>
          proc.stdout.compile.to(ByteVector) <* checkExit(cmd, proc.exitValue)
        }
      case RecordLimit.UntilStopped =>
        stop match
          case None         => Concurrent[F].raiseError(new IllegalStateException("no stop signal for UntilStopped"))
          case Some(signal) =>
            // Interrupting the read ends the take; releasing the process then stops the recorder.
            config.record(spec, None).spawn[F].use(_.stdout.interruptWhen(signal.attempt).compile.to(ByteVector))
    pcm.map(Wav.fromPcm16(_, spec))

  private def play[F[_]: Concurrent: Processes](config: ProcessAudioConfig, clip: AudioClip): F[Unit] =
    val cmd = config.play(clip.encoding)
    cmd.spawn[F].use { proc =>
      Stream.chunk(Chunk.byteVector(clip.bytes)).through(proc.stdin).compile.drain >> checkExit(cmd, proc.exitValue)
    }

  /**
   * A player held open for the interpreter's lifetime, fed from a channel so `Write` returns without waiting for
   * playback. On release the channel closes, the backlog drains, and the player is allowed to finish what it has
   * buffered before it is stopped.
   */
  private def openSpeaker[F[_]: Concurrent: Processes](
    config: ProcessAudioConfig,
    spec: AudioEncoding.Pcm16
  ): Resource[F, Channel[F, ByteVector]] =
    for
      proc <- config.play(spec).spawn[F]
      ch   <- Resource.eval(Channel.unbounded[F, ByteVector])
      feed  = ch.stream.flatMap(b => Stream.chunk(Chunk.byteVector(b))).through(proc.stdin).compile.drain
      _    <- Resource.make(feed.start)(fiber => ch.close >> fiber.join >> proc.exitValue.void)
    yield ch

  private def checkExit[F[_]: Concurrent](cmd: ProcessBuilder, exit: F[Int]): F[Unit] =
    exit.flatMap(code => Concurrent[F].raiseWhen(code != 0)(AudioDeviceError(cmd.command, code)))
