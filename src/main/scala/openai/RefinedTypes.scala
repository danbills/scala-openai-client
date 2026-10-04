package openai

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

object RefinedTypes:
  type NonEmptyString = String :| Not[Empty]
  type BaseUrl        = String :| Not[Empty] // e.g. "http://localhost:8080/v1"
  type NonNegativeInt = Int    :| GreaterEqual[0]
  type MaxTokens      = Int    :| Greater[0]
  type Temperature    = Double :| (GreaterEqual[0.0] & LessEqual[2.0])
  type SampleRate     = Int    :| Greater[0]
  type ChannelCount   = Int    :| Greater[0]
  type SpeechSpeed    = Double :| (GreaterEqual[0.25] & LessEqual[4.0])
