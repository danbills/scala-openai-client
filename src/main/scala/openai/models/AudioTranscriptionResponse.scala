package openai.models

import io.circe.Codec

case class AudioTranscriptionResponse(text: String) derives Codec.AsObject
