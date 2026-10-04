package openai.models

import fs2.io.file.Path
import io.github.iltotore.iron.autoRefine
import openai.RefinedTypes.NonEmptyString

case class AudioTranscriptionRequest(
  audio: AudioInput,
  model: NonEmptyString = "whisper-1",
  /** ISO-639-1 hint, e.g. `en`. */
  language: Option[NonEmptyString] = None,
  /** Text that biases spelling and style (names, jargon). */
  prompt: Option[NonEmptyString] = None
)

object AudioTranscriptionRequest:
  def apply(file: Path): AudioTranscriptionRequest   = AudioTranscriptionRequest(AudioInput.FromFile(file))
  def apply(clip: AudioClip): AudioTranscriptionRequest = AudioTranscriptionRequest(AudioInput.Inline(clip))
