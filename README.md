# scala-openai-client

Standalone Scala 3 client library for any **OpenAI-compatible** HTTP endpoint —
real OpenAI, llama.cpp's `llama-server`, vLLM, or anything else that speaks the
same JSON shape.

```scala
libraryDependencies += "io.github.danbills" %% "scala-openai-client" % "0.1.0"
```

## What's inside

| Package | Purpose |
|---|---|
| `openai.RefinedTypes` | Iron type aliases: `NonEmptyString`, `BaseUrl`, `MaxTokens`, `Temperature` |
| `openai.models` | Circe codecs for chat completions, streaming chunks, TTS, audio transcription |
| `openai.enums` | `Role`, `FinishReason`, `StreamEnabled` (no booleans — 2-value enums) |
| `openai.free` | Requests as data: `ChatOp`, `AudioOp`, `ChatStreamOp` algebras for Free + `EitherK` coproducts (see below) |
| `openai.interpreters` | http4s interpreters for the algebras (`Http4sChat`, `Http4sAudio`, `OpenAIHttp4s`) |
| `openai.audio` | `AudioDeviceOp` (microphone/speaker algebra) and `ProcessAudioDevice` (arecord/aplay via fs2 processes) |

Demos (the unpublished `demo` module), both written as programs over the algebras: `openai.demo.VoiceNarrationDemo`
(Whisper → LLM → TTS → speaker) and `openai.demo.Gemma4AudioDirectDemo` (microphone → Gemma-4 audio input). They
read their servers from `LLM_URL`, `WHISPER_URL` and `TTS_URL`.

## Programs as data (Free + coproducts)

`openai.free` describes calls as values, following Bjarnason's "Composable application architecture
with reasonably priced monads" (Scala Days 2014). Each endpoint is its own algebra wrapping the
existing request models. Smart constructors are polymorphic in the coproduct `G`, so a program
asks only for the algebras it uses and runs in any coproduct that contains them, alongside your
own algebras (UI, console, tools).

| Algebra | Ops | Smart constructors |
|---|---|---|
| `ChatOp` | `Complete(req)` → `ChatCompletionResponse` | `Chat[G]`: `complete`, `ask` |
| `AudioOp` | `Transcribe(req)` → text; `Speak(req)` → `AudioClip` | `Audio[G]`: `transcribe`, `speak` |
| `ChatStreamOp` | `Events(req)` → one `ChatStreamEvent` (`Delta`, then `Done`) | `ChatStreaming[G]`: `events`, `deltas`, `onDeltas` |
| `SpeechStreamOp` | `Events(req)` → one `SpeechEvent` (`Bytes`, then `Done`) | `SpeechStreaming[G]`: `events`, `bytes`, `onBytes` |
| `StreamControlOp` | `Halt` (no value) | `StreamControl[G]`: `halt` |
| `openai.audio.AudioDeviceOp` | `Record(limit)` → `AudioClip`; `Play(clip)`; `Write(pcm)` | `AudioDevice[G]`: `record`, `play`, `write` |

**Audio.** `AudioClip(bytes, AudioEncoding)` is passed between the microphone, chat and TTS:
- *In:* `ContentPart.audio(clip)` sends audio to a multimodal chat model. `AudioTranscriptionRequest(clip)`
  or `AudioTranscriptionRequest(path)` transcribes it.
- *Out:* chat with `modalities = Some(List(Text, Audio))` and `audio = Some(AudioOutput(voice))` makes
  the model reply in speech. `ChatMessage.audio` carries the reply, and the stream's `DeltaContent.audio`
  carries it piece by piece. TTS models return a whole `AudioClip` (`Audio.speak`) or stream bytes
  (`SpeechStreaming`). Ask for `AudioEncoding.Pcm16` when bytes go straight to a speaker.
- *Device:* the microphone and speaker are a separate algebra, `AudioDeviceOp`, so the HTTP interpreters
  never touch a sound device.

```scala
// Voice to voice: the model hears the take and answers in speech, played as it streams in.
def voiceTurn[G[_]](using D: AudioDevice[G], S: ChatStreaming[G]): Free[G, ChatMessage] =
  for
    take <- D.record(RecordLimit.UntilStopped)
    req   = ChatCompletionRequest(model, List(ChatMessage(Role.User, List(ContentPart.audio(take)))),
              modalities = Some(List(Modality.Text, Modality.Audio)), audio = Some(AudioOutput("alloy")))
    done <- S.onDeltas(req)(d => d.audio.flatMap(_.data).fold(Free.pure[G, Unit](()))(D.write))
  yield done.message
```

**Streaming.** A streaming op yields *one* event, and its interpreter targets `fs2.Stream[F, *]`
as the monad, so the rest of the program runs once per event, as in the list monad. A
`ChatStreamEvent.Delta` arrives for each SSE chunk, then a single `Done` carries the reply
assembled by `ChatStreamAccumulator` (tool calls included). `Halt` ends a branch, so
`onDeltas(req)(printToken)` prints every token and then continues *once* with the whole reply.
Programs that use only `ChatOp`/`AudioOp` run in any `F`. A program that uses `ChatStreamOp`
needs a `Stream[F, *]` interpreter, and its coproduct type shows that. Single-shot interpreters
join the stream target via `StreamInterpreters.lift`. `StreamInterpreters.control` interprets `Halt`.

