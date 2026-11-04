# Settings › Models

Planned: round 1 picked b4, to be promoted. Until then the gear opens a temporary Settings page
(`SettingsScreen.kt`): the lectures folder, the audio format, the model downloads and the passes after the lecture.

b4: Settings (the lectures folder, the audio, the passes after the lecture, and a Models row saying whether every
language in use has a live model) opens Models, a catalog per language: chips for the languages in use and Speakers,
Add a language for any language Whisper knows; each model a card with its facts, badges (Live, Refine, NPU, Many
languages, From Hugging Face, the NPU check), its download and a menu. **Find on Hugging Face** searches for models
the app can run, filtered by language, kind (Whisper, Streaming; Parakeet, SenseVoice, Moonshine, Paraformer listed as
not in the app yet) and **NPU on this phone**; licenses are marked for commercial use, not, or unknown (asked first);
a size is installed pinned to the repo's commit and checked by sha256; an NPU model is tried on the NPU once
downloaded. No storage bar. Lab: `models b4`, `models b4-sm8750`.

## Design history

- [Round 1 (Nov 4, 2026)](../ux-history/models/2026-11-04-first-round.md): b4 — catalog + Hugging Face + NPU on this
  phone (picked); a — one page, a row per job, b — a catalog, c — set up for you, b2, b3.
