# Changelog

All notable changes to Notari (formerly *Voice Note Markdown*) are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed — Pass 1 budget too small on CPU fallback path (2026-05-17 evening)
- Real-device test on a Pixel where `Backend.GPU()` init fails with a
  LiteRT-LM internal error: the session correctly falls back to
  `Backend.CPU()`, but the morning fix's GPU-tuned budget was too
  small for CPU. The 278-char "Quick reminder" note timed out at
  exactly the budget value (56700ms = 15000 + 278*150), and a
  follow-up 470-char sogno note timed out at 38450ms; logcat showed
  the model emitting a valid JSON response in ~78s in both cases.
- Fix: `GemmaSession.backend()` now reports the live backend (`GPU` /
  `CPU` / `UNKNOWN`); `LiteRtLmGemmaSession` tracks which path
  `engineFactory()` resolved to. The structuring use case reads the
  backend after `warmUp()` and picks budgets on BOTH baseline AND
  per-char:
  - GPU: 15 s baseline + 30 ms/char (Pass 1)
  - CPU/UNKNOWN: 60 s baseline + 150 ms/char (Pass 1)
  Empirical fit `T(L) ≈ 46s + 0.068s × L` on Pixel CPU; the new
  baseline reflects the dominant static-prompt prefill cost that the
  per-char-only model was systematically under-budgeting on short
  notes.
- `MAX_PASS_BUDGET_MS` raised 150s → 250s to fit CPU-path long-note
  inference. Trade-off: CPU users now wait up to ~250s before a
  genuinely broken inference surfaces as a fallback. Acceptable
  because a spurious fallback on valid content is the worst outcome.
- Structuring pane copy updated to set expectations honestly:
  "structuring time depends on your hardware (typically 20–90 s,
  longer on older phones without GPU acceleration)". The previous
  copy didn't prepare users for CPU-path latency.
- New regression test pins the call order `warmUp → backend →
  generate` so a refactor can't accidentally read backend before the
  engine is loaded.
- See [ADR 0016 amendment](docs/decisions/0016-engine-load-inference-split.md#amendment--2026-05-17-evening-backend-aware-budgets).

### Fixed — engine cold reload eating Pass 1 inference budget (2026-05-17)
- Real-device session with 4 sequential notes: first 3 structured
  cleanly, the 4th fell back with "Pass 1 failed (timeout after
  68900ms)" on a 278-char Italian transcript. The number is the exact
  value of `coldStartBudgetFor(278) = 55_000 + 278*50`, confirming
  the budget timer expired on engine load, not on inference.
- Root cause: the Pass 1 `withTimeoutOrNull` was wrapping BOTH the
  ~1.5 GB engine load (variable, 15-30s) and the actual model
  inference (predictable, 5-15s). When `onTrimMemory(TRIM_MEMORY_
  BACKGROUND)` released the engine between captures (any 1-second
  background trip qualified), the next dictation hit a cold reload
  that consumed the entire budget before the model could see the
  prompt. A racing concurrent warm-up from `LifecycleResumeEffect`
  further doubled the load latency by triggering a GPU→CPU fallback
  mid-init.
- Fix (three coordinated changes, see [ADR 0016](docs/decisions/0016-engine-load-inference-split.md)):
  1. `StructureNoteUseCaseImpl` calls `session.warmUp()` BEFORE the
     timed Pass 1 with its own 60s budget. Engine load is no longer
     deducted from inference time.
  2. `LiteRtLmGemmaSession.ensureEngineLoaded()` becomes `suspend` and
     gates engine creation behind a `Mutex`. Concurrent callers wait
     for the single in-flight load instead of racing parallel
     GPU-init attempts. The CAS-then-close pattern is removed — it
     prevented memory leaks but not the cost.
  3. `onTrimMemory` threshold tightened from `TRIM_MEMORY_BACKGROUND`
     to `TRIM_MEMORY_COMPLETE`. The 1.5 GB engine now survives
     normal `Capture → NoteDetail → Capture` round trips; only a
     genuine OS-level OOM signal releases it.
- `COLD_START_BASE_MS` dropped 55s → 15s now that the cold-load
  constant lives in the separate `ENGINE_LOAD_BUDGET_MS = 60_000`.
  Pass 1 budgets for typical notes are now ~25-40s instead of
  ~65-80s — user-visible structuring completes substantially faster
  while remaining safe against thermal/contention noise.
- Added `OrderTrackingSession` test that pins the warm-up-before-
  generate ordering so a future refactor can't accidentally collapse
  the split.

### Fixed — recurring cold-start timeouts on short transcripts
- The accretion of prompt content through v6 → v7 → v8 (Cleanup rules,
  Headings rule, Example 10, FINAL CHECKLIST) gradually pushed the
  static prompt over the 45s cold-start budget. Test 4 (290-char
  transcript) was hitting "Pass 1 failed (timeout after 58900ms)"
  reproducibly because the budget formula gave exactly ~59s for that
  transcript length.
- `COLD_START_BASE_MS` bumped 45s → 55s. Gives 10s of headroom over the
  empirical worst case (Pixel 6a CPU fallback ~50s for short notes
  cold path), so background CPU contention and thermal throttling
  variance don't spuriously cancel valid generation runs.
- FINAL CHECKLIST trimmed from ~300 chars to ~120 chars. Same three
  rules (full-transcript devo scan, 2-4 tags, headings-keep-both),
  expressed as one-line bullets. Recovers most of the prefill cost
  without losing the position-bias benefit.