Default coproducts: `OpenAIOp` (chat + audio), `OpenAIDeviceOp` (+ device), `ChatStreamingOp` (streaming chat alone), `StreamingOp`, `OpenAIStreamingOp` (all API algebras), and
`VoiceOp` (all of them plus `AudioDeviceOp`).
### Interpreters

| Interpreter | Shape | Needs |
|---|---|---|
| `Http4sChat(client, endpoint)` | `ChatOp ~> F` | `Concurrent` |
| `Http4sChat.stream(client, endpoint)` | `ChatStreamOp ~> Stream[F, *]` (`.streaming` adds `Halt` for `ChatStreamingOp`) | `Concurrent` |
| `Http4sAudio(client, stt, tts, multiparts)` | `AudioOp ~> F` | `Concurrent`, `Files` (for `AudioInput.FromFile`) |
| `Http4sAudio.stream(client, tts)` | `SpeechStreamOp ~> Stream[F, *]` | `Concurrent` |
| `StreamInterpreters.control` / `.lift` | `Halt` / any `G ~> F` into `Stream[F, *]` | nothing |
| `ProcessAudioDevice(config, stop)` | `Resource[F, AudioDeviceOp ~> F]` | `Concurrent`, `Processes` |
| `OpenAIHttp4s.client` | Ember `Client[F]` | `Async`, `Network` (the only place) |

`OpenAIHttp4s` composes these for the default coproducts: `apply` (`OpenAIOp ~> F`), `streaming`
(`OpenAIStreamingOp ~> Stream[F, *]`) and `voice` (adds a device). `Endpoints` sends chat,
transcription and speech to separate servers when they live on different ports. Failures surface
as `OpenAIError` (`Http`, `BadStreamChunk`, `BadUrl`).

`ProcessAudioDevice` uses `arecord`/`aplay` by default, and `ffplay` for compressed formats. Takes come
back as WAV with a correct header. `Write` feeds one player that stays open, and on release that player
finishes what it has buffered. The commands are configurable via `ProcessAudioConfig`, which is
also how the tests run it without a sound card.

```scala
val endpoints = OpenAIHttp4s.Endpoints(
  chat          = Endpoint("http://localhost:8080/v1", "sk-local"),
  transcription = Endpoint("http://localhost:8766/v1", "sk-local"),
  speech        = Endpoint("http://localhost:8767/v1", "sk-local"))

val turn: IO[ChatMessage] =
  (OpenAIHttp4s.client[IO], ProcessAudioDevice[IO](stop = Some(IO.readLine.void)), Resource.eval(Multiparts.forSync[IO]))
    .tupled
    .use { (client, device, multiparts) =>
      voiceTurn[VoiceOp].foldMap(OpenAIHttp4s.voice(client, endpoints, multiparts, device)).compile.lastOrError
    }
```

## Build & publish

```bash
sbt --client compile          # build (or plain `sbt compile` for a fresh server)
sbt --client "Test/testFull"  # every suite; plain `test` is incremental in sbt 2 and skips unaffected ones
sbt --client 'set ThisBuild / version := "0.2.0-SNAPSHOT"; publishLocal; publishM2'   # Ivy + Maven local
```

Most suites are offline (pure interpreters, an in-memory http4s server, stand-in audio processes). The
`Live*` suites and `ChatCompletionTest` run only against servers you name, and cancel otherwise:

```bash
LLM_URL=http://localhost:8080/v1 MODEL=qwen3 WHISPER_URL=http://localhost:8766/v1 \
TTS_URL=http://localhost:8767/v1 sbt "Test/testFull"
```

[AGENTS.md](AGENTS.md) covers the sbt 2 caching behavior.

Version pins: Scala 3.8.3, sbt 2.0.6,
Circe 0.14.15, Iron 3.3.1, cats-effect 3.7.0, cats-free 2.13.0, fs2 3.11.0, http4s 0.23.32.

## Using a local build

To try unreleased changes from another project, publish a snapshot (`publishLocal; publishM2` as above;
`publishLocal` alone only writes Ivy-local) and depend on it:

```scala
resolvers += Resolver.mavenLocal
libraryDependencies += "io.github.danbills" %% "scala-openai-client" % "0.2.0-SNAPSHOT"
```

## Releasing

Published to Maven Central as `io.github.danbills %% scala-openai-client`,
same recipe as [iron-mcp](https://github.com/danbills/iron-mcp). Releases are
cut locally. sbt 2 uploads to the Sonatype Central Portal itself, so there is
no CI plugin and no workflow — `sbt-dynver` takes the version from the git tag
(`build.sbt` deliberately sets no `version`) and `sbt-pgp` signs.

```bash
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0

export SONATYPE_USERNAME=...   # Central Portal user token, not your login
export SONATYPE_PASSWORD=...
export PGP_PASSPHRASE=...

sbt publishSigned   # sign, into a local bundle
sbt sonaUpload      # upload; release by hand in the portal
# or sonaRelease    # upload and release in one step
```

The version is only clean (`0.1.0`) when the tree is clean at the tag —
sbt-dynver appends a commit and timestamp otherwise, which is a useful guard
against releasing uncommitted work.