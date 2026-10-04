package openai.interpreters

import cats.effect.Concurrent
import cats.syntax.all.*
import cats.~>
import fs2.io.file.Files
import fs2.{Chunk, Stream}
import io.circe.syntax.*
import openai.free.{AudioOp, SpeechEvent, SpeechStreamOp}
import openai.models.*
import org.http4s.Response
import org.http4s.circe.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.multipart.{Multiparts, Part}
import scodec.bits.ByteVector

/** `/audio/transcriptions` and `/audio/speech` over http4s. */
object Http4sAudio:

  /**
   * `Files[F]` reads `AudioInput.FromFile`; the caller supplies `Multiparts[F]` (e.g. `Multiparts.forSync`), so
   * this needs `Concurrent`, not `Sync`.
   */
  def apply[F[_]: Concurrent: Files](
    client: Client[F],
    transcription: Endpoint,
    speech: Endpoint,
    multiparts: Multiparts[F]
  ): AudioOp ~> F = new (AudioOp ~> F):
    def apply[A](op: AudioOp[A]): F[A] = op match
      case AudioOp.Transcribe(request) =>
        val file = request.audio match
          case AudioInput.FromFile(path) => Part.fileData[F]("file", path.fileName.toString, Files[F].readAll(path))
          case AudioInput.Inline(clip)   =>
            Part.fileData[F]("file", s"audio.${clip.encoding.fileExtension}", Stream.chunk(Chunk.byteVector(clip.bytes)))
        val fields = Vector(Part.formData[F]("model", request.model)) ++
          request.language.map(Part.formData[F]("language", _)) ++
          request.prompt.map(Part.formData[F]("prompt", _))
        for
          multipart <- multiparts.multipart(file +: fields)
          req       <- transcription.post[F]("/audio/transcriptions")
          resp      <- client.expectOr[AudioTranscriptionResponse](
                         req.withEntity(multipart).putHeaders(multipart.headers))(OpenAIError.from)
        yield resp

      case AudioOp.Speak(request) =>
        speech.post[F]("/audio/speech").flatMap { req =>
          Responses.stream(client, req.withEntity(request.asJson))
            .evalMap(resp => resp.body.compile.to(ByteVector).map(AudioClip(_, encodingOf(resp, request.responseFormat))))
            .compile
            .lastOrError
        }

  /**
   * The encoding the server actually sent, from `Content-Type`; the requested one when the header is missing or
   * not specific. Servers that ignore `response_format` (e.g. NeMo FastPitch, always WAV) still get a playable clip.
   */
  private def encodingOf[F[_]](resp: Response[F], requested: AudioEncoding): AudioEncoding =
    resp.contentType.map(_.mediaType).filter(_.mainType == "audio").map(_.subType) match
      case Some("wav" | "x-wav" | "wave" | "vnd.wave") => AudioEncoding.Wav
      case Some("mpeg" | "mp3")                        => AudioEncoding.Mp3
      case Some("flac" | "x-flac")                     => AudioEncoding.Flac
      case Some("opus" | "ogg")                        => AudioEncoding.Opus
      case Some("aac")                                 => AudioEncoding.Aac
      case _                                           => requested

  /** Streamed TTS: a [[SpeechEvent.Bytes]] per body chunk as the server sends it, then one [[SpeechEvent.Done]]. */
  def stream[F[_]: Concurrent](client: Client[F], speech: Endpoint): SpeechStreamOp ~> Stream[F, *] =
    new (SpeechStreamOp ~> Stream[F, *]):
      def apply[A](op: SpeechStreamOp[A]): Stream[F, A] = op match
        case SpeechStreamOp.Events(request) =>
          Stream
            .eval(speech.post[F]("/audio/speech"))
            .flatMap(req => Responses.stream(client, req.withEntity(request.asJson)))
            .flatMap(_.body.chunks.map(c => SpeechEvent.Bytes(c.toByteVector))) ++
            Stream.emit(SpeechEvent.Done)
