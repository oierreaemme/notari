# Notari — Session recap 2026-05-26 → 2026-05-28

Hand-off for the next chat. Branch in flight: **`feature/asr-whisper`**.

## TL;DR — where we are

The ASR engine has been migrated from `SpeechRecognizer` to **whisper.cpp batch**. The complete in-car plumbing (continuous capture, Bluetooth mic routing, microphone foreground service) is built and validated on-device. On a Pixel 6a with **`ggml-small-q5_1.bin`** (~180 MB), Italian + English code-switching transcribes well (phone-mic ~95% usable, Bluetooth ~80% usable — the Bluetooth ceiling is the narrowband HFP/SCO audio, not whisper).

ADR 0018 is amended to record the engine pivot (Vosk → whisper); ADR 0019 (Proposed) decides at-rest encryption decoupled from the biometric lock (not yet implemented).

## What changed this session (chronological)

1. **External review (Gemini) of v1.0.1.** Most of it noise; one real point: at-rest encryption.
2. **ADR 0019 (Proposed).** SQLCipher with a device-bound Keystore key, **always-on**, **decoupled** from the biometric (so a fingerprint change can't destroy data; no Hilt lazy-DB ripple). Not implemented yet.
3. **ADR 0018 originally chose Vosk** (continuous streaming) as the replacement for `SpeechRecognizer`. Built the spike (`VoskSpeechToTextSession` + `FallbackSpeechToTextSession` + `FileVoskModelProvider`).
4. **In-car plumbing** (engine-agnostic, reused throughout):
   - `BluetoothAudioRouter` — `setCommunicationDevice` on API 31+, `startBluetoothSco` fallback. Confirmed routing to Mario's "A60Pro" earbuds on the Pixel 6a.
   - `RecordingForegroundService` (type `microphone`) — keeps capture alive with the screen off. Persistent notification: "Notari is recording — Audio stays on this device."
   - Layout fix in `RecordingPane`: long transcripts now sit in a `weight(1f).verticalScroll(...)` with auto-scroll-to-bottom; controls stay pinned.
   - Waveform RMS rescaled to the UI's `[-2, 12]` window (raw whisper-style dB was saturating the normalisation).
5. **Privacy guard evolved.** `AudioRecord` is now allowed only in `VoskSpeechToTextSession.kt` and `BatchSpeechToTextSession.kt`; a test asserts every mic-owning file zeroes its PCM. Sink bans (`MediaRecorder`, `FileOutputStream`, …) unchanged.
6. **Vosk validation showed the dealbreaker** (this ADR's "Alternatives considered" anticipated it): small Italian Vosk garbles English/code-switching and degrades on imperfect speech. Fatal for tech notes.
7. **Whisper migration phase 1** — `BatchSpeechToTextSession` + `BatchTranscriber` + `FakeBatchTranscriber`. Validated the record→transcribe-at-stop→structure flow with a placeholder.
8. **Whisper migration phase 2** — vendored `whisper.cpp` (v1.8.5) as a git submodule at `core/asr/src/main/cpp/whisper.cpp`. CMakeLists + JNI bridge + Kotlin `WhisperContext` + `WhisperBatchTranscriber`. Build lesson: AGP builds the native debug variant unoptimised — without `-O3` whisper is **~20× too slow**; CMakeLists forces it.
9. **Phase.Transcribing UI added.** The capture screen now shows a dedicated "Trascrizione…" step between Recording and Structuring instead of hiding inside a long "Structuring…". The foreground service stays alive through `Recording` AND `Transcribing` so a screen-off stop can't let the OS kill the process mid-transcription.
10. **Model auto-selection.** `WhisperBatchTranscriber` picks the best model present, in priority order:
    1. `ggml-small-q5_1.bin` — recommended default (~180 MB, quality ≈ `small` at ~2.5× smaller)
    2. `ggml-small.bin` (~466 MB)
    3. `ggml-base-q5_1.bin` (~60 MB)
    4. `ggml-base.bin` (~142 MB)
    5. `ggml-tiny.bin` (~75 MB)
    No rebuild needed — push whichever you want.
11. **Diagnostic logs in `BatchSpeechToTextSession`** (currently uncommitted on the tree) measure the AudioRecord cold-start. Real numbers from a Pixel 6a phone-mic session: first PCM frame at ~+130 ms (silence), first non-silent frame at **+845–1082 ms**. So the audio path takes ~700–1000 ms to stabilise after `startRecording()`.

## Working tree & branches

- `main` — v1.0.1, untouched in this session.
- `feature/asr-vosk` — the Vosk spike, pushed (without the in-car plumbing that landed on `feature/asr-whisper`).
- `feature/asr-whisper` — **current branch**. Carries: Vosk spike + plumbing + ADR 0019 + phase 1 batch + phase 2 whisper native + Transcribing UI + model auto-selection. Last committed message: `feat(asr): Transcribing UI phase + auto-select best whisper model`.
- **Uncommitted at end of session:** diagnostic timing logs in `BatchSpeechToTextSession.kt` (`BatchSession` tag) — see the open issue below.

## Key files added / modified

`:core:asr` (this is where most of the work lives)
- `src/main/cpp/CMakeLists.txt` (new) — builds whisper.cpp; forces `-O3` on debug.
- `src/main/cpp/whisper_jni.cpp` (new) — JNI bridge: init, transcribe, free.
- `src/main/cpp/whisper.cpp/` (submodule) — vendored whisper.cpp v1.8.5.
- `src/main/java/com/voicenotemd/core/asr/WhisperContext.kt` (new)
- `src/main/java/com/voicenotemd/core/asr/WhisperBatchTranscriber.kt` (new)
- `src/main/java/com/voicenotemd/core/asr/BatchSpeechToTextSession.kt` (new — captures PCM, batch-transcribes at stop)
- `src/main/java/com/voicenotemd/core/asr/BatchTranscriber.kt` (new — interface + `FakeBatchTranscriber`)
- `src/main/java/com/voicenotemd/core/asr/BluetoothAudioRouter.kt` (new)
- `src/main/java/com/voicenotemd/core/asr/VoskSpeechToTextSession.kt` (kept for reference, unwired)
- `src/main/java/com/voicenotemd/core/asr/FallbackSpeechToTextSession.kt` (kept, unwired)
- `src/main/java/com/voicenotemd/core/asr/VoskModelProvider.kt` (kept, unwired)
- `src/main/java/com/voicenotemd/core/asr/VoskResultParser.kt` (kept, unwired)
- `src/main/java/com/voicenotemd/core/asr/di/AsrModule.kt` (rewired to whisper batch path)
- `src/main/AndroidManifest.xml` (+ `MODIFY_AUDIO_SETTINGS`)
- `build.gradle.kts` (+ `externalNativeBuild` + `abiFilters += "arm64-v8a"`)
- `src/test/java/com/voicenotemd/core/asr/NoAudioPersistenceTest.kt` (evolved — see "Privacy guards" below)

`:feature:capture`
- `src/main/java/com/voicenotemd/feature/capture/RecordingForegroundService.kt` (new)
- `src/main/java/com/voicenotemd/feature/capture/CaptureUiContract.kt` (+ `Phase.Transcribing`)
- `src/main/java/com/voicenotemd/feature/capture/CaptureViewModel.kt` (`Recording → Transcribing → Structuring`)
- `src/main/java/com/voicenotemd/feature/capture/CaptureRoute.kt` (+ `TranscribingPane`, FGS lifecycle through both phases, long-note layout fix, RMS waveform fix)
- `src/main/AndroidManifest.xml` (+ FGS declaration + `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`)

Docs
- `docs/decisions/0018-continuous-streaming-asr-vosk.md` — amended 2026-05-27 with the whisper pivot + on-device validation result.
- `docs/decisions/0019-encryption-at-rest-decoupled-from-biometric.md` (new, Proposed).
- `docs/decisions/0013-biometric-launch-lock.md` — forward-link added to 0019.
- `docs/decisions/0003-asr-strategy.md` — forward-link added to 0018.
- `docs/decisions/README.md` — index updated.
- `CHANGELOG.md` — `[Unreleased]` section now describes the whole arc.

## Privacy guards (what they enforce)

- **Detekt `ForbiddenImport`** (`config/detekt/detekt.yml`) — global: bans `MediaRecorder`, `MediaMuxer`, `MediaCodec`, `okhttp3`, `retrofit2`, `firebase`, `HttpURLConnection`, `URLConnection`. No INTERNET in the merged manifest (CI checks).
- **`NoAudioPersistenceTest`** (`:core:asr`) — bans `MediaRecorder`, `MediaMuxer`, `MediaCodec`, `FileOutputStream`, `RandomAccessFile` in the module. `AudioRecord` is allowed **only** in `VoskSpeechToTextSession.kt` and `BatchSpeechToTextSession.kt`, and a second test asserts every file referencing `AudioRecord` also contains a `.fill(0)` zeroing call.
- **Manifest** rips out transitive `INTERNET` / network permissions with `tools:node="remove"`.

## Open follow-ups (task list state)

- **Task 10 (pending) — Real in-car test: Bluetooth + whisper together.** Mario's on-device. So far tested separately (BT routing confirmed; phone-mic whisper confirmed); the full scenario (phone in pocket, screen off, BT earbuds, whisper batch) is the next validation.
- **Task 12 (pending) — Whisper productionization for merge.** Before a PR onto main:
  - **Cold-start UX** — surface a brief "Preparazione…" indicator during the ~700–1000 ms AudioRecord warm-up, transition to "In ascolto…" only after the first non-silent RMS frame. (Diagnosed today; intermittent bug "first part of dictation missing" comes from speaking inside the warm-up window.)
  - Model delivery (bundle vs download) — shared with ADR 0008.
  - Remove or gate spike diagnostic logs (`WhisperBatch`, `AsrBtRouter`, `BatchSession`, `AsrFallback`, `VoskModel`).
  - Restore release ABIs (`armeabi-v7a`, `x86_64`) alongside `arm64-v8a`.
  - Thread-count tuning (current: `availableProcessors().coerceIn(2, 4)`).
  - Default model choice for shipping (recommendation: `ggml-small-q5_1.bin`).
  - Add a unit test for `BatchSpeechToTextSession` using `FakeBatchTranscriber`.
  - `./gradlew ktlintFormat` before the PR.
- **ADR 0019 (Proposed) — Encryption at rest** — not implemented yet; the decision is recorded.

## Performance & quality data points

- **Whisper cold-start** (first inference per process): `nativeInitFromFile` for `ggml-small-q5_1.bin` is fast (sub-second on Pixel 6a). The transcriber loads and frees the model per call to free RAM for Gemma; this is acceptable.
- **Transcription speed** with `small-q5_1`, 4 threads, Pixel 6a CPU: roughly real-time-ish (a ~25 s note transcribes in tens of seconds, well below the user's tolerance now that there's a dedicated "Trascrizione…" indicator).
- **`-O3` matters.** Without it, whisper.cpp ran ~20× slower (2 minutes for 6.7 s of audio in the first debug build).
- **AudioRecord cold-start.** First PCM read at ~+130 ms (silence); first non-silent PCM at +845–1082 ms. The audio path takes ~700–1000 ms to stabilise.
- **Quality verdict on `small-q5_1`.** Italian + English brand/jargon ("Ableton", "ADR", "HFP/SCO") transcribed correctly enough that notes are usable with light editing. Bluetooth narrowband still degrades quality regardless of engine.

## Quick-reference commands (PowerShell)

```
./gradlew :app:installDebug
./gradlew ktlintFormat
./gradlew test detekt ktlintCheck --console=plain 2>&1 | Select-String -Pattern "FAILED|failed|error:|BUILD SUCCESSFUL|BUILD FAILED|Tests run" | Select-Object -First 80
```

Push a whisper model to internal storage (replace `<model>`):
```
adb push <model>.bin /data/local/tmp/<model>.bin
adb shell chmod 755 /data/local/tmp/<model>.bin
adb shell run-as com.voicenotemd.debug mkdir -p files/whisper
adb shell run-as com.voicenotemd.debug cp /data/local/tmp/<model>.bin files/whisper/<model>.bin
adb shell run-as com.voicenotemd.debug ls -la files/whisper
adb shell rm /data/local/tmp/<model>.bin
```

Live log filter for the ASR path:
```
adb logcat -c
adb logcat | Select-String -Pattern "BatchSession|WhisperBatch|AsrBtRouter|AndroidRuntime"
```

## Storage footprint

- Gemma E2B INT4 (`.litertlm`): ~1.5 GB
- whisper `small-q5_1`: ~180 MB
- App APK + LiteRT-LM runtime: tens of MB
- **Total ≈ 1.7 GB.** Honest framing: this is the price of doing the AI entirely on-device. Users who pick Notari are picking exactly this trade. Going further down (`base-q5_1` ~60 MB) trades quality for ~120 MB.

## Memory pointers (auto-loaded in future sessions)

- `project_asr_vosk_migration.md` — already updated with the whisper outcome. Description: "ASR migration — landed on whisper.cpp BATCH (not Vosk) after on-device tests; ADR 0018 amended 2026-05-27."
- `feedback_gradle_shell.md` — Mario is on PowerShell on Windows; use `./gradlew` (not `gradlew.bat`); don't pipe to Unix tools (`tail`/`grep`), use `Select-Object -Last` / `Select-String`.
- `feedback_file_tools_vs_bash_mount.md` — trust Read/Write/Edit over the bash mount.
- `project_voice_note_markdown_engine_lifecycle.md` — load-vs-inference contention is the recurring Gemma bug source.
- `project_prompt_asset_build_cache.md` — prompt changes need a clean build (`./gradlew clean :app:installDebug`); irrelevant after the move to whisper but the lesson generalises.
- `project_mario_motivation_context.md` — Notari is Mario's real tool; Obsidian vault; Pixel 6a; wants Markdown export to Obsidian.

## Suggested first move in the next chat

If we resume soon: **the cold-start "Preparazione…" UX**. It's small, it would close the intermittent "missing first words" bug, and it leaves the codebase in a noticeably better state for the eventual PR. After that: the real in-car test, then the productionization pass for a merge onto main.

If the in-car test happens first: bring the logcat (`BatchSession`, `WhisperBatch`, `AsrBtRouter`) and the transcribed note for an honest read on the BT-narrowband ceiling.