### Changed — prompt v8: FINAL CHECKLIST added before generation
- Right before "NOW STRUCTURE THIS TRANSCRIPT", a 3-bullet checklist
  reinforces the three rules Gemma was most prone to forget when prompt
  length grew: full-transcript scan for "devo" markers, 2-4 tags
  always, heading-keeps-both-description-and-checkboxes.
- Position matters for small models — the last tokens before generation
  carry disproportionate attention. Real-device traces 2026-05-16 showed
  the checkbox extraction rate dropping from ~95% (v7) to ~50% (v8 before
  the checklist) because the relevant rule had been pushed deeper into
  the prompt by the earlier additions.
- Cost: ~300 chars (~1s of additional prefill on CPU).

### Changed — TagValidator now trusts ≥4-char mono-part tags (final policy)
- Field testing on 2026-05-16 showed the prefix-match heuristic still
  killed legitimate semantic abstractions: tags like `lavoro` on a
  Jira+dentist note (prefix `lavo` doesn't start any word like "Marco",
  "Atlassian", "dentista") or `personale` on a personal note (prefix
  `pers` matches nothing). Result: 75% of generated notes had empty
  tag arrays.
- New policy: any mono-part tag ≥4 chars is TRUSTED unconditionally.
  This restores rich tagging at the cost of accepting an occasional
  hallucinated 4+ char tag (estimated ~5% of cases, user can edit).
- Multi-part kebab tags (`app-development`) keep the strict
  word-boundary anchor — the original Jira-note false-positive
  remains fixed.
- Mono-part ≤3-char tags (`rag`, `seo`, `ai`) keep the strict literal
  word-match — short context-bleed hallucinations stay killed.
- Tag prompt rule (v8) softened in tandem: removed "must be literally
  present" instruction that was making Gemma emit zero tags; added
  explicit nudge "ALWAYS emit 2–4 tags, prefer broad categories
  (lavoro, personale, sogni, salute, idea, riflessione...)".

### Fixed — TagValidator too strict on semantic abstractions (regression)
- The word-boundary fix from earlier today over-corrected: legitimate
  semantic abstractions like the tag `sogni` on a note about
  *"sogno strano"* / *"mi sono svegliato"* (no literal "sogni" word) were
  being stripped. Same for `lavoro` on work notes that never said the word
  "lavoro", `studio` on notes that said "studiare", etc.
- Added a **prefix-match fallback** for mono-part tags ≥4 chars: the
  tag passes if its 4-char prefix starts ANY word in the transcript
  (Unicode-aware). Catches plural/singular variation (`sogn-` matches
  "sogno"), Italian conjugation (`lavor-` matches "lavorando"), and
  general morphological variants — across all Latin-script languages.
- Multi-part kebab tags (`app-development`) keep the strict
  word-boundary rule. Substring-noise stays killed (still no false
  positive for "app" inside "appuntamento").
- Mono-part ≤3 char tags (`rag`, `seo`, `ai`) keep the strict rule too.
  These are the most common shape of context-bleed hallucinations and
  we don't trust them on prefix.
- 7 new test cases for prefix-match behaviour incl. a documented
  known limit (cross-language prefix doesn't work: `riflessione` tag
  won't anchor against French `réflexion` — translation-equivalents
  require a real semantic model, out of scope).

### Changed — prompt v8 trimmed for cold-start budget
- Removed the verbose "Notice Example 10" paragraph (~250 chars) and
  condensed the Headings rule introduction. Same teaching value (Example 10
  itself does the heavy lifting), ~400 chars saved in prompt.
- Real-device traces showed v8's added ~1000 chars pushed test cases
  with short transcripts past the 59s cold-start budget — the timeout
  on screenshot was a direct casualty. Trimming brings the v8 prefill
  cost back close to v7 while keeping the headings-preserve-prose fix.

### Fixed — TagValidator multilingual matching
- Substring matching replaced with Unicode-aware word-boundary regex
  (`(?<![\p{L}\p{M}])…(?![\p{L}\p{M}])`). Previously the tag `app-development`
  passed validation on a Jira/dentist note because "app" matched as substring
  inside "appuntamento". Now matches only whole words. Works identically
  across all 6 v1 languages (it, en, es, fr, de, pt) because `\p{L}` covers
  every Unicode letter — Latin, accented Latin, anywhere.
- Accent folding via `java.text.Normalizer` NFD before matching, so the
  ASCII kebab tag `perche` correctly matches the accented transcript word
  `perché`. Tested on French (`reflexion` ↔ `réflexion`), Spanish
  (`espana` ↔ `España`), German (`buro` ↔ `Büro`), Portuguese (`reuniao`
  ↔ `reunião`).
- Apostrophe-elision boundaries handled: `l'app` correctly matches tag
  `app`, `Sarah's` correctly matches tag `sarah`.
- 11 new test cases covering false-positive substring rejection across
  4 languages, accent folding across 4 languages, and apostrophe-elision
  edge cases.

### Changed — prompt v8 (event description preserved under headings)
- `structure_note_v8.txt` adds a new top-level `Headings — REQUIRED rule`
  stating explicitly that a `##` heading is a topic LABEL and never
  replaces the event description prose. Worked Example 10 demonstrates
  a multi-topic note where each `##` carries a full event description in
  prose between the heading and the action checkboxes.
