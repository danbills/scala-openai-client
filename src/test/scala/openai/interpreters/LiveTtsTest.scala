package openai.interpreters

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.free.Free
import io.github.iltotore.iron.autoRefine
import openai.free.*
import openai.models.*
import org.http4s.multipart.Multiparts
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

/**
 * Live: TTS (TTS_URL) round-tripped through Whisper (WHISPER_URL), so no speaker is needed.
 * Cancels when either variable is unset or its server is unreachable (see [[LiveEnv]]).
 */
class LiveTtsTest extends AnyFunSuite:
  def endpoints(speech: Endpoint, transcription: Endpoint) =
    OpenAIHttp4s.Endpoints(chat = speech, transcription = transcription, speech = speech)

  def run[A](endpoints: OpenAIHttp4s.Endpoints)(program: Free[OpenAIStreamingOp, A]): List[A] =
    OpenAIHttp4s.client[IO].use { client =>
      Multiparts.forSync[IO].flatMap(m => program.foldMap(OpenAIHttp4s.streaming(client, endpoints, m)).compile.toList)
    }.unsafeRunSync()

  test("speak, then transcribe what was spoken") {
    val ttsAt     = LiveEnv.endpoint("TTS_URL").fold(cancel(_), identity)
    val whisperAt = LiveEnv.endpoint("WHISPER_URL").fold(cancel(_), identity)

    // Some servers (NeMo) ignore response_format and always send WAV; the interpreter labels the clip from Content-Type.
    val speech = TextToSpeechRequest("The quick brown fox jumps over the lazy dog.", responseFormat = AudioEncoding.Pcm16())
    val program = for
      clip  <- Audio[OpenAIStreamingOp].speak(speech)
      heard <- Audio[OpenAIStreamingOp].transcribe(AudioTranscriptionRequest(clip))
    yield (clip, heard.text)

    val List((clip, heard)) = run(endpoints(ttsAt, whisperAt))(program): @unchecked
    println(s"TTS: ${clip.bytes.size} bytes ${clip.encoding}; heard: $heard")
    assert(clip.encoding == AudioEncoding.Wav)
    assert(clip.bytes.take(4) == ByteVector("RIFF".getBytes))
    assert(heard.toLowerCase.contains("quick brown fox"))
  }

  test("streamed speech arrives as bytes, then Done") {
    val ttsAt  = LiveEnv.endpoint("TTS_URL").fold(cancel(_), identity)
    val events = run(endpoints(ttsAt, ttsAt))(SpeechStreaming[OpenAIStreamingOp].events(TextToSpeechRequest("Streaming works.")))
    val bytes  = events.collect { case SpeechEvent.Bytes(b) => b }.foldLeft(ByteVector.empty)(_ ++ _)
    println(s"Streamed TTS: ${events.size - 1} chunks, ${bytes.size} bytes")
    assert(events.last == SpeechEvent.Done)
    assert(bytes.take(4) == ByteVector("RIFF".getBytes))
  }
