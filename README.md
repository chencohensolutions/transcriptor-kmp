# Transcriptor

A notebook for your classes, for Android and iOS. It records the lecture and transcribes it **on the phone** with a
local speech recognition model, Hebrew first. Write notes while it keeps transcribing; every line of speech and every
note is stamped with its time in the recording. The audio is kept (FLAC, 48 kHz stereo, 16 bit), so after class a
larger model can refine the whole transcript, tell the speakers apart, and any line can be played back and corrected.

**Status:** early. The Android screens are being designed in the UI lab; the iOS app (SwiftUI) follows once Android
is finished.

## Models

Everything runs on the phone; the network is only used to download models. Models are chosen per language in
Settings › Models: one for live transcription, one for refining after class.

| Model | Languages | Size | License | Use |
|---|---|---|---|---|
| [ivrit.ai Whisper large-v3 turbo](https://huggingface.co/ivrit-ai/whisper-large-v3-turbo) | Hebrew | 809M params | Apache-2.0 | Live |
| [ivrit.ai Whisper large-v3](https://huggingface.co/ivrit-ai/whisper-large-v3) | Hebrew | 1.55B params | Apache-2.0 | Refine |
| [Whisper large-v3 turbo](https://huggingface.co/openai/whisper-large-v3-turbo) | ~100 languages | 809M params | MIT | Live |
| [Whisper large-v3](https://huggingface.co/openai/whisper-large-v3) | ~100 languages | 1.55B params | MIT | Refine |

## Building

Kotlin Multiplatform: shared logic in `sharedLogic/`, Jetpack Compose on Android, SwiftUI on iOS. See
[CLAUDE.md](CLAUDE.md) for the commands and [docs/design.md](docs/design.md) for the design.