- Fixes the 2026-05-16 regression where v7 was dropping
  *"riunione con Marco e il team di Atlassian alle 15:30 per parlare
  della migrazione di Jira"* and rendering just `## Riunione Jira` +
  the three preparation checkboxes.
- `AssetPromptLoader.ACTIVE_PROMPT` flipped to v8.

### Added — deterministic post-processing pipeline (ADR 0015)
- **`RelativeDateTimeResolver`** (`:core:inference/.../normalize/`): multilingual
  table-driven resolution for simple relative expressions across all 6 supported
  languages (en, it, es, fr, de, pt). "stasera" / "tonight" / "ce soir" / "esta
  noche" / "heute abend" / "esta noite" → exact `Instant` via `clock + ZoneId`.
  Overrides Gemma's `iso_resolved` for simple matches; abstains on compound
  expressions ("stasera tardi") and lets the model decide. Fixes the
  "stasera resolved to yesterday" bug seen on 2026-05-16.
- **`MarkdownBodyFormatter`**: regex pipeline that ensures every `- [ ]` and
  `- ` starts on its own line, prose→block transitions have a blank line,
  triple-newlines collapse to double, trailing whitespace stripped. Idempotent.
  Eliminates the "inline checkbox glued to prose" bug.
- **`TagValidator`**: strips tags that have no anchor in the transcript or in
  the user's existing-tags corpus. Multi-part kebab tags are anchored if any
  ≥3-char part appears; mono-part short tags require literal presence. Kills
  hallucinated tags like "rag" appearing from nowhere.
- **Title sanitization**: strip trailing punctuation (`.,;:!?"'`) so Gemma's
  "Riunione con Marco." becomes a clean heading.
- All three post-processors are pure functions with full unit test coverage
  (~45 test cases combined), no LLM-in-the-loop required for testing.

### Changed — prompt v7 (slimmer, semantically-focused)
- `structure_note_v7.txt` removes formatting whitespace rules and
  surface-form precision rules (now enforced by `MarkdownBodyFormatter` and
  the deterministic resolvers).
- Sharpened the "Checkboxes vs prose" distinction with two worked examples:
  Example 8 (three "devo" commitments scattered in continuous prose, all
  extracted as checkboxes) and Example 9 (meta-speech preserved verbatim
  instead of smoothed into a fake "devo ricordarmi" commitment).
- `AssetPromptLoader.ACTIVE_PROMPT` flipped to v7.

### Changed — notes list card layout
- Timestamp moved from the tag-chip row (where it was being squeezed into a
  ridiculous 5-line vertical column when 3+ tags were present) to a small
  muted label above the title.
- Switched to relative smart formatting:
  - Today → `HH:mm` (e.g. `08:37`)
  - Yesterday → `Yesterday HH:mm`
  - This week (2-6 days) → `EEE HH:mm` (e.g. `Sat 18:02`)
  - Older same-year → `d MMM`
  - Older different-year → `d MMM yyyy`
- Tag row now collapses entirely when a note has no tags (was rendering an
  empty padded row taking visual space).

### Fixed — UX during structuring
- **`FLAG_KEEP_SCREEN_ON` during `Phase.Structuring`**: a `DisposableEffect`
  in `StructuringPane` adds the window flag while structuring is active and
  removes it the moment we move on. Eliminates the screen-off → process
  throttling → cold-start timeout chain that triggered plain-text fallbacks
  in real-device use.
- **Progress indicator → elapsed-only**: dropped the "estimated ~Xs" half of
  the readout; shows just "Xs elapsed" anchored to `System.currentTimeMillis()`.
  The wall-clock anchor means lifecycle pauses (screen off) don't desync the
  displayed time. An honest "we don't know exactly" beats a wrong promise.

### Fixed — cold-start structuring timeouts
- `StructureNoteUseCaseImpl.COLD_START_BASE_MS` raised from 30s to 45s.
  Real-device traces (Pixel 6a, CPU fallback) showed 500-700 char notes
  timing out at the previous budget because engine cold-load varies
  15-25s depending on whether the OS file cache is warm and whether
  `onTrimMemory` had recently released the allocation. The bump absorbs
  both edges without inflating the worst-case budget.
- New `CaptureViewModel.warmUpIfNeeded()` public method, invoked from
  `CaptureRoute` via `LifecycleResumeEffect`. The VM's init-time
  warm-up only fires once per VM lifetime; this hook fires every
  `ON_RESUME` so the engine reloads the moment the user returns to the
  capture screen after `onTrimMemory` released it (typically while the
  user was in the notes list or settings). Closes the most common
  source of plain-text fallback events seen in field use.

### Added — orthographic cleanup in structuring prompt (ADR 0014)
- `structure_note_v6.txt` adds a `Cleanup — REQUIRED orthographic corrections`
  section that permits the model to fix obvious typos, wrong word endings,
  missing accents, and clearly broken word-segmentation from dictation
  ("integrale rno" → "intorno", "arrabbia" → "arrabbiata"), while explicitly
  forbidding fixes on irrecoverable garbled fragments and on proper nouns
  even when they look unusual ("Carusi", "Lakia", "Remo reale" stay verbatim).
- Worked Example 7 demonstrates all three behaviours — fixed typos, kept
  garbled fragment, preserved proper noun — on one combined transcript.
