package openai.demo

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.free.Free
import cats.syntax.all.*
import fs2.io.file.Path
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import openai.RefinedTypes.*
import openai.audio.{AudioDevice, ProcessAudioConfig, ProcessAudioDevice}
import openai.enums.Role
import openai.free.{Audio, Chat}
import openai.interpreters.OpenAIHttp4s
import openai.models.*
import org.http4s.multipart.Multiparts

/** End-to-end voice narration.
  *
  * Pipeline: WAV file → Whisper ASR (port 8766) → LLM summary (port 8080) → NeMo TTS (port 8767) → speaker
  *
  * The pipeline is a program over `Audio`, `Chat`, `AudioDevice` and the demo's own `DemoConsole`; `run`
  * interprets it with the http4s interpreters and `ProcessAudioDevice` (aplay).
  *
  * Run with: sbt "demo/runMain openai.demo.VoiceNarrationDemo [file.wav]"
  *
  * Servers come from LLM_URL, WHISPER_URL and TTS_URL (see [[DemoEnv]]).
  */
object VoiceNarrationDemo extends IOApp:

  val endpoints             = DemoEnv.endpoints
  val model: NonEmptyString = DemoEnv.model("gemma-4-12b-it-qat-q4_0.gguf")

  def narrate[G[_]](wav: Path)(using A: Audio[G], C: Chat[G], D: AudioDevice[G], Out: DemoConsole[G])
      : Free[G, Unit] =
    for
      _          <- Out.println("[1/3] Transcribing audio via Whisper...")
      transcript <- A.transcribe(AudioTranscriptionRequest(wav))
      _          <- Out.println(s"      Transcript: ${transcript.text}")

      _       <- Out.println("[2/3] Summarising via LLM...")
      reply   <- C.ask(ChatCompletionRequest(
                   model = model,
                   messages = List(
                     ChatMessage(Role.System,
                       "You are a concise voice assistant. Summarise the user's message in one or two natural spoken sentences. No markdown, no lists."),
                     ChatMessage(Role.User, transcript.text)
                   )
                 ))
      summary  = reply.map(_.text).filter(_.nonEmpty).getOrElse(transcript.text)
      _       <- Out.println(s"      Summary: $summary")

      _ <- summary.refineOption[Not[Empty]] match
             case None       => Out.println("      Nothing to narrate.")
             case Some(text) =>
               Out.println("[3/3] Narrating via NeMo FastPitch TTS...") >>
                 A.speak(TextToSpeechRequest(input = text)).flatMap(D.play)
      _ <- Out.println("      Done.")
    yield ()

  def run(args: List[String]): IO[ExitCode] =
    val wav = Path(args.headOption.getOrElse("jfk.wav"))
    (OpenAIHttp4s.client[IO], ProcessAudioDevice[IO](ProcessAudioConfig(speaker = None)), Resource.eval(Multiparts.forSync[IO]))
      .tupled
      .use { (client, device, multiparts) =>
        val interp = DemoConsole.interpreter[IO].or(OpenAIHttp4s.withDevice(client, endpoints, multiparts, device))
        narrate[DemoOp](wav).foldMap(interp)
      }
      .as(ExitCode.Success)
