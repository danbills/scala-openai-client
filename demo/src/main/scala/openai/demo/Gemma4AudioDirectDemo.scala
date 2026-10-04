package openai.demo

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.free.Free
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import openai.RefinedTypes.*
import openai.audio.{AudioDevice, ProcessAudioConfig, ProcessAudioDevice, RecordLimit}
import openai.enums.Role
import openai.free.Chat
import openai.interpreters.OpenAIHttp4s
import openai.models.*
import org.http4s.multipart.Multiparts
import scodec.bits.ByteVector

import scala.concurrent.duration.*

/** Talk directly into Gemma-4 12B — raw microphone audio, no transcription.
  *
  * Records from the local mic, sends the take straight to Gemma-4's audio modality (LLM_URL) as an
  * `input_audio` content part alongside a text instruction, and prints the reply. There is no Whisper/STT step —
  * the model hears the audio itself.
  *
  * Requires the unified vision+audio mmproj loaded on :8080 (verify with
  * `/props` on a llama.cpp server → modalities.audio == true).
  *
  * Run (records a fixed 8s window, then answers):
  *   sbt "demo/runMain openai.demo.Gemma4AudioDirectDemo"
  * Record a custom number of seconds:
  *   sbt "demo/runMain openai.demo.Gemma4AudioDirectDemo 12"
  * Or send an existing WAV instead of recording:
  *   sbt "demo/runMain openai.demo.Gemma4AudioDirectDemo jfk.wav"
  */
object Gemma4AudioDirectDemo extends IOApp:

  val endpoints              = OpenAIHttp4s.Endpoints.single(DemoEnv.chat)
  val model: NonEmptyString  = DemoEnv.model("gemma-4-12b-it-qat-q4_0.gguf")
  val defaultSeconds: Int    = 8 // fixed record window when no arg is given

  /** Where the audio comes from: a fresh take of so many seconds, or an existing clip. */
  def askByVoice[G[_]](source: Either[Int, AudioClip])(using C: Chat[G], D: AudioDevice[G], Out: DemoConsole[G])
      : Free[G, Unit] =
    for
      clip <- source.fold(
                secs => Out.println(s"🎙  Recording for ${secs}s…") >> D.record(RecordLimit.For(secs.seconds)),
                Free.pure
              )
      _ <- if clip.bytes.size <= 44 then Out.println("The take is empty/headers-only — is a microphone available?")
           else answer(clip)
    yield ()

  private def answer[G[_]](clip: AudioClip)(using C: Chat[G], Out: DemoConsole[G]): Free[G, Unit] =
    val req = ChatCompletionRequest(
      model = model,
      // Multimodal message: the raw audio part + a text instruction part.
      messages = List(ChatMessage(Role.User, List(
        ContentPart.audio(clip),
        ContentPart.Text("Listen to this audio and respond to what I said. If it is a question, answer it directly.")
      ))),
      max_tokens = Some(8192) // thinking model: reasoning + answer share this budget (128k slot ceiling)
    )
    for
      _     <- Out.println(s"Sending ${clip.bytes.size / 1024} KB of audio directly to Gemma-4 12B (audio modality)…")
      reply <- C.ask(req)
      // Gemma-4 emits its reasoning in `reasoning_content`; the final answer lands in `content`. If it ran out of
      // tokens mid-thought, content is empty — fall back to the reasoning so we always show something.
      answer = reply.map(_.text).filter(_.nonEmpty)
                 .orElse(reply.flatMap(_.reasoning_content).filter(_.nonEmpty))
                 .getOrElse("[No response]")
      _     <- Out.println(s"\n--- Gemma-4 12B (heard your audio) ---\n$answer\n--------------------------------------\n")
    yield ()

  def run(args: List[String]): IO[ExitCode] =
    // Push-to-talk hangs under sbt's forked run (stdin isn't forwarded), so takes are fixed-length.
    val source: IO[Either[Int, AudioClip]] = args.headOption match
      case Some(n) if n.toIntOption.isDefined => IO.pure(Left(n.toInt))
      case Some(p) =>
        IO.println(s"Using audio file: $p") >>
          Files[IO].readAll(Path(p)).compile.to(ByteVector).map(b => Right(AudioClip(b, AudioEncoding.Wav)))
      case None => IO.pure(Left(defaultSeconds))

    (OpenAIHttp4s.client[IO], ProcessAudioDevice[IO](ProcessAudioConfig(speaker = None)), Resource.eval(Multiparts.forSync[IO]))
      .tupled
      .use { (client, device, multiparts) =>
        val interp = DemoConsole.interpreter[IO].or(OpenAIHttp4s.withDevice(client, endpoints, multiparts, device))
        source.flatMap(askByVoice[DemoOp](_).foldMap(interp))
      }
      .as(ExitCode.Success)