- `AssetPromptLoader.ACTIVE_PROMPT` flipped to `structure_note_v6.txt`. v5
  remains in assets/ as the rollback target.
- The "no invention" pillar is preserved as the higher-priority rule —
  the prompt's conflict-resolution clause says cleanup loses to
  no-invention whenever they meet.

### Added — tag consistency via prompt context (ADR 0012)
- `PromptTemplate.render(transcript, now, zone, existingTags)` extended with the
  list of tags already in use across the user's saved notes. `StaticPromptTemplate`
  substitutes `{{EXISTING_TAGS}}` with up to 50 of them, joined by `, `. Empty list
  on the first note (no consistency pressure).
- `StructureNoteUseCase.invoke(transcript, forceLanguage, existingTags)` forwards
  the corpus snapshot to both Pass 1 and Pass 2.
- `CaptureViewModel` maintains a `@Volatile` snapshot of
  `noteRepository.observeAllTags()` (mapped to `String`) and passes it on every
  structuring call. Room re-emits on every change so deletions and edits are
  reflected automatically.
- `structure_note_v5.txt` adds an `EXISTING TAGS (already used across the user's
  notes): {{EXISTING_TAGS}}` line plus a REQUIRED reuse rule and a worked Example 6
  showing Gemma reusing `app-development` instead of coining `app` for the same
  topic.

### Added — optional biometric launch lock (ADR 0013)
- New `Security` section in Settings with a **Require biometric unlock** toggle.
  Off by default — the privacy promise holds even when off; the toggle adds
  device-shared-access protection on top.
- `UserSettings.requireBiometricUnlock: Boolean` (default `false`) persisted via
  a new DataStore key `require_biometric_unlock`. No migration step required.
- `MainActivity` extends `FragmentActivity` (was `ComponentActivity`) and gates
  the Compose tree behind a `BiometricPrompt` using `BIOMETRIC_STRONG`. Triggers
  on cold launch and on resume from background. No device-PIN fallback by design.
- `SettingsViewModel.onBiometricAvailability(available)` auto-disables the
  persisted toggle when the device reports it can no longer authenticate (user
  removed their fingerprint between sessions), preventing lock-out.
- `SettingsRoute` queries `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`
  once on entry and disables the toggle row with an inline hint when the
  device has no enrolled biometric.
- No new manifest permissions. The "no INTERNET permission" CI check (ADR 0007)
  is unaffected.

### Fixed — search input lag
- `NotesViewModel.onIntent(UpdateQuery)` now writes to `_uiState` immediately
  while still forwarding to the 250ms-debounced filter pipeline. The
  collect block no longer overwrites `state.query`, only the filtered notes,
  so typing feels native instead of frozen.

### Fixed — auto-capitalize on text input fields
- The Notes search field, the capture review body, and the note-detail
  title + body all now request `KeyboardCapitalization.Sentences` so the
  system keyboard surfaces a capital letter at the start of a thought.

### Added — engine lifecycle and inference performance
- `LiteRtLmGemmaSession` now manages the LiteRT-LM `Engine` as a memory-pressure-
  aware singleton. Implements `ComponentCallbacks2` and releases the engine on
  `onTrimMemory(level >= TRIM_MEMORY_BACKGROUND)`. Subsequent calls reload
  lazily. Prevents the ~1.5 GB native allocation from causing process kills on
  4 GB-RAM devices. See ADR 0009.
- CAS-protected concurrent initialization in `ensureEngineLoaded`: if two
  callers race to create the engine simultaneously, the loser explicitly
  `close()`-es the redundant 1.5 GB allocation before returning the winner's
  reference.
- **Pre-warming** via `GemmaSession.warmUp()` (default no-op), overridden in
  `LiteRtLmGemmaSession` to call `ensureEngineLoaded()` on
  `dispatchers.default`. `CaptureViewModel.init` fires it fire-and-forget so
  the engine is loading in background while the user is reading the capture
  screen and tapping the mic. Eliminates cold-start latency from the
  user-perceived first-structuring time.
- **Backend probing**: `engineFactory` tries `Backend.GPU()` first, falls back
  to `Backend.CPU()` on init failure. On devices where the LiteRT-LM kernels
  compile against the platform GPU driver, structuring drops from ~60s to
  ~15-25s for the same 1000-char note. Known fail-mode on Pixel 6a Mali-G78
  with LiteRT-LM 0.11 — silent fallback ensures the app still runs. See
  ADR 0011.
- **MTP speculative decoding** via `ExperimentalFlags.enableSpeculativeDecoding = true`
  before each engine creation. ~25% decode speedup on CPU, 2-3× on GPU, when
  the model file carries MTP drafter heads (Hugging Face re-publication of
  `gemma-4-E2B-it-litert-lm` from 2026-05-05 onward). Wrapped in `runCatching`
  so an API rename in a patch release degrades gracefully to non-speculative
  inference instead of crashing the build.
- File-level `@file:OptIn(ExperimentalApi::class)` in `LiteRtLmGemmaSession`
  for the LiteRT-LM experimental annotation set.
- `LiteRtLmGemmaSession` now logs every engine init under tag `VoiceNoteGemma`
  with the active backend and the active MTP state — visible via
  `adb logcat -s VoiceNoteGemma`. One line of grep tells you exactly which
  inference path the engine landed on for the current device.

