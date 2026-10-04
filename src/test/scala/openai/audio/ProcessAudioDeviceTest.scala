package openai.audio

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import fs2.io.process.ProcessBuilder
import openai.models.{AudioClip, AudioEncoding}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.{ByteOrdering, ByteVector}

import java.nio.file.Files as JFiles
import scala.concurrent.duration.*

/** The process device with stand-in commands (`head`, `cat`, `sh`) instead of a sound card. */
class ProcessAudioDeviceTest extends AnyFunSuite:

  val spec: AudioEncoding.Pcm16 = AudioEncoding.Pcm16(16000, 1)

  def tmp(): java.nio.file.Path = JFiles.createTempFile("audio", ".bin")
  def sh(script: String): ProcessBuilder = ProcessBuilder("sh", List("-c", script))

  /** Silence of exactly the requested sample count, or endless silence when there is no count. */
  val fakeRecorder: (AudioEncoding.Pcm16, Option[Long]) => ProcessBuilder = (s, samples) =>
    samples.fold(ProcessBuilder("cat", List("/dev/zero")))(n =>
      ProcessBuilder("head", List("-c", (n * s.channels * 2).toString, "/dev/zero")))

  def run[A](config: ProcessAudioConfig, stop: Option[IO[Unit]] = None)(ops: AudioDeviceOp[?]*): List[Any] =
    ProcessAudioDevice[IO](config, stop).use(dev => ops.toList.traverse(op => dev(op).widen[Any])).unsafeRunSync()


  def dataSize(wav: ByteVector): Long = wav.slice(40, 44).toInt(signed = false, ByteOrdering.LittleEndian).toLong

  test("fixed-length take asks for the exact sample count and comes back as a valid WAV") {
    val config = ProcessAudioConfig(capture = spec, speaker = None, record = fakeRecorder)
    val List(clip: AudioClip) = run(config)(AudioDeviceOp.Record(RecordLimit.For(250.millis))): @unchecked
    assert(clip.encoding == AudioEncoding.Wav)
    assert(clip.bytes.size == 44 + 8000) // 0.25 s * 16 kHz * 2 bytes
    assert(clip.bytes.take(4) == ByteVector("RIFF".getBytes))
    assert(dataSize(clip.bytes) == 8000)
  }

  test("UntilStopped records until the stop signal, then stops the recorder") {
    val config = ProcessAudioConfig(capture = spec, speaker = None, record = fakeRecorder)
    val List(clip: AudioClip) =
      run(config, stop = Some(IO.sleep(200.millis)))(AudioDeviceOp.Record(RecordLimit.UntilStopped)): @unchecked
    assert(clip.bytes.size > 44)
    assert(dataSize(clip.bytes) == clip.bytes.size - 44)
  }

  test("UntilStopped without a stop signal fails") {
    val config = ProcessAudioConfig(speaker = None, record = fakeRecorder)
    assertThrows[IllegalStateException](run(config)(AudioDeviceOp.Record(RecordLimit.UntilStopped)))
  }

  test("Play pipes the whole clip to the player; a failing player raises") {
    val out    = tmp()
    val clip   = AudioClip(ByteVector(1, 2, 3, 4), AudioEncoding.Mp3)
    val config = ProcessAudioConfig(speaker = None, play = _ => sh(s"cat > $out"))
    run(config)(AudioDeviceOp.Play(clip))
    assert(ByteVector(JFiles.readAllBytes(out)) == clip.bytes)

    val failing = ProcessAudioConfig(speaker = None, play = _ => sh("cat > /dev/null; exit 3"))
    assert(intercept[AudioDeviceError](run(failing)(AudioDeviceOp.Play(clip))) == AudioDeviceError("sh", 3))
  }

  test("Write feeds one long-lived speaker, which finishes playing before release") {
    val out = tmp()
    // A slow player: release must drain the backlog and wait for it rather than kill it.
    val config = ProcessAudioConfig(speaker = Some(spec), play = _ => sh(s"sleep 0.3; cat > $out"))
    run(config)(AudioDeviceOp.Write(ByteVector(1, 2)), AudioDeviceOp.Write(ByteVector(3)), AudioDeviceOp.Write(ByteVector(4, 5)))
    assert(ByteVector(JFiles.readAllBytes(out)) == ByteVector(1, 2, 3, 4, 5))
  }

  test("Write without a speaker fails") {
    assertThrows[IllegalStateException](run(ProcessAudioConfig(speaker = None))(AudioDeviceOp.Write(ByteVector(1))))
  }

  test("default commands") {
    assert(ProcessAudioConfig.arecord(spec, Some(4000L)).args ==
      List("-q", "-t", "raw", "-f", "S16_LE", "-r", "16000", "-c", "1", "-s", "4000"))
    assert(ProcessAudioConfig.aplay(AudioEncoding.Wav).args == List("-q", "-t", "wav", "-"))
    assert(ProcessAudioConfig.aplay(AudioEncoding.Opus).command == "ffplay")
  }
