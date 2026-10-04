package openai.enums

import io.circe.{Decoder, Encoder, Json}

enum StreamEnabled:
  case Yes
  case No

object StreamEnabled:
  given Encoder[StreamEnabled] = Encoder.instance:
    case Yes => Json.fromBoolean(true)
    case No  => Json.fromBoolean(false)
  given Decoder[StreamEnabled] = Decoder.decodeBoolean.map:
    case true  => Yes
    case false => No
