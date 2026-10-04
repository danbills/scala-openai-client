package openai.free

import cats.InjectK
import cats.data.{EitherK, State}
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.free.Free
import cats.~>
import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import openai.RefinedTypes.*
import openai.audio.{AudioDevice, AudioDeviceOp, RecordLimit}
import openai.enums.{FinishReason, Role}
import openai.models.*
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

import scala.concurrent.duration.*

// A user-defined algebra, mixed into the library's coproducts below.
enum ConsoleOp[A]:
  case Print(s: String) extends ConsoleOp[Unit]

final class Console[G[_]](using InjectK[ConsoleOp, G]):
  def print(s: String): Free[G, Unit] = Free.liftInject[G](ConsoleOp.Print(s))

object Console:
  given [G[_]](using InjectK[ConsoleOp, G]): Console[G] = new Console[G]

type DeviceOpenAIOp[A]     = EitherK[AudioDeviceOp, OpenAIOp, A]
type ConsoleStreamingOp[A] = EitherK[ConsoleOp, StreamingOp, A]
type AppOp[A]              = EitherK[ConsoleOp, VoiceOp, A]

object Programs:
  val model: NonEmptyString = "qwen3.8-27b"

  def textOf(m: ChatMessage): Option[String] = m.content match
    case s: String => Some(s)
    case _         => None

  /** Record, transcribe, ask, speak the answer. All single-shot, so it runs in any monad. */
  def narrate[G[_]](using D: AudioDevice[G], A: Audio[G], C: Chat[G]): Free[G, Option[String]] =
    for
      take    <- D.record(RecordLimit.For(5.seconds))
      heard   <- A.transcribe(AudioTranscriptionRequest(take))
      replied <- C.ask(ChatCompletionRequest(model, List(ChatMessage(Role.User, heard.text))))
      text     = replied.flatMap(textOf).flatMap(_.refineOption[Not[Empty]])
      _       <- text.fold(Free.pure[G, Unit](()))(t => A.speak(TextToSpeechRequest(input = t)).flatMap(D.play))
    yield text

  /** Print tokens as they arrive, then continue once with the assembled reply. */
  def streamToConsole[G[_]](req: ChatCompletionRequest)(using S: ChatStreaming[G], Out: Console[G])
      : Free[G, ChatMessage] =
    for
      done <- S.onDeltas(req)(d => d.content.fold(Free.pure[G, Unit](()))(Out.print))
      _    <- Out.print("|done")
    yield done.message

  /** Voice to voice: the model hears the take and answers in speech, played as it streams in. */
  def voiceTurn[G[_]](using D: AudioDevice[G], S: ChatStreaming[G]): Free[G, ChatMessage] =
    for
      take <- D.record(RecordLimit.UntilStopped)
      req   = ChatCompletionRequest(
                model,
                List(ChatMessage(Role.User, List(ContentPart.audio(take)))),
                modalities = Some(List(Modality.Text, Modality.Audio)),
                audio = Some(AudioOutput("alloy"))
              )
      done <- S.onDeltas(req)(d => d.audio.flatMap(_.data).fold(Free.pure[G, Unit](()))(D.write))
    yield done.message

  /** Streamed TTS straight to the speaker, then one more action once synthesis has finished. */
  def sayStreaming[G[_]](text: NonEmptyString)(using S: SpeechStreaming[G], D: AudioDevice[G], Out: Console[G])
      : Free[G, Unit] =
    S.onBytes(TextToSpeechRequest(text, responseFormat = AudioEncoding.Pcm16()))(D.write)
      .flatMap(_ => Out.print("spoken"))

