package openai.free

import cats.InjectK
import cats.free.Free
import openai.models.*

/** The audio endpoints as data: transcription (`/audio/transcriptions`) and whole-clip TTS (`/audio/speech`). */
enum AudioOp[A]:
  case Transcribe(request: AudioTranscriptionRequest) extends AudioOp[AudioTranscriptionResponse]
  case Speak(request: TextToSpeechRequest)            extends AudioOp[AudioClip]

/** Smart constructors for [[AudioOp]] into any coproduct `G` that contains it. */
final class Audio[G[_]](using InjectK[AudioOp, G]):
  def transcribe(request: AudioTranscriptionRequest): Free[G, AudioTranscriptionResponse] =
    Free.liftInject[G](AudioOp.Transcribe(request))

  def speak(request: TextToSpeechRequest): Free[G, AudioClip] =
    Free.liftInject[G](AudioOp.Speak(request))

object Audio:
  given [G[_]](using InjectK[AudioOp, G]): Audio[G] = new Audio[G]
  def apply[G[_]](using a: Audio[G]): Audio[G]      = a
