# Repository Guidelines

## What This Is

A Scala 3 client for OpenAI-compatible servers (OpenAI, llama.cpp, vLLM, NInfer, Whisper, NeMo TTS) built as
**requests as data**: one Free algebra per endpoint, combined with `EitherK` coproducts, interpreted by
effect-polymorphic interpreters. There is no client class; `OpenAIClient` was removed in 0.2.0.

## Project Structure

- `src/main/scala/openai/models/` — wire types and Circe codecs (`ChatCompletionRequest`, `ChatMessage`, `StreamChunk`, `Audio.scala`, `ChatStreamAccumulator`)
- `src/main/scala/openai/free/` — algebras (`ChatOp`, `AudioOp`, `ChatStreamOp`, `SpeechStreamOp`, `StreamControlOp`), smart-constructor capabilities (`Chat[G]`, `Audio[G]`, `ChatStreaming[G]`, `SpeechStreaming[G]`, `StreamControl[G]`), `Coproducts.scala`, `StreamInterpreters`
- `src/main/scala/openai/interpreters/` — http4s interpreters (`Http4sChat`, `Http4sAudio`, `OpenAIHttp4s`, `Endpoint`, `OpenAIError`)
- `src/main/scala/openai/audio/` — microphone/speaker algebra (`AudioDeviceOp`, `AudioDevice[G]`) and its process interpreter (`ProcessAudioDevice`, `Wav`)
- `src/main/scala/openai/*Demo.scala`, `DemoConsole.scala` — runnable demos, written as programs
- `src/test/scala/openai/` — `free/` (pure), `interpreters/` (fake http4s server + live), `audio/` (stand-in processes)

## Design Rules

- **No `IO` in library code.** Ask for the weakest typeclass that works: `Concurrent` for http4s interpreters, `Files` only for file transcription, `Processes` only for the device interpreter, `Async` + `Network` only in `OpenAIHttp4s.client`. `IO` appears in tests and demos.
- **An op is plain data.** Never put an effect type in an algebra; an op that would return `Stream[IO, …]` is a design error.
- **Streaming ops yield one element** and are interpreted into `fs2.Stream[F, *]` as the monad; the rest of the program runs once per element. `Halt` (via `StreamControl[G]`) ends a branch, which is how `onDeltas` / `onBytes` rejoin. A program using a streaming algebra needs a `Stream` interpreter, and its coproduct type says so.
- **Smart constructors are polymorphic in the coproduct** (`final class Chat[G[_]](using InjectK[ChatOp, G])` + a `given` in the companion), so programs ask only for the algebras they use.
- **Coproducts are named, right-nested `EitherK` aliases** (`OpenAIOp`, `OpenAIDeviceOp`, `ChatStreamingOp`, `StreamingOp`, `OpenAIStreamingOp`, `VoiceOp`); `InjectK` derivation walks the right spine. Compose interpreters with `.or` in the same order.
- **Failures are typed**: non-2xx is `OpenAIError.Http(status, body)`, a bad SSE line `OpenAIError.BadStreamChunk`, a bad URL `OpenAIError.BadUrl`.
- **Audio bytes stay inside interpreters.** Ops carry `AudioClip` / `ByteVector` values or name a sink; the HTTP interpreters never touch a sound device.
- Iron refinement types for anything constrained (`BaseUrl`, `NonEmptyString`, `SampleRate`, `SpeechSpeed`). Literals refine via `autoRefine`; **interpolated strings cannot be refined at compile time** — use a literal or `refineOption` at runtime.

## Build, Test, Publish

```bash
sbt --client compile
sbt --client "Test/testFull"                     # ALL suites — see "test is incremental" below
sbt --client "testOnly openai.free.*"            # one area
sbt --client 'set ThisBuild / version := "0.2.0-SNAPSHOT"; publishLocal; publishM2'
```

