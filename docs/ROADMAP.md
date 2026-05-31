# Notari — Roadmap

Post-submission roadmap derived from a senior code review (2026-05-31). It turns
that review into concrete, prioritised work across three axes — **speed**,
**features**, **markdown** — plus the one product blocker that gates real-world
adoption.

## Guiding constraint

Every item below MUST preserve the cardinal rules (CLAUDE.md §1–3): **zero
network / no `INTERNET` permission, zero audio persistence, deterministic
output.** Any item that would touch those is flagged and gated on an ADR.

## Priority at a glance

Ranked by leverage (impact ÷ effort), not by axis.

| # | Item | Axis | Impact | Effort | ADR |
|---|------|------|--------|--------|-----|
| **R1** | Async / background structuring | Speed (perceived) | High | M | [0023](decisions/0023-async-background-structuring.md) |
| **R2** | Actionable datetime mentions (calendar/reminders) | Features | High | S→M | [0024](decisions/0024-actionable-datetime-mentions.md) |
| **R3** | Assisted model delivery (no manual SAF) | Product blocker | High | L | [0022](decisions/0022-model-delivery-saf-now-pad-later.md) |
| **R4** | Inference latency: prompt prefill + smaller-model default | Speed (real) | Med–High | M | — |
| **R5** | FTS5 full-text search | Features | Med | M | ADR needed (schema) |
| **R6** | Markdown editing: toolbar + live preview | Markdown | Med | M | — |
| **R7** | Markdown formatter: richer deterministic structure | Markdown | Med | S–M | — |
| **R8** | VAD silence-trim before whisper | Speed (real) | Med | S | — |
| **R9** | Quick capture (widget / QS tile / Assistant) | Features | Med | M | — |

---

## R1 — Async / background structuring  ⟶ ADR 0023

**Why:** structuring blocks the user for 15 s (warm GPU) to 250 s (CPU cap);
it's the worst UX property and the dominant cause of timeout fallbacks.

**Issues**
- **R1.1** Persist the transcript as a plain note (`structured=false`) the moment
  whisper returns; release the user from the capture screen.
- **R1.2** Run Gemma in a background job that survives navigation (WorkManager
  `CoroutineWorker` or short `dataSync` FGS — *not* the mic FGS); update the
  note in place on success via the existing merge in `NoteDetailViewModel`.
- **R1.3** Conflict rule: apply the structured result only if the note body is
  unchanged since capture; otherwise discard (user already curated it).
- **R1.4** Completion signal: live Room-flow update + low-priority notification
  when backgrounded.
- **R1.5** (Increment-first) Ship the "wait 8 s, then background" middle step
  before the full async flow.

**Acceptance:** dictation returns the user to a usable note in < 2 s on any
device; structuring never blocks navigation; no regression in the privacy tests.

## R2 — Actionable datetime mentions  ⟶ ADR 0024

**Why:** the resolved `Instant` on each mention is the app's best data and is
currently inert (display + YAML only). Verified: no `AlarmManager` /
`CalendarContract` / `WorkManager` anywhere.

**Issues**
- **R2.1** "Add to calendar" on a resolved chip via `ACTION_INSERT` — zero
  permission, zero network. Ship first.
- **R2.2** Optional in-app local reminder (notification); prefer inexact alarms
  to avoid `SCHEDULE_EXACT_ALARM`; rebuild on `BOOT_COMPLETED`, cancel on note
  delete/edit.
- **R2.3** "Upcoming" agenda view (notes with a future mention, sorted by time).

**Acceptance:** from a note, one tap creates a calendar event prefilled with
title + resolved time; privacy copy states the calendar hand-off explicitly.

## R3 — Assisted model delivery  ⟶ implements ADR 0022 (PAD path)

**Why:** the existential adoption blocker. Manual SAF import of a >1 GB Gemma
file + a whisper `ggml` is where non-technical users abandon. ADR 0022 already
chose Play Asset Delivery as the scale path.

**Issues**
- **R3.1** Whisper ggml (freely redistributable): bundle via PAD / on-demand
  asset with an in-app progress UI — no `INTERNET` in the app.
- **R3.2** Gemma (Google-gated terms): keep SAF but wrap it in a guided,
  progress-tracked onboarding; investigate a PAD-hosted path compatible with the
  Gemma terms.
- **R3.3** First-run setup wizard that detects missing models and walks delivery.

**Acceptance:** a fresh install reaches a working transcription without the user
ever opening a file picker manually.

## R4 — Inference latency (real)

**Why:** `StructureNoteUseCaseImpl` documents the ~46 s CPU baseline as the
~8 KB static prompt re-prefilled every `Conversation` with no KV-cache reuse.

**Issues**
- **R4.1** Audit + shrink the static prompt; measure prefill cost per backend.
- **R4.2** Track LiteRT-LM prefix/KV caching; adopt when available — collapses
  the baseline.
- **R4.3** Quality/Speed setting: default whisper `tiny`/`base` and a smaller
  Gemma quant on low-end devices.
- **R4.4** Investigate GPU-init reliability (the CPU fallback is the perf cliff;
  `LiteRtLmGemmaSession` already logs the failing OEM cases).

## R5 — FTS5 full-text search

**Why:** `NotesViewModel` filters in memory (`title.contains || body.contains`);
won't scale. SQLite FTS5 works under SQLCipher. *Touches the encrypted schema →
ADR recommended.*

**Issues**
- **R5.1** FTS5 virtual table mirroring note title/body, kept in sync by trigger
  or DAO; migration under SQLCipher.
- **R5.2** Repository/DAO search query + ranked results; debounced as today.

## R6 — Markdown editing UX

**Why:** editing is a raw `OutlinedTextField` of markdown — no toolbar, no
preview (verified). High friction for the formatting the app is built around.

**Issues**
- **R6.1** Markdown toolbar (bold / italic / bullet / checkbox / heading) acting
  on the text selection.
- **R6.2** Live preview toggle or split edit/preview using the existing
  `MarkdownText` (Markwon) renderer.

## R7 — Markdown formatter: richer structure

**Why:** `MarkdownBodyFormatter` is whitespace-only (subtractive). It can impose
more structure deterministically (ADR 0015 philosophy).

**Issues**
- **R7.1** Nested lists, blockquotes, ordered-step normalisation.
- **R7.2** Let the prompt authorise more structure types (numbered steps,
  callouts) while keeping the deterministic formatter as the guarantor.

## R8 — VAD silence-trim before whisper

**Why:** fewer samples = less transcription time and battery. Trim leading/
trailing/inter-utterance silence from the in-RAM PCM before `transcribe()`.
Privacy unchanged (still RAM-only, still zeroed).

## R9 — Quick capture surfaces

**Why:** lower the cost of starting a capture. Home-screen widget, Quick
Settings tile, Assistant/`ACTION_ASSIST` entry → straight into recording.

---

## Sequencing recommendation

1. **R1.5 → R1** and **R2.1** in parallel — both are high-impact and independent;
   together they change the app from "impressive demo" to "usable daily".
2. **R3** next — without it the above only benefits the author + enthusiasts.
3. Then **R4 / R5 / R6** as quality-of-life depth.

Effort key: **S** ≈ days · **M** ≈ 1–2 weeks · **L** ≈ multi-week.