### Added — temporal reasoning
- `PromptTemplate.render(transcript, now: Instant, zone: ZoneId)` extended with
  current-time markers. `StaticPromptTemplate` substitutes `{{NOW_ISO}}` and
  `{{NOW_TIMEZONE}}` in addition to `{{TRANSCRIPT}}`. The new `structure_note_v3.txt`
  prompt embeds these as a `CURRENT TIMESTAMP: ...` / `USER TIMEZONE: ...` block,
  letting Gemma anchor relative datetime references in the transcript to real
  ISO-8601 timestamps in the `mentions` array. Pre-v3, `iso_resolved` was always
  `null` for relative references. See ADR 0010.
- New `structure_note_v4.txt` prompt (currently `ACTIVE_PROMPT`): tightens the
  body-formatting rules into REQUIRED-when-X form (the 2B-effective model
  respects this much better than the prior "ONLY when X" framing), adds a
  "DATETIME REFERENCES ONLY" rule for the `mentions` array after observing
  E2B emit person names there, adds an explicit "do not translate" rule with
  an English example after observing it auto-translate English transcripts to
  Italian, and adds a multi-topic example with `##` headings. Five examples
  total covering prose, list, mixed task + context, multi-topic, English.
- `StructureNoteUseCaseImpl.tryParseInstant` now accepts three ISO formats Gemma
  can emit: UTC instant (`Instant.parse`), offset datetime (`OffsetDateTime.parse`),
  and date-only (`LocalDate.atStartOfDay(systemDefault())`). Earlier code only
  accepted the UTC `Z` form and silently dropped the other two — meaning even
  when Gemma resolved a date correctly, the stored `DateMention.resolved` was
  `null`. Fixed.
- `MentionsSection` composable in `:core:design`: renders the datetime mentions
  from a `Note` as chips with the surface form in curly quotes and the resolved
  ISO timestamp formatted in the user's locale ("gio 14 mag 2026 · 10:30").
  Wired into both the `CaptureRoute` review pane and `NoteDetailRoute`. Renders
  nothing when there are no temporal references. Makes on-device temporal
  reasoning visible to the user, not just stored in the DB.

### Added — ASR robustness
- `AndroidSpeechToTextSession` continuous-listen loop with an `AtomicBoolean
  stopRequested` flag. On `onResults` and on `onError(ERROR_NO_MATCH /
  ERROR_SPEECH_TIMEOUT)`, the listener schedules a 50ms-delayed
  `recognizer.startListening` restart, but only if `stopRequested` is false.
  `awaitClose` and `stop()` set the flag and call
  `mainHandler.removeCallbacksAndMessages(null)` to cancel any pending restart.
  Pauses (the user thinking mid-dictation) no longer terminate the recording
  mid-sentence; only `onError` with a fatal code (mic unavailable etc.) closes
  the flow.
- Mark `TranscriptChunk.isFinal = true` inside `onResults` (was incorrectly
  `false`). Downstream code can now reliably distinguish partials from finals.
- Reset `lastTranscript = ""` as the first line of `start()` so a session that
  fails before reaching `stop()` doesn't carry stale text into the next session.

