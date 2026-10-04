package openai.interpreters

import cats.effect.{Async, Concurrent, Resource}
import cats.~>
import fs2.Stream
import fs2.io.file.Files
import fs2.io.net.Network
import openai.audio.AudioDeviceOp
import openai.free.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.multipart.Multiparts

/** The http4s interpreters, composed for the default coproducts. */
object OpenAIHttp4s:

  /** Which server answers each endpoint family. */
  final case class Endpoints(chat: Endpoint, transcription: Endpoint, speech: Endpoint)

  object Endpoints:
    def single(endpoint: Endpoint): Endpoints = Endpoints(endpoint, endpoint, endpoint)

  /** The only place that needs `Async` and `Network`: building the Ember client. */
  def client[F[_]: Async: Network]: Resource[F, Client[F]] = EmberClientBuilder.default[F].build

  /** Single-shot algebras into `F`. */
  def apply[F[_]: Concurrent: Files](
    client: Client[F],
    endpoints: Endpoints,
    multiparts: Multiparts[F]
  ): OpenAIOp ~> F =
    Http4sChat(client, endpoints.chat).or(Http4sAudio(client, endpoints.transcription, endpoints.speech, multiparts))

  /** [[apply]] plus a microphone/speaker interpreter, e.g. `openai.audio.ProcessAudioDevice`. */
  def withDevice[F[_]: Concurrent: Files](
    client: Client[F],
    endpoints: Endpoints,
    multiparts: Multiparts[F],
    device: AudioDeviceOp ~> F
  ): OpenAIDeviceOp ~> F =
    device.or(apply(client, endpoints, multiparts))

  /** Every API algebra into `Stream[F, *]`; single-shot ops become one-element streams. */
  def streaming[F[_]: Concurrent: Files](
    client: Client[F],
    endpoints: Endpoints,
    multiparts: Multiparts[F]
  ): OpenAIStreamingOp ~> Stream[F, *] =
    import StreamInterpreters.{control, lift}
    lift(Http4sChat(client, endpoints.chat)).or(
      lift(Http4sAudio(client, endpoints.transcription, endpoints.speech, multiparts)).or(
        Http4sChat.stream(client, endpoints.chat).or(
          Http4sAudio.stream(client, endpoints.speech).or(control[F]))))

  /** [[streaming]] plus a microphone/speaker interpreter, e.g. `openai.audio.ProcessAudioDevice`. */
  def voice[F[_]: Concurrent: Files](
    client: Client[F],
    endpoints: Endpoints,
    multiparts: Multiparts[F],
    device: AudioDeviceOp ~> F
  ): VoiceOp ~> Stream[F, *] =
    StreamInterpreters.lift(device).or(streaming(client, endpoints, multiparts))