class FreeAlgebraTest extends AnyFunSuite:
  import Programs.*

  type Log[A] = State[List[String], A]

  val take: AudioClip = AudioClip(ByteVector(1, 2, 3), AudioEncoding.Wav)

  def response(text: String): ChatCompletionResponse =
    ChatCompletionResponse(
      id = "cmpl-1",
      model = model,
      `object` = "chat.completion",
      created = 0L,
      choices = List(Choice(0, ChatMessage(Role.Assistant, text), FinishReason.Stop))
    )

  val chatLog: ChatOp ~> Log = new (ChatOp ~> Log):
    def apply[A](op: ChatOp[A]): Log[A] = op match
      case ChatOp.Complete(req) => State(log => (log :+ s"chat:${req.model}", response("ask not")))

  val audioLog: AudioOp ~> Log = new (AudioOp ~> Log):
    def apply[A](op: AudioOp[A]): Log[A] = op match
      case AudioOp.Transcribe(req) =>
        State(log => (log :+ s"transcribe:${req.audio}", AudioTranscriptionResponse("my fellow Americans")))
      case AudioOp.Speak(req) =>
        State(log => (log :+ s"speak:${req.input}", AudioClip(ByteVector(9), req.responseFormat)))

  val deviceLog: AudioDeviceOp ~> Log = new (AudioDeviceOp ~> Log):
    def apply[A](op: AudioDeviceOp[A]): Log[A] = op match
      case AudioDeviceOp.Record(limit) => State(log => (log :+ s"record:$limit", take))
      case AudioDeviceOp.Play(clip)    => State(log => (log :+ s"play:${clip.bytes.toHex}", ()))
      case AudioDeviceOp.Write(pcm)    => State(log => (log :+ s"write:${pcm.toHex}", ()))

  test("single-shot program interprets into State, no cats-effect") {
    val (log, spoken) = narrate[DeviceOpenAIOp].foldMap(deviceLog.or(chatLog.or(audioLog))).run(Nil).value
    assert(spoken.contains("ask not"))
    assert(log == List(
      "record:For(5 seconds)",
      s"transcribe:${AudioInput.Inline(take)}",
      s"chat:$model",
      "speak:ask not",
      "play:09"
    ))
  }

  test("programs build in any coproduct holding their algebras, and only those") {
    assertCompiles("""narrate[VoiceOp]""")
    assertCompiles("""narrate[AppOp]""")
    assertCompiles("""voiceTurn[VoiceOp]""")
    assertCompiles("""streamToConsole[AppOp](ChatCompletionRequest(model, Nil))""")
    assertCompiles("""sayStreaming[AppOp]("hi")""")
    // OpenAIOp holds no streaming, device or console algebra.
    assertTypeError("""streamToConsole[OpenAIOp](ChatCompletionRequest(model, Nil))""")
    assertTypeError("""narrate[OpenAIOp]""")
    // VoiceOp has no ConsoleOp.
    assertTypeError("""sayStreaming[VoiceOp]("hi")""")
  }

  // --- Stream[IO, *] target: canned stand-ins for the http4s and device interpreters ---

  val deltas: List[DeltaContent] = List(
    DeltaContent(role = Some(Role.Assistant), reasoning_content = Some("think")),
    DeltaContent(content = Some("Hel")),
    DeltaContent(content = Some("lo")),
    DeltaContent(tool_calls = Some(List(ToolCallDelta(0, Some("call_1"), Some("lookup"), Some("{\"q\":"))))),
    DeltaContent(tool_calls = Some(List(ToolCallDelta(0, None, None, Some("\"x\"}"))))),
    DeltaContent(audio = Some(AudioDelta(Some("audio_1"), Some(ByteVector(0xa, 0xb)), Some("Hel")))),
    DeltaContent(audio = Some(AudioDelta(None, Some(ByteVector(0xc)), Some("lo"))))
  )

  def chatStream(seen: Ref[IO, List[ChatCompletionRequest]]): ChatStreamOp ~> Stream[IO, *] =
    new (ChatStreamOp ~> Stream[IO, *]):
      def apply[A](op: ChatStreamOp[A]): Stream[IO, A] = op match
        case ChatStreamOp.Events(req) =>
          Stream.exec(seen.update(_ :+ req)) ++
            Stream.emits(deltas.map(ChatStreamEvent.Delta(_))) ++
            Stream.emit(ChatStreamEvent.Done(ChatStreamAccumulator.fold(deltas), Some("stop")))

  val speechStream: SpeechStreamOp ~> Stream[IO, *] = new (SpeechStreamOp ~> Stream[IO, *]):
    def apply[A](op: SpeechStreamOp[A]): Stream[IO, A] = op match
      case SpeechStreamOp.Events(_) =>
        Stream(SpeechEvent.Bytes(ByteVector(1)), SpeechEvent.Bytes(ByteVector(2)), SpeechEvent.Done)

  def consoleTo(out: Ref[IO, Vector[String]]): ConsoleOp ~> IO = new (ConsoleOp ~> IO):
    def apply[A](op: ConsoleOp[A]): IO[A] = op match
      case ConsoleOp.Print(s) => out.update(_ :+ s)

  def deviceTo(out: Ref[IO, Vector[String]]): AudioDeviceOp ~> IO = new (AudioDeviceOp ~> IO):
    def apply[A](op: AudioDeviceOp[A]): IO[A] = op match
      case AudioDeviceOp.Record(_)  => out.update(_ :+ "record").as(take)
      case AudioDeviceOp.Play(clip) => out.update(_ :+ s"play:${clip.bytes.toHex}")
      case AudioDeviceOp.Write(pcm) => out.update(_ :+ s"write:${pcm.toHex}")

  val unusedChat: ChatOp ~> IO = new (ChatOp ~> IO):
    def apply[A](op: ChatOp[A]): IO[A] = IO.raiseError(new AssertionError(s"unexpected $op"))

  val unusedAudio: AudioOp ~> IO = new (AudioOp ~> IO):
    def apply[A](op: AudioOp[A]): IO[A] = IO.raiseError(new AssertionError(s"unexpected $op"))

  /** Every algebra of [[AppOp]] into `Stream[IO, *]`, logging console and device effects to `out`. */
  def appInterpreter(out: Ref[IO, Vector[String]], seen: Ref[IO, List[ChatCompletionRequest]])
      : AppOp ~> Stream[IO, *] =
    import StreamInterpreters.{control, lift}
    lift(consoleTo(out)).or(lift(deviceTo(out)).or(lift(unusedChat).or(lift(unusedAudio).or(
      chatStream(seen).or(speechStream.or(control[IO]))))))

  def runApp[A](program: Free[AppOp, A]): (List[A], Vector[String], List[ChatCompletionRequest]) =
    (for
      out     <- Ref.of[IO, Vector[String]](Vector.empty)
      seen    <- Ref.of[IO, List[ChatCompletionRequest]](Nil)
      results <- program.foldMap(appInterpreter(out, seen)).compile.toList
      logged  <- out.get
      reqs    <- seen.get
    yield (results, logged, reqs)).unsafeRunSync()

  test("onDeltas runs per delta, then continues exactly once with the assembled reply") {
    val (results, printed, _) = runApp(streamToConsole[AppOp](ChatCompletionRequest(model, Nil)))
    assert(printed == Vector("Hel", "lo", "|done"))
    assert(results.size == 1)
    assert(results.head.content == "Hello")
    assert(results.head.reasoning_content.contains("think"))
    assert(results.head.tool_calls.contains(List(ToolCall("call_1", "lookup", "{\"q\":\"x\"}"))))
  }

  test("voice to voice: recorded audio goes in, spoken reply streams to the speaker") {
    val (results, logged, reqs) = runApp(voiceTurn[AppOp])
    assert(logged == Vector("record", "write:0a0b", "write:0c"))
    assert(reqs.head.messages.head.content == List(ContentPart.InputAudio(take.base64, "wav")))
    assert(reqs.head.modalities.contains(List(Modality.Text, Modality.Audio)))
    assert(results.map(_.audio) == List(Some(AssistantAudio("audio_1", ByteVector(0xa, 0xb, 0xc), "Hello"))))
  }

  test("streamed TTS writes each chunk, then continues once") {
    val (results, logged, _) = runApp(sayStreaming[AppOp]("hello"))
    assert(logged == Vector("write:01", "write:02", "spoken"))
    assert(results == List(()))
  }

  test("deltas fans out and drops Done") {
    val (results, _, _) = runApp(ChatStreaming[AppOp].deltas(ChatCompletionRequest(model, Nil)))
    assert(results == deltas)
  }

  // --- Pure models ---

  test("accumulator assembles interleaved tool calls by index") {
    val msg = ChatStreamAccumulator.fold(List(
      DeltaContent(tool_calls = Some(List(ToolCallDelta(1, Some("b"), Some("g"), Some("[1"))))),
      DeltaContent(tool_calls = Some(List(ToolCallDelta(0, Some("a"), Some("f"), Some("{"))))),
      DeltaContent(tool_calls = Some(List(ToolCallDelta(1, None, None, Some("]")), ToolCallDelta(0, None, None, Some("}")))))
    ))
    assert(msg.role == Role.Assistant)
    assert(msg.content == "")
    assert(msg.reasoning_content.isEmpty)
    assert(msg.audio.isEmpty)
    assert(msg.tool_calls.contains(List(ToolCall("a", "f", "{}"), ToolCall("b", "g", "[1]"))))
  }

  test("audio wire format") {
    val chatReq = ChatCompletionRequest(
      model, Nil, modalities = Some(List(Modality.Text, Modality.Audio)), audio = Some(AudioOutput("alloy"))).asJson
    assert(chatReq.hcursor.downField("modalities").as[List[String]] == Right(List("text", "audio")))
    assert(chatReq.hcursor.downField("audio").downField("format").as[String] == Right("pcm16"))

    val tts = TextToSpeechRequest("hi", responseFormat = AudioEncoding.Pcm16(), speed = Some(1.5)).asJson
    assert(tts.hcursor.downField("response_format").as[String] == Right("pcm"))
    assert(tts.hcursor.downField("speed").as[Double] == Right(1.5))
    assert(tts.hcursor.downField("instructions").failed)

    val reply = decode[ChatMessage](
      """{"role":"assistant","content":null,"audio":{"id":"audio_1","data":"CgsM","transcript":"Hello","expires_at":7}}""")
    val audio = AssistantAudio("audio_1", ByteVector(0xa, 0xb, 0xc), "Hello", Some(7L))
    assert(reply.map(_.audio) == Right(Some(audio)))
    // Sent back in history by id only.
    assert(reply.map(_.asJson.hcursor.downField("audio").focus.map(_.noSpaces)) == Right(Some("""{"id":"audio_1"}""")))

    assert(decode[DeltaContent]("""{"audio":{"data":"AQI=","transcript":"Hi"}}""") ==
      Right(DeltaContent(audio = Some(AudioDelta(None, Some(ByteVector(1, 2)), Some("Hi"))))))
    assert(decode[DeltaContent]("""{"audio":{"data":"not base64!"}}""").isLeft)
  }