### Added — UI/UX
- `CaptureUiState.structuringStartedAtMs` field plus a `StructuringPane` rewrite
  that shows an elapsed-time counter and a per-transcript-length estimate
  ("23s elapsed · estimated ~55s") plus an honest privacy note ("Gemma 4 E2B
  is running locally on your device. Your audio and transcript never leave
  the phone."). On-device inference is 15-60s on mid-tier mobile CPU; the
  spinner now communicates that work is progressing rather than feeling
  infinite.
- Long-note advisory banner in `RecordingPane`: when `partialTranscript.length`
  exceeds 2000 chars (≈ 3-4 minutes of dictation), a soft notice appears under
  the listening indicator: "Long note — structuring may take a bit longer and
  may simplify long stretches." Sets expectations honestly for the edge case.
- Capture screen now also pre-emits engine warm-up (see "engine lifecycle"
  block above).

### Added — capture flow correctness
- Debounce on `ToggleRecord` in `CaptureViewModel`: taps within 300ms of the
  prior toggle are ignored. Prevents the double-tap race that could launch a
  second permission request or a second recognizer before the first releases
  the mic. Required by CLAUDE.md §10.
- `stopRecordingAndStructure` sets `phase = Structuring` synchronously *before*
  launching the structuring coroutine, plus a guard that returns early if
  phase is not `Recording`. Closes the race where the natural `flow.collect`
  completion path and a user-initiated stop could both reach
  `stopRecordingAndStructure` and invoke `structure()` twice.
- `handleSave` (append path) refreshes `updatedAt = Instant.now(clock)` on the
  copy of `existingNote`. Prior code preserved the original timestamp,
  surfacing as "note never appears to update" in the notes list.
- `handleDiscard` preserves `isAppending` (and `activeLanguage`) when resetting
  state. Prior code dropped `isAppending`, sending the next dictation to the
  "new note" path even when an `appendId` was still in scope.
- `clock` in `CaptureViewModel` is now an `internal var Clock = Clock.systemUTC()`,
  matching the existing pattern in `NoteDetailViewModel`. Was constructor
  parameter with default value, which Hilt does not honour for `@Inject`
  constructors — would have failed binding at compile time.

### Added — structuring use case
- **Defensive transcript-scaled timeouts** in `StructureNoteUseCaseImpl`. Pass 1
  budget = `30s + 50ms × len(transcript)`, capped at 150s. Pass 2 budget =
  `8s + 50ms × len(transcript)`, capped at 150s. Earlier fixed 8s timeout
  caused legitimate (slow but successful) Gemma responses to be discarded
  because the Kotlin coroutine cancelled before the native generation returned.
  Empirical formula matches Pixel 6a (Tensor G1, CPU) measurements: 15-20s for
  short notes, 50-60s for 1000-char notes. See ADR 0009.
- **Short-circuit to fallback when Pass 1 times out** instead of trying Pass 2
  with a still-busy native engine. The retry only fires when Pass 1 returned a
  response that failed JSON parsing — the case Pass 2 was designed for. Avoids
  concurrent `Conversation.sendMessage` calls on the same engine, which
  litertlm 0.11.0 does not guarantee thread-safe.
- Debug message in `lastRawResponse` now distinguishes timeout from exception
  (`"Pass 1 failed (timeout after 80000ms)"` vs
  `"Pass 1 failed (exception: model file not found)"`).

### Added — onboarding state-based navigation
- `OnboardingUiState.isCompleted: Boolean` flag, set in `OnboardingViewModel`
  both at init (when prior-launch `hasCompletedOnboarding` is true) and on
  `Finish`/`Skip`. `OnboardingRoute` consumes it via
  `LaunchedEffect(state.isCompleted) { if (isCompleted) onCompleted() }`.
- `OnboardingUiEvent` sealed interface removed entirely. The prior design used
  `MutableSharedFlow(replay = 0)` to fire a `Completed` event; for returning
  users the event was emitted in `init` before the route's `LaunchedEffect`
  had a chance to subscribe, dropping it silently and stranding the user on a
  blank screen.

### Added — notes list
- **Multi-select bulk delete.** Long-press a note to enter selection mode,
  tap more notes to add them, then tap the new trash icon in the top app bar.
  An `AlertDialog` confirms with singular/plural copy ("Delete 1 note?" /
  "Delete N notes?"). Confirmed deletes loop through `noteRepository.delete(id)`
  — cascading FKs on tags + mentions clean up automatically. New
  `NotesUiIntent.RequestDeleteSelected` / `ConfirmDeleteSelected` /
  `DismissDeleteSelected`, plus `NotesUiEvent.SelectionDeleted(count)` for the
  snackbar feedback.
- **YAML frontmatter for Markdown export.** Both the ZIP bulk export and the
  single-note Share intent now produce Obsidian-compatible Markdown with full
  frontmatter: `title`, `created`, `updated`, `language`, `tags`, `mentions`
  (each with `surface` + `iso`), `structured`. Single source of truth in
  `Note.toMarkdownWithFrontmatter()` in `:core:common`, used from both
  `NotesViewModel.exportToZip` and `NoteDetailViewModel.handleShare`. Drop the
  `.md` file into Obsidian/Hugo/Jekyll/LogSeq and the metadata round-trips.
- ZIP export filename collision fix: `${dateStr}_${safeTitle}_${note.id.take(6)}.md`.
  Two "Untitled" notes from the same day no longer produce duplicate entries.
- `NotesViewModel.ioDispatcher` is now an `internal var CoroutineDispatcher =
  Dispatchers.IO`, allowing tests to substitute `testDispatcher` and have
  `advanceUntilIdle` actually wait for the ZIP writing to finish before
  assertions. Same seam pattern as the `clock` in capture and noteDetail.

### Added — tests and tooling
- `androidx.test:core` added to `:core:database`'s test dependencies for
  `ApplicationProvider.getApplicationContext()`. Robolectric stopped pulling
  it transitively in 4.x.
- Robolectric upgraded from 4.13 to 4.14.1 to support targetSdk 35
  (`DefaultSdkPicker` was rejecting the SDK version).
- `runTest(testDispatcher)` instead of `runTest(Dispatchers.Unconfined)` in
  `PreferencesSettingsRepositoryTest`. Newer `kotlinx-coroutines-test` rejects
  non-`TestDispatcher` contexts to keep virtual time honest.
- `NoteRepositoryImplTest`'s `should filter by tag` no longer depends on
  SQLite tie-break behaviour for equal `created_at` values — explicit distinct
  timestamps make the assertion deterministic.
- `NotesViewModelTest.ZIP filename univoco` updated to construct full `Note`
  objects (all 9 fields; Antigravity scaffolds had only 5 set).
- `CaptureViewModelTest` rewritten: `coVerify` instead of `verify` for the
  suspend `noteRepository.update`, `coEvery { use case } coAnswers {
  awaitCancellation() }` to hold the structuring coroutine in `Structuring`
  state for the assertion, `settingsRepository.observe()` stubbed to return a
  non-empty `flowOf(UserSettings.Default)` so `.first()` in the production code
  doesn't throw `NoSuchElementException`.
- `OnboardingViewModelTest` rewritten to assert state (`isCompleted`) instead
  of consuming a removed `uiEvents` flow.
- `SettingsViewModelTest`'s `should ignore concurrent imports` uses a
  `CompletableDeferred` gate to hold the first import in flight, so the
  second-call drop is observable under the existing `UnconfinedTestDispatcher`.
- `failOnNoDiscoveredTests = false` in both `AndroidLibraryConventionPlugin`
  and `AndroidApplicationConventionPlugin`. Library modules with no `@Test`
  methods (currently `:core:design`, which is pure Compose UI) used to fail
  the `test` task on Gradle 9; now they're correctly treated as a no-op.
- `LiteRtLmGemmaSessionTest` is intentionally disabled in source —
  `litertlm-android:0.11.0` ships Java 21 bytecode and the test JVM toolchain
  is Java 17, which throws `UnsupportedClassVersionError` on class load.
  The file is kept as a stub with a long comment explaining the constraint
  and how to restore the tests when the toolchain moves to Java 21.

### Fixed
- Imports reordering in `LiteRtLmGemmaSession.kt`: `android.content.ComponentCallbacks2`
  and `android.content.res.Configuration` were placed below the KDoc block,
  which is legal Kotlin but disorienting for the reader and a ktlint candidate.
  Moved to the top of the file.
- Stripped transitive `INTERNET`, `ACCESS_NETWORK_STATE`, and Google datatransport
  components that `com.google.mediapipe:tasks-genai` was silently merging into the
  final manifest. See ADR `0007-strip-transitive-network-perms.md`.
- Privacy CI gate now also fails on any `com.google.android.datatransport.*`
  service/receiver/provider that survives the merge — closing the second half of the
  same hole.
- The `tools:ignore="MissingClass"` annotation on the
  `com.google.android.datatransport.*` stripping declarations silences Lint
  errors that complained about the classes not being on the classpath (which
  is exactly the point — the strip is defense in depth, the classes shouldn't
  be there).

### Known technical debt
- `LiteRtLmGemmaSessionTest` is currently a stub. Restore the tests by
  upgrading the toolchain to Java 21 in `libs.versions.toml` (`java = "21"`),
  OR by refactoring `LiteRtLmGemmaSession` to take an abstract `LlmEngine`
  interface so the test can mock without loading the Java-21 artifact.
- Detekt and ktlint are declared in `libs.versions.toml` but never applied
  to any module. The `detekt` and `ktlintCheck` Gradle tasks don't exist
  today. Apply them in the convention plugins for v1.1.
- `NotesViewModel.exportToZip` still uses `Dispatchers.IO` indirectly via the
  injected `ioDispatcher` seam. Migrate to `AppDispatchers.io` injection at
  the convention-plugin level so feature modules don't have to expose
  per-VM dispatcher seams.
- v1 prompt (`structure_note_v1.txt`) and v2 (`structure_note_v2.txt`) stay
  in `assets/prompts/` as legacy. Active prompt is v4. Drop v1+v2 in a v1.1
  cleanup pass to shave ~6 KB off the APK.
- Date math precision: on E2B, "lunedì" sometimes resolves off-by-one (Sunday
  or the Monday after next). Acceptable for v1 because the resolved ISO is
  visible in the UI and the user can correct; evaluate larger E4B variant
  in v2 for improvement.
- Empty stub file `MediaPipeGemmaSession.kt` left in the package after the
  LiteRT-LM migration so git history attaches to a stable path. Delete in
  v1.1 cleanup pass.

### Added — initial scaffolding (kept for historical context)
- Project scaffolding: multi-module Gradle layout (`:app`, `:core:*`, `:feature:*`).
- Architecture Decision Records (ADRs) under `docs/decisions/`.
- Apache 2.0 license.
- CI workflow with Detekt, ktlint, unit tests, coverage, and a build-failing check
  that rejects `INTERNET` permission in the merged manifest.
- Initial Gemma structuring prompt (`structure_note_v1.txt`) with strict JSON contract.
- Domain models (`Note`, `Tag`, `Language`, `StructuredNote`, `Result`).
- `SettingsRepository` domain interface and `UserSettings` model in `:core:common`.
- `PreferencesSettingsRepository` (DataStore-backed) and Hilt module in
  `:core:datastore`, with unit-test coverage for round-tripping forced language and
  the onboarding flag.
- `:feature:capture` — `CaptureViewModel` with the full MVI surface (StateFlow uiState
  + SharedFlow uiEvents + onIntent), end-to-end recording → structuring → review → save
  state machine, real Compose screen with mic-permission gating, recording control,
  structuring loader, and a pre-save preview that lets the user edit title and body.
  Eight-test suite covering happy path, blank-transcript guard, plain-text fallback,
  forced-language pass-through, save / discard, and intent debouncing.
- `:feature:notes` — `NotesViewModel` over the repository's reactive streams with
  debounced text search and tag filtering; LazyColumn list UI with note cards
  (title, snippet, tags, date) and stable keys; empty-state copy that varies by
  filter context.
- `:feature:notedetail` — `NoteDetailViewModel` reading via `SavedStateHandle`, with
  edit / cancel / save / delete / share intents; share emits `text/markdown` with the
  title rendered as a heading and tags appended; in-app delete dialog; six-test suite.
- `:feature:settings` — `SettingsViewModel` and Material 3 settings screen with a
  privacy section (verification instructions + permission manifest), language picker
  (Auto + 6 supported BCP-47 locales), and a destructive "Delete all notes" path
  gated by an explicit confirmation dialog; six-test suite.
- Navigation graph now declares the `noteId` argument explicitly so Hilt's
  `SavedStateHandle` can pick it up in `NoteDetailViewModel`.
- `:feature:onboarding` — three-screen first-launch welcome (`Speak.` /
  `We make it Markdown.` / `Audio never leaves your phone.`) backed by an
  `OnboardingViewModel` that gates visibility on `UserSettings.hasCompletedOnboarding`
  and writes the flag through `SettingsRepository.markOnboardingComplete()`. Wired
  into the navigation graph as the start destination; `Capture` replaces it on the
  back stack the moment onboarding completes (or is detected as already complete).
  Four-test suite.
- `MarkdownText` composable in `:core:design` — Markwon-backed renderer with the
  `core` / `tables` / `tasklist` extensions. Note detail view now renders the body
  as proper Markdown (headings, lists, tables, checkboxes) when not in edit mode.
- Splash: `MainActivity` now installs the AndroidX `SplashScreen` API before
  `super.onCreate`, so the launcher theme `Theme.VoiceNoteMarkdown.Splash` becomes
  the cold-start window background and is then swapped for the app theme.
- Prompt evaluation suite — 12 transcript / expected-JSON pairs in EN / IT / ES /
  FR / DE / PT plus an adversarial empty-input fixture under
  `core/inference/src/test/resources/prompt-eval/`. New `PromptEvalTest` loads them
  via the test classloader, runs each through `StructureNoteUseCaseImpl` wired with
  a deterministic `PinnedGemmaSession`, and asserts schema conformance + language
  round-trip + the empty-input fallback path.
- Detekt: extended `style.ForbiddenImport` to also block
  `android.media.MediaRecorder` / `MediaMuxer` / `MediaCodec` (audio persistence
  pillar). The legacy `custom.ForbiddenImport` block is kept disabled so the
  canonical rule is the only source of truth.
- `NoAudioPersistenceTest` rewritten so the source-grep strips Kotlin comments
  before matching — preventing false positives on KDoc that legitimately mentions
  the forbidden classes — and extended to cover `MediaMuxer` / `MediaCodec`.
- **Inference runtime swap**: replaced `com.google.mediapipe:tasks-genai` /
  `tasks-text` with `com.google.ai.edge.litertlm:litertlm`. Gemma 4 E2B is
  distributed as a `.litertlm` bundle and the MediaPipe runtime cannot load
  that format. New `LiteRtLmGemmaSession` is a drop-in for the deleted
  `MediaPipeGemmaSession`; the `GemmaSession` interface is unchanged. See
  ADR 0008 (which supersedes ADR 0004's runtime + delivery sections).
- **Model delivery via Storage Access Framework**, not `DownloadManager`. New
  `OnDeviceModelRepository` interface in `:core:common` with a
  `FileBasedOnDeviceModelRepository` impl in `:core:inference` that streams
  the picked file to a `.part` sibling and atomic-renames onto
  `filesDir/models/gemma-4-e2b-it.litertlm`. Added `Settings → On-device model`
  section with status badge, "Import .litertlm" button (SAF picker), "Replace"
  / "Remove" affordances, and a friendly error snackbar.
- `ModelFileProvider` now resolves the model from two locations in priority
  order: app-private `filesDir/models/...` (where SAF imports land) and
  app-scoped `getExternalFilesDir("models")/...` (the convenience path for
  `adb push` during development).
- New tests: `FileBasedOnDeviceModelRepositoryTest` (atomic write, partial-stream
  failure → no leftover, delete reverts status). `SettingsViewModelTest` extended
  to cover model status surface, byte streaming on import, error reporting,
  concurrent-import gating, and explicit model deletion.
- `core:inference` `noCompress` now also covers `.litertlm` (in addition to the
  legacy `.task`) so the bundle never gets re-compressed if anyone bundles it.
- `Language` now carries both a primary BCP-47 tag (`it`) and a locale-tagged
  recognizer form (`it-IT`). The Android `SpeechRecognizer` is fed the latter,
  fixing OEM devices that ship only locale-keyed offline language packs.
- Capture screen: the language chip is now tappable and opens a Material 3
  `ModalBottomSheet` with Auto + 6 supported languages, persisting the choice
  through `SettingsRepository` so it survives launches and round-trips with
  Settings. Includes an inline tip pointing the user to Android's offline
  language pack settings.
- `StructureNoteUseCase` now returns `StructuringResult(note, lastRawResponse)`
  instead of bare `Note`. On fallback, the raw model response surfaces in the
  Capture review pane as a debug card and is emitted to Logcat under tag
  `VoiceNoteGemma`, so prompt regressions are diagnosable without re-recording.
- New prompt `structure_note_v2.txt`, roughly half the size of v1 and tighter on
  heading rules. Tuned for Gemma 4 E2B's ~2B effective parameters.
  `AssetPromptLoader.ACTIVE_PROMPT` now points to v2; v1 stays as legacy.

### Fixed
- Stripped transitive `INTERNET`, `ACCESS_NETWORK_STATE`, and Google datatransport
  components that `com.google.mediapipe:tasks-genai` was silently merging into the
  final manifest. See ADR `0007-strip-transitive-network-perms.md`.
- Privacy CI gate now also fails on any `com.google.android.datatransport.*`
  service/receiver/provider that survives the merge — closing the second half of the
  same hole.

### Privacy commitments (enforced from day 1)
- No `INTERNET` permission requested. CI gate now provably catches transitive
  re-introductions and any datatransport background workers.
- No audio files written to disk at any point.
- No analytics, no crash reporting, no telemetry.

[Unreleased]: https://github.com/REPLACE_ME/voice-note-markdown/compare/HEAD
