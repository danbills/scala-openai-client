package openai.interpreters

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.github.iltotore.iron.autoRefine
import openai.free.*
import openai.models.*
import org.http4s.multipart.Multiparts
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

/** Live: transcription interpreter against WHISPER_URL. Cancels if it is unset, unreachable, or `jfk.wav` is absent. */
class LiveWhisperTest extends AnyFunSuite:
  val wav = Path("jfk.wav")

  test("transcribe jfk.wav from a file and from memory") {
    val endpoint = LiveEnv.endpoint("WHISPER_URL").fold(cancel(_), identity)
    if !java.nio.file.Files.exists(wav.toNioPath) then cancel(s"$wav not found")

    val texts = OpenAIHttp4s.client[IO].use { client =>
      for
        multiparts <- Multiparts.forSync[IO]
        interp      = OpenAIHttp4s(client, OpenAIHttp4s.Endpoints.single(endpoint), multiparts)
        bytes      <- Files[IO].readAll(wav).compile.to(ByteVector)
        transcribe  = (r: AudioTranscriptionRequest) => Audio[OpenAIOp].transcribe(r).map(_.text)
        program     = (transcribe(AudioTranscriptionRequest(wav)), transcribe(AudioTranscriptionRequest(AudioClip(bytes, AudioEncoding.Wav))))
                        .mapN(List(_, _))
        texts      <- program.foldMap(interp)
      yield texts
    }.unsafeRunSync()

    println(s"Transcriptions: $texts")
    texts.foreach(t => assert(t.toLowerCase.contains("ask not")))
  }
