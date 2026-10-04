package openai.interpreters

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.free.Free
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import openai.RefinedTypes.*
import openai.enums.{FinishReason, Role}
import openai.free.*
import openai.models.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.multipart.{Multipart, Multiparts}
import org.scalatest.funsuite.AnyFunSuite
import org.typelevel.ci.CIStringSyntax
import scodec.bits.ByteVector

/** The http4s interpreters against an in-memory server: real requests, real response decoding, no network. */
class Http4sInterpreterTest extends AnyFunSuite:

  val model: NonEmptyString = "test-model"
  val endpoints = OpenAIHttp4s.Endpoints(
    chat = Endpoint("http://chat.test/v1", "sk-chat"),
    transcription = Endpoint("http://stt.test/v1/", "sk-stt"),
    speech = Endpoint("http://tts.test/v1", "sk-tts")
  )

  /** What the fake server saw: host+path, Authorization, and the body as JSON or multipart parts. */
  final case class Seen(target: String, auth: String, json: Option[Json], parts: List[(String, Option[String], String)])

  def sse(lines: String*): Response[IO] =
    Response[IO](Status.Ok).withEntity(lines.map(l => s"data: $l\n\n").mkString + "data: [DONE]\n\n")

  def run[A](program: Free[OpenAIStreamingOp, A])(respond: PartialFunction[String, Response[IO]]): (List[A], List[Seen]) =
    (for
      seen       <- Ref.of[IO, List[Seen]](Nil)
      multiparts <- Multiparts.forSync[IO]
      app = HttpApp[IO] { req =>
        val target = s"${req.uri.host.fold("")(_.value)}${req.uri.path}"
        val auth   = req.headers.get(ci"Authorization").fold("")(_.head.value)
        val body =
          if req.uri.path.renderString.endsWith("transcriptions") then
            req.as[Multipart[IO]].flatMap(_.parts.toList.traverse(p =>
              p.bodyText.compile.string.map(t => (p.name.getOrElse(""), p.filename, t)))).map(ps => Seen(target, auth, None, ps))
          else req.as[Json].map(j => Seen(target, auth, Some(j), Nil))
        body.flatMap(s => seen.update(_ :+ s)).as(respond.applyOrElse(target, _ => Response[IO](Status.NotFound)))
      }
      interp = OpenAIHttp4s.streaming(Client.fromHttpApp(app), endpoints, multiparts)
      out <- program.foldMap(interp).compile.toList
      all <- seen.get
    yield (out, all)).unsafeRunSync()


  def response(text: String): ChatCompletionResponse =
    ChatCompletionResponse("cmpl-1", model, "chat.completion", 0L,
      choices = List(Choice(0, ChatMessage(Role.Assistant, text), FinishReason.Stop)))

  test("chat completion: authorized POST, stream flag cleared, response decoded") {
    val req = ChatCompletionRequest(model, List(ChatMessage(Role.User, "hi")), stream = Some(openai.enums.StreamEnabled.Yes))
    val (out, seen) = run(Chat[OpenAIStreamingOp].ask(req)) {
      case "chat.test/v1/chat/completions" => Response[IO](Status.Ok).withEntity(response("hello").asJson)
    }
    assert(out == List(Some(ChatMessage(Role.Assistant, "hello"))))
    assert(seen.map(s => (s.target, s.auth)) == List(("chat.test/v1/chat/completions", "Bearer sk-chat")))
    assert(seen.head.json.exists(j => j.hcursor.downField("stream").failed && j.hcursor.get[String]("model") == Right("test-model")))
  }

  test("non-2xx becomes OpenAIError.Http with the body") {
    val err = intercept[OpenAIError](run(Chat[OpenAIStreamingOp].complete(ChatCompletionRequest(model, Nil))) {
      case _ => Response[IO](Status.TooManyRequests).withEntity("slow down")
    })
    assert(err == OpenAIError.Http(429, "slow down"))
  }

  test("chat stream: deltas in order, then one Done assembled from them") {
    val chunks = List(
      """{"id":"c","object":"chunk","created":0,"model":"m","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}""",
      """{"id":"c","object":"chunk","created":0,"model":"m","choices":[{"index":0,"delta":{"content":"lo","audio":{"id":"a1","data":"AQI=","transcript":"Hello"}}}]}""",
      """{"id":"c","object":"chunk","created":0,"model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"t1","function":{"name":"f","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
      """{"id":"c","object":"chunk","created":0,"model":"m","choices":[]}"""
    )
    val program = ChatStreaming[OpenAIStreamingOp].events(ChatCompletionRequest(model, Nil))
    val (out, seen) = run(program) { case "chat.test/v1/chat/completions" => sse(chunks*) }
    assert(out.collect { case ChatStreamEvent.Delta(d) => d.content.getOrElse("") } == List("Hel", "lo", ""))
    assert(out.last == ChatStreamEvent.Done(
      ChatMessage(Role.Assistant, "Hello", tool_calls = Some(List(ToolCall("t1", "f", "{}"))),
        audio = Some(AssistantAudio("a1", ByteVector(1, 2), "Hello"))),
      Some("tool_calls")))
    assert(seen.head.json.exists(_.hcursor.get[Boolean]("stream") == Right(true)))
  }

  test("chat stream: a malformed chunk fails the stream") {
    val err = intercept[OpenAIError](run(ChatStreaming[OpenAIStreamingOp].deltas(ChatCompletionRequest(model, Nil))) {
      case _ => sse("{not json")
    })
    assert(err.isInstanceOf[OpenAIError.BadStreamChunk])
  }

  test("speak: whole clip in the requested encoding, from the speech server") {
    val (out, seen) = run(Audio[OpenAIStreamingOp].speak(TextToSpeechRequest("hi", responseFormat = AudioEncoding.Pcm16()))) {
      case "tts.test/v1/audio/speech" => Response[IO](Status.Ok).withEntity(Array[Byte](1, 2, 3))
    }
    assert(out == List(AudioClip(ByteVector(1, 2, 3), AudioEncoding.Pcm16())))
    assert(seen.head.auth == "Bearer sk-tts")
    assert(seen.head.json.exists(_.hcursor.get[String]("response_format") == Right("pcm")))
  }

  test("speech stream: body bytes arrive as Bytes events, then one Done") {
    val body = ByteVector.fill(20000)(7)
    val (out, _) = run(SpeechStreaming[OpenAIStreamingOp].events(TextToSpeechRequest("hi"))) {
      case "tts.test/v1/audio/speech" => Response[IO](Status.Ok).withEntity(body.toArray)
    }
    assert(out.last == SpeechEvent.Done)
    assert(out.collect { case SpeechEvent.Bytes(b) => b }.foldLeft(ByteVector.empty)(_ ++ _) == body)
  }

  test("transcribe an in-memory clip: multipart file + fields, trailing slash in base URL tolerated") {
    val clip = AudioClip(ByteVector("RIFFdata".getBytes), AudioEncoding.Wav)
    val req  = AudioTranscriptionRequest(AudioInput.Inline(clip), language = Some("en"))
    val (out, seen) = run(Audio[OpenAIStreamingOp].transcribe(req)) {
      case "stt.test/v1/audio/transcriptions" => Response[IO](Status.Ok).withEntity(Json.obj("text" -> "ask not".asJson))
    }
    assert(out == List(AudioTranscriptionResponse("ask not")))
    assert(seen.head.auth == "Bearer sk-stt")
    assert(seen.head.parts == List(("file", Some("audio.wav"), "RIFFdata"), ("model", None, "whisper-1"), ("language", None, "en")))
  }
