package openai.enums

import io.circe.{Decoder, Encoder}

enum Role(val value: String):
  case System    extends Role("system")
  case User      extends Role("user")
  case Assistant extends Role("assistant")
  case Tool      extends Role("tool")

object Role:
  given Encoder[Role] = Encoder.encodeString.contramap(_.value)
  given Decoder[Role] = Decoder.decodeString.emap: s =>
    Role.values.find(_.value == s).toRight(s"Invalid role: $s")
