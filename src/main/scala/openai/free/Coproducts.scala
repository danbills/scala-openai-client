package openai.free

import cats.data.EitherK
import cats.free.Free
import openai.audio.AudioDeviceOp

// Default coproducts. Right-nested named aliases: InjectK derivation
// walks EitherK's right spine. Programs written against the capabilities (`Chat[G]`, `Audio[G]`,
// `ChatStreaming[G]`, `SpeechStreaming[G]`, `AudioDevice[G]`) run in these or in any user coproduct that adds
// its own algebras (UI, console, tools).

/** Single-shot API algebras: interpretable into any monad `F`. */
type OpenAIOp[A]      = EitherK[ChatOp, AudioOp, A]
type OpenAIProgram[A] = Free[OpenAIOp, A]

/** Single-shot API algebras plus the local microphone and speaker: still interpretable into any monad `F`. */
type OpenAIDeviceOp[A]      = EitherK[AudioDeviceOp, OpenAIOp, A]
type OpenAIDeviceProgram[A] = Free[OpenAIDeviceOp, A]

/** Streaming chat alone: needs a `Stream[F, *]` interpreter (`Http4sChat.streaming`). */
type ChatStreamingOp[A] = EitherK[ChatStreamOp, StreamControlOp, A]

/** Multi-valued API algebras: need a `Stream[F, *]` interpreter. */
type SpeechControlOp[A] = EitherK[SpeechStreamOp, StreamControlOp, A]
type StreamingOp[A]     = EitherK[ChatStreamOp, SpeechControlOp, A]

/** Every API algebra. */
type AudioStreamingOp[A]       = EitherK[AudioOp, StreamingOp, A]
type OpenAIStreamingOp[A]      = EitherK[ChatOp, AudioStreamingOp, A]
type OpenAIStreamingProgram[A] = Free[OpenAIStreamingOp, A]

/** Every API algebra plus the local microphone and speaker: voice-to-voice programs. */
type VoiceOp[A]      = EitherK[AudioDeviceOp, OpenAIStreamingOp, A]
type VoiceProgram[A] = Free[VoiceOp, A]