`-Werror` is on, so every warning fails the build. Scala 3.8.3, sbt 2.0.6, cats-effect 3.7, fs2 3.11, http4s 0.23.32,
cats-free 2.13. Releases follow the README recipe (tag → `publishSigned` → `sonaUpload`); the version comes from the
git tag via sbt-dynver.

### sbt 2 behavior that bites

- **`test` is incremental** (the old `testQuick`): it runs only the suites affected by changed code. A run that
  reports "18 passed" when there are 26 tests is not a regression or a pass — it skipped suites. Use
  `Test/testFull` (the Makefile's `test` target does) whenever the answer matters.
- **Compile results are cached on disk, shared across directories and git worktrees, and `clean` does not evict
  them.** A cache hit prints `[success]` in about 0 s and **replays no warnings**. Under `-Werror` a cached
  success still means warning-free, but in builds without `-Werror` "no
  warnings after a fast compile" proves nothing. To get a genuine compile, change an input (append a comment to
  a source, then revert it) and run `clean; compile`.
- **`sbt --client` joins its arguments into one command line.** `sbt --client 'set X := 1' publishLocal` is a
  parse error; chain with `;` inside one quoted argument: `sbt --client 'set X := 1; publishLocal'`.
- **`set` persists in the server session** until `reload`. After publishing with a `set ThisBuild / version`,
  later commands in the same server still see that version.
- **Publish to both Ivy-local and Maven-local** (`publishLocal; publishM2`): downstream sbt builds that resolve
  through `Resolver.mavenLocal` only see the Maven-local copy.
- **`export Runtime/fullClasspath` prints virtual paths** (`List(${OUT}/…jar>sha256-…/size, ${CSR_CACHE}/…)`).
  Map `${OUT}` → `target/out`, `${CSR_CACHE}` → `~/.cache/coursier/v1`, strip `>sha256-…`, join with `:` to get
  a `java -cp` classpath — the way to run a stdio program by hand, since `sbt run` does not forward stdin.
- **A JDK updated in place** leaves the running server unable to spawn processes ("Failed to exec spawn
  helper"); `sbt --client shutdown` and retry.

## Testing

- ScalaTest `AnyFunSuite`. Suites are hermetic unless named `Live*`.
- **Pure programs**: interpret into `State` / `Either` with canned `Op ~> F` interpreters (`FreeAlgebraTest`).
  `assertCompiles` / `assertTypeError` pin which coproducts a program may target.
- **http4s interpreters**: drive them against `Client.fromHttpApp` (`Http4sInterpreterTest`) — real requests,
  real decoding, no network.
- **Device interpreter**: swap the commands in `ProcessAudioConfig` for `head` / `cat` / `sh` stand-ins
  (`ProcessAudioDeviceTest`); never record from the mic or play sound in a test.
- **Live tests** (`LiveWhisperTest`, `LiveTtsTest`, `ChatCompletionTest`) are opt-in: they read `LLM_URL`,
  `WHISPER_URL`, `TTS_URL` (and optionally `MODEL`, `OPENAI_API_KEY`) through `LiveEnv`, and cancel when the
  variable is unset or the host does not answer.
  - A Whisper server that loads its model on demand can fail the first request on a cold start; a failure that
    passes on rerun is the server, not the client.
  - Some TTS servers (NeMo FastPitch) ignore `model` / `voice` / `response_format` and always return WAV; the
    interpreter labels clips from `Content-Type` for that reason.
  - `jfk.wav` is not in the repo; `LiveWhisperTest` cancels without it. Put a copy in the working directory
    for a run and delete it afterwards.

## Consumers

Downstream builds consume snapshots from Maven local. After an API change: publish (`publishLocal; publishM2`),
bump the consumer's pinned version, and compile it.

## Commits

Imperative subjects describing the change ("Replace OpenAIClient with Free algebras over EitherK coproducts");
explain the why in the body. Do not commit `project/metals.sbt` or `jfk.wav`.
