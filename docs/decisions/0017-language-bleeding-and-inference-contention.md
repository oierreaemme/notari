# 17. Language bleeding and inference contention

Date: 2026-05-22
Status: Accepted

## Context

Real-device testing on the latest build (Mario, Pixel 6a) surfaced two
distinct defects, both on the structuring path:

1. **Language bleeding.** With the dictation language pinned to English,
   notes came back with mixed Italian/English titles and Italian tags
   (e.g. a title like *"Privacy promise è il prodotto"*, tags `personale`).
   The bleed was reproducible on short English notes.

2. **Spurious timeouts.** A short note's structuring occasionally timed
   out even though an identical note had structured fine moments earlier —
   the recurring "the next note times out" pattern from ADR 0016, still
   appearing after the engine-load/inference split.

An independent review (Antigravity) was commissioned on the same build
without code changes. It diagnosed both issues accurately; this ADR
records the decisions, including where we diverged from its proposed fix.

### Root cause — language bleeding

Two compounding factors:

- **Italian-dominated few-shot corpus.** The active prompt
  (`structure_note_v8.txt`) carried 10 worked examples, **9 Italian and 1
  English**. On an autoregressive INT4 model, a 9:1 example imbalance is a
  strong prior toward Italian tokens regardless of the input language.
- **Cross-language tag contamination.** ADR 0012's `{{EXISTING_TAGS}}`
  corpus is injected unfiltered. For a user whose history is mostly
  Italian, an English dictation was nudged (prompt rule: "REUSE that exact
  tag string") to emit Italian tags — and once an Italian token lands in
  the `tags` field, the generation drifts Italian for `title` and
  `body_markdown` too.

A contributing weakness: `LanguageScopedPromptTemplate` (the pinned-language
decorator, added 2026-05-22) instructed the model using only the bare
BCP-47 code (`"en"`/`"it"`). For a 2B-effective model, the code is a far
weaker steer than the explicit language name.

### Root cause — inference contention

`Conversation.sendMessage()` in `LiteRtLmGemmaSession` is a **synchronous,
blocking native (C++) call**. The structuring use case wraps each pass in
`withTimeoutOrNull`, which cancels the *Kotlin* coroutine on timeout — but
the native thread does not observe Kotlin cancellation and keeps running to
completion. There was no guard preventing a *second* `generate()` from
starting while a timed-out-but-still-running native inference occupied the
cores. On the CPU fallback path the two inferences saturated the cores,
triggering thermal throttling and pushing subsequent captures past their
budget. This is the same family as ADR 0016 but a different mechanism:
0016 was load-vs-load racing; this is inference-vs-zombie-inference.

## Decision

### 1. Rebalance and prune the prompt → `structure_note_v9.txt`

Reduce the worked examples from 10 to 6, balanced **3 English / 3 Italian**,
each still teaching one distinct rule (pure prose, bullet list, event-vs-
commitment, orthographic cleanup, meta-speech preservation, multi-topic
headings + tag reuse). This removes the language prior and, as a bonus,
cuts the static prompt from **11148 → 8787 bytes (~21%)**, directly
reducing the CPU prefill cost (the dominant term on the no-GPU path —
~46s for the old prompt per ADR 0016's empirical fit).

Also added an explicit prompt rule: *tags MUST be in the note's own
language; do NOT reuse an EXISTING TAG whose language differs.* This is the
in-prompt guard for the auto-detect case (where no code-side language
filter applies).

### 2. Scope the existing-tags corpus to the active language

`CaptureViewModel` now keeps a snapshot of whole notes (`observeAll()`)
instead of a flat tag list, and at structure time passes only the tags
from notes in the pinned language. With no pin, the full corpus is passed
unchanged and rule (1)'s prompt guard governs.

### 3. Use the language name (not the bare code) in the language lock

`LanguageScopedPromptTemplate` now renders *"the user selected English
(BCP-47 \"en\") … write everything ENTIRELY in English"* using
`Language.name`. Behaviour-neutral for stored language (the use case still
forces it deterministically); this only strengthens the model steer.

### 4. Single-flight the native inference

`LiteRtLmGemmaSession.generate()` now wraps `runGenerate()` in a dedicated
`generationMutex.withLock`. Two native inferences can never run at once: a
second caller suspends until the in-flight one finishes rather than piling
on. A suspended waiter is still cancellable, so a use-case timeout on the
waiter falls through to the plain-text fallback cleanly. Engine loading
stays outside this lock (it has its own `engineLoadMutex`), so background
warm-up is unaffected.

## Where we diverged from the external review

The review correctly diagnosed the native non-cancellation/contention, but
its remediation was only "prune the prompt + raise the CPU timeouts"
(cold 60→75s, warm 50→60s). We **rejected raising the timeouts**: without a
single-flight guard, a longer budget keeps the zombie native thread alive
*longer*, which can worsen contention rather than fix it. Decision (4)
addresses the mechanism the review identified; the budgets from ADR 0016
are left unchanged. We also went further than the review on language by
rebalancing the example set (the review kept 7 examples at 6:1), since the
example imbalance — not just tag contamination — was a primary driver.

## Alternatives considered

- **True cooperative cancellation of the native call.** Ideal, but
  LiteRT-LM exposes no cancel token on `sendMessage()` in the version we
  ship. Single-flighting is the pragmatic guard until the runtime offers
  one. Follow-up below.
- **Fail-fast when busy** (reject a second generate immediately rather than
  queue). Rejected for now: queuing-then-timing-out is simpler and the
  warm budget usually covers the wait. Revisit if users report long waits.
- **Drop tag reuse entirely.** Throws away ADR 0012's value for
  same-language users. The per-language filter keeps the benefit without
  the cross-language harm.

## Consequences and trade-offs

- English dictation with an Italian history should now produce English
  title/tags/body. Auto-detect notes are guarded by the prompt rule only,
  which is weaker than the pinned path — acceptable for v1.
- The device runs at most one inference at a time; rapid re-captures queue
  instead of thrashing the CPU. Net effect on the "next note times out"
  pattern should be elimination of the contention contribution.
- v9 is ~21% smaller, so cold CPU prefill is proportionally faster.
- Risk: pruning examples could regress a formatting behaviour an example
  uniquely taught. Mitigated by keeping one example per distinct rule and
  by the deterministic post-processing pipeline (ADR 0015) that enforces
  formatting regardless of model output.

## Follow-ups

- Re-test on the Pixel 6a: confirm English notes are monolingual and that
  back-to-back short notes no longer time out.
- File an upstream request / track LiteRT-LM cancellation support; replace
  the single-flight guard with real cancellation when available.
- Post-submission: an on-device eval comparing the prompts across the 6
  languages to quantify any formatting regression from the pruned examples.

## Amendment — 2026-05-22 evening: few-shot example LEAKAGE (v9 → v10)

Re-testing on the device surfaced a worse failure than language bleeding:
the model emitted the **content of the worked examples** as the user's note —
a note titled "Riunione Jira e fiori per Laura" about Marco / the Atlassian
team / the Jira migration, and a "Deploy … NTR-432" note naming Sarah and
Andrea. None of it was dictated. This is a Pillar 4 (no-hallucination)
violation and the most dangerous defect for the submission.

### Diagnosis

Two compounding facts:

1. **Stale build, not a prompt bug (operational).** Those exact strings exist
   only in v8. Their appearance proved the **device was still running a
   v8-packaged APK**: Gradle's asset-merge task stays cached on an incremental
   build, so a changed `ACTIVE_PROMPT` and a newly added `structure_note_vN.txt`
   do **not** reach the APK while the Kotlin code changes do. Fix: a CLEAN
   build (`./gradlew --stop` then `./gradlew clean :app:installDebug`). Verify
   what is actually on disk with `Select-String … ACTIVE_PROMPT` +
   `Get-ChildItem …/prompts/` before trusting the source.
2. **Root cause (model).** Small INT4 models attend to long, vivid few-shot
   examples over a short transcript placed far away at the end of the prompt,
   and copy one. v8 (10 rich examples) and even v9 (6) were leak-prone because
   the examples carried memorable names, projects, and ticket numbers.

### Decision

`structure_note_v10.txt` (active) supersedes v9, which never reached a device:

- **3 examples instead of 6**, deliberately short and low-salience.
- **Bland placeholder content** — no ticket numbers, no memorable project or
  person names that would be tempting (or damaging) to copy.
- **Anti-copy guard immediately before the transcript**: an explicit block
  stating the examples are format-only, contain no user information, must
  never be copied, and that a short transcript must yield a short note built
  only from the transcript's own words.

See `docs/prompt-evaluations/example-leakage-v10.md`.

### "Auto" language now follows the device locale

The per-language tag filter and language lock only engaged when a language was
**pinned**; in "Auto" they did nothing and short notes still came out mixed.
Because Android's `SpeechRecognizer` has no real language auto-detection (it
transcribes in the device language regardless), "Auto" now resolves to the
device locale — `CaptureViewModel` derives the working language from
`Locale.getDefault()` when no pin is set, and feeds it to both the language
lock and the tag filter. An explicit pick from the selector always overrides.
This makes the default path reliable and the behaviour honest (Auto = "use the
phone's language"); it does not attempt true cross-language auto-detection,
which the ASR cannot support in v1.

### Smaller consequences shipped alongside

- **Junk datetime mentions dropped.** E2B sometimes emitted a placeholder
  mention (empty or literal `"null"` surface form) for notes with no time
  reference, showing a stray "null" chip. These are now filtered in
  `buildStructuredNote`; genuinely vague-but-real phrases are kept.
- **Export timestamps in local time.** `created`/`updated` and mention `iso`
  in the exported front-matter are rendered in the device timezone with offset
  (e.g. `…+02:00`) instead of UTC `Z`, so the exported note matches the time
  the app displays. Instants remain UTC internally.
