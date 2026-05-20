# Notari

*Voice notes that never leave your phone, structured by Gemma 4.*

[![Build](https://img.shields.io/badge/build-pending-lightgrey)](.github/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Min SDK](https://img.shields.io/badge/minSdk-28-green)](#)
[![Privacy](https://img.shields.io/badge/network-zero-brightgreen)](#privacy)

> Speak. Get a clean Markdown note. The audio never leaves your phone.

## Why this exists

Voice notes are messy. Existing apps either give you a raw transcript that
nobody re-reads, or they ship your audio to a server you don't control —
neither solves the actual problem.

Notari captures speech, transcribes it locally, and uses a small on-device
LLM (Google Gemma 4 E2B) to turn it into a structured Markdown note: a
title, tags, parsed dates, and a clean body. The audio buffer is held
only in RAM and overwritten the moment transcription completes — it
never touches disk.

The name takes its cue from the Latin *notarius* — historically the
trusted recorder of spoken statements. That is the contract: you speak,
the app structures, the recording is destroyed, nothing leaves the device.

This is built for the
[Google Gemma 4 Challenge](https://dev.to/) (May 2026), "Build With Gemma 4"
track.

## Demo

Coming soon — demo GIF and 90-second walkthrough.

## How it works

```
 ┌──────────┐   ┌────────────────────┐   ┌────────────────────────┐
 │  Mic     │──▶│ SpeechRecognizer   │──▶│ Gemma 4 E2B            │
 │ capture  │   │ (OS-managed buffer)│   │ via LiteRT-LM runtime  │
 └──────────┘   └─────────┬──────────┘   │ (GPU → CPU fallback,   │
                          │              │  MTP speculative dec.) │
                  Flow<String>           └────────────┬───────────┘
                  transcript                          │
                                                      ▼
                                          ┌────────────────────┐
                                          │ JSON with title,   │
                                          │ tags, mentions,    │
                                          │ body_markdown,     │
                                          │ ISO-resolved dates │
                                          └─────────┬──────────┘
                                                    ▼
                                          ┌────────────────────┐
                                          │ Room (notes +      │
                                          │ tags + mentions)   │
                                          └────────────────────┘
```

The structuring prompt embeds the current wall-clock time so relative
references like *"domani alle 15"* or *"tomorrow at 3pm"* come back as
real ISO-8601 timestamps anchored to the device's timezone — temporal
reasoning done locally, no clock service called over the network. See
[ADR 0010](docs/decisions/0010-prompt-temporal-context.md).

Inference probes three paths in order, falling back gracefully:
`Backend.GPU` (fastest, fails on some OEM driver combos) →
`Backend.CPU` (universal) → with `ExperimentalFlags.enableSpeculativeDecoding`
flipped on for MTP drafter heads when the model carries them. See
[ADR 0011](docs/decisions/0011-backend-probing-and-mtp.md).

Full architecture: [docs/architecture.md](docs/architecture.md).

## Features

- Tap to record, dictate long-form with natural pauses, tap to stop.
  No menus.
- Automatic language detection (English, Italian, Spanish, French,
  German, Portuguese), or force a language in Settings.
- Gemma 4 E2B running fully on-device transforms the transcript into:
  title, tags, datetime mentions (with **resolved ISO timestamps**),
  and a clean Markdown body.
- Datetime mentions are rendered as chips in the review pane and note
  detail — *"venerdì prossimo"* → *"ven 22 mag 2026"* — so the model's
  temporal reasoning is visible, not just stored.
- All notes searchable and filterable by tag.
- Multi-select notes for bulk delete or bulk export.
- Export as Obsidian-compatible Markdown with full YAML frontmatter
  (title, created/updated, language, tags, mentions, structured flag) —
  drop the file into any Markdown tool and the metadata round-trips.
- Material 3 Expressive UI, dynamic color, predictive back, edge-to-edge.
- 100% offline. Works in airplane mode.

## Performance and limits

This is a 2B-effective-parameter LLM running INT4-quantized on a phone.
We trade latency for privacy, and we tell you about it honestly:

- **First inference after process start** loads ~1.5 GB of weights off
  disk before generation begins. Pre-warming kicks off in the background
  the moment you land on the capture screen, so by the time you finish
  dictating it's usually ready.
- **Structuring latency on Pixel 6a (Tensor G1, CPU backend)** —
  empirically: 15-20s for a 200-character note, 30-40s for a 500-char
  note, 50-60s for a 1000-char note. On devices with a working
  `Backend.GPU` path the same notes complete in 5-15s (Pixel 6a's
  Mali-G78 currently fails to compile the kernels with LiteRT-LM 0.11,
  see ADR 0011 — your mileage will vary).
- The capture screen shows an elapsed-time counter and an estimate
  while Gemma works, so you always know whether the spinner means
  *working* or *stuck*.

## Privacy

The privacy promise is the product, not a footnote.

- **No `INTERNET` permission.** A CI check fails the build if anyone tries to
  add it.
- **No audio file ever touches disk.** The recording buffer lives in RAM,
  is consumed by the transcriber, and is overwritten immediately.
- **No analytics, no crash reporters that phone home, no remote model
  downloads at runtime** (model is bundled or fetched once on first launch
  with explicit consent via the system download manager).
- **No Android auto-backup of notes** (`android:allowBackup="false"` on a
  per-data-class basis, see `data_extraction_rules.xml`).

### Verify it yourself

You don't have to take our word for it. With the app installed:

```bash
# 1. The app's manifest declares zero network permissions:
adb shell dumpsys package com.voicenotemd | grep "permission.INTERNET"
# (no output expected)

# 2. While recording, no audio file appears under the app's data dir:
adb shell run-as com.voicenotemd find /data/data/com.voicenotemd -type f

# 3. Network traffic during a full record-transcribe-save cycle:
adb shell pm grant com.voicenotemd android.permission.DUMP  # if needed
# Then use a tool like NetGuard or PCAPdroid to confirm zero traffic.
```

## Building

### Prerequisites

- Android Studio Ladybug | 2024.2.1 or newer
- JDK 17
- Android SDK 35 (compileSdk), platform tools

### One-time setup

```bash
git clone https://github.com/REPLACE_ME/voice-note-markdown.git
cd voice-note-markdown

# Generate the Gradle wrapper if not present (first clone only):
gradle wrapper --gradle-version 8.10.2
```

### Getting the Gemma 4 E2B model

The model is **not committed** (~1.5 GB) and the app **does not download
it for you** — that would require an INTERNET permission, which we
deliberately don't request. See
[ADR 0008](docs/decisions/0008-litertlm-and-saf-import.md).

Instead, download `gemma-4-E2B-it.litertlm` from the
[litert-community Hugging Face page](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
(you'll accept the Gemma terms of use there). Make sure the file is
dated **2026-05-05 or later** — earlier copies don't have the MTP
speculative-decoding heads embedded. Then provide it to the app via
one of two paths.

**For end users — Settings → On-device model → Import**

1. Open the app, tap the gear icon → "On-device model".
2. Tap **Import .litertlm** and pick the file you downloaded from any
   source the system file picker can reach (Downloads, Drive, external
   SD card, …).
3. The app streams the bytes into private storage at
   `filesDir/models/gemma-4-e2b-it.litertlm` and flips the status to
   "Ready". From then on, structured-note inference works fully offline.

**For developers — faster `adb push`**

```bash
# debug variant has applicationId com.voicenotemd.debug
adb push gemma-4-E2B-it.litertlm \
  /sdcard/Android/data/com.voicenotemd.debug/files/models/gemma-4-e2b-it.litertlm
```

The `ModelFileProvider` in `:app/AppModule.kt` resolves both locations
(internal first, then external) — no app rebuild needed after pushing.

### Build

```bash
./gradlew :app:assembleDebug
```

### Test and static analysis

```bash
./gradlew test                # unit tests across all modules
./gradlew detekt              # Detekt static analysis (all modules)
./gradlew ktlintCheck         # ktlint style check (all modules)
./gradlew :app:lintDebug      # Android lint
./gradlew :app:processDebugManifest   # then run the privacy gate:
bash ./scripts/check-no-internet-permission.sh
```

Detekt and ktlint run across every Kotlin source set (applied to all
subprojects from the root build) and are green; the same checks run in CI
on every push. ktlint configuration lives in `.editorconfig`, Detekt
configuration in `config/detekt/detekt.yml`. The privacy gate script is a
Bash script — invoke it with `bash` on Windows (PowerShell won't execute a
`.sh` directly).

## Architecture

See [docs/architecture.md](docs/architecture.md) for the full overview and
the [`docs/decisions/`](docs/decisions/) folder for ADRs.

Module layout:

```
:app                — entry point, navigation graph, Hilt setup
:core:design        — Material 3 theme, shared composables (Mentions
                      chip, Markwon renderer), motion specs
:core:common        — Result types, dispatchers, domain primitives,
                      Markdown+YAML exporter shared by share + ZIP
:core:database      — Room database, DAOs, entities, cascading FKs
:core:datastore     — DataStore preferences (settings)
:core:inference     — LiteRT-LM runtime + Gemma 4 E2B engine lifecycle
                      (GPU/CPU probing, MTP, onTrimMemory release)
:core:asr           — SpeechRecognizer continuous-listen wrapper
:feature:capture    — recording + structuring + review screen
:feature:notes      — list, search, filter, multi-select bulk actions
:feature:noteDetail — single-note view, edit, share as Markdown+YAML
:feature:settings   — preferences, privacy info, model import
:feature:onboarding — 3-screen first-launch (state-driven navigation)
```

## License

[Apache 2.0](LICENSE).

The bundled Gemma 4 E2B model is governed by the
[Gemma Terms of Use](https://ai.google.dev/gemma/terms). By building the
app you agree to those terms.
