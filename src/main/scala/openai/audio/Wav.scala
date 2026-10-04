package openai.audio

import openai.models.{AudioClip, AudioEncoding}
import scodec.bits.{ByteOrdering, ByteVector}

object Wav:
  /** Wrap raw 16-bit PCM in a 44-byte RIFF/WAVE header with the true data length. */
  def fromPcm16(pcm: ByteVector, spec: AudioEncoding.Pcm16): AudioClip =
    def le32(n: Long): ByteVector = ByteVector.fromInt(n.toInt, 4, ByteOrdering.LittleEndian)
    def le16(n: Int): ByteVector  = ByteVector.fromInt(n, 2, ByteOrdering.LittleEndian)
    val blockAlign                = spec.channels * 2
    val header =
      ByteVector("RIFF".getBytes) ++ le32(36 + pcm.size) ++ ByteVector("WAVEfmt ".getBytes) ++
        le32(16) ++ le16(1) ++ le16(spec.channels) ++ le32(spec.sampleRate.toLong) ++
        le32(spec.sampleRate.toLong * blockAlign) ++ le16(blockAlign) ++ le16(16) ++
        ByteVector("data".getBytes) ++ le32(pcm.size)
    AudioClip(header ++ pcm, AudioEncoding.Wav)
