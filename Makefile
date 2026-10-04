.PHONY: help compile test publish voice-narration audio-demo

SBT ?= sbt --client

## help          — show targets
help:
	@grep -E '^## ' Makefile | sed 's/## //'

## compile       — compile the library
compile:
	$(SBT) compile

## test          — full test run (live-server tests cancel if unreachable)
test:
	$(SBT) "Test/testFull"

## publish       — publish to Ivy-local and Maven-local for local consumers
publish:
	$(SBT) "publishLocal; publishM2"

## voice-narration — WAV → Whisper → LLM → TTS (LLM_URL, WHISPER_URL, TTS_URL)
voice-narration:
	$(SBT) "demo/runMain openai.demo.VoiceNarrationDemo"

## audio-demo    — push-to-talk audio straight into the model (LLM_URL)
audio-demo:
	$(SBT) "demo/runMain openai.demo.Gemma4AudioDirectDemo"