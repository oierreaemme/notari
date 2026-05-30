# 20. AudioRecord warm-up grace period — the "Preparazione…" phase

Date: 2026-05-28
Status: Accepted

## Context

On-device timing measurements from the `BatchSession` logs (Pixel 6a,
phone-mic capture, real dictation sessions 2026-05-27) show a stable
two-step delay after `AudioRecord.startRecording()`:

| Marker                       | Typical latency        |
|------------------------------|------------------------|
| First PCM frame (silent)     | ~+130 ms               |
| First non-silent PCM frame   | +845–1082 ms           |

That second window — the ~700–1000 ms between `startRecording()`
returning and the audio path producing usable signal — is the AGC and
DSP pipeline stabilising. Any speech captured during that window comes
out as silence or unintelligible noise: whisper.cpp will dutifully
transcribe it, but the result is "nothing" for the first one or two
words the user said.

The recap from 2026-05-27 names this directly: *"intermittent bug
'first part of dictation missing' comes from speaking inside the
warm-up window."* The bug is not in the engine — whisper, the
foreground service, the BT routing all work correctly. The bug is that
the UI says "Listening…" the instant the user taps the mic, before the
underlying audio path is actually ready.

We need a UI state that honestly says "wait a moment" during the
warm-up, and transitions to the real "Listening…" the moment the path
is ready.

## Decision

Add a `Phase.Preparing` state to `CaptureUiState`, sitting between
`AwaitingPermission` and `Recording` in the capture state machine. The
state is owned by `CaptureViewModel`; the transitions are:

```
Idle → AwaitingPermission → Preparing → Recording → Transcribing → …
                                ↓                       ↓
                              Idle (cancel)         (existing flow)
```

`Preparing` is entered the moment permission is granted and
`SpeechToTextSession.start()` is called. It is exited:

1. **On first non-silent PCM frame** — `BatchSpeechToTextSession`
   exposes a new `audioReady: Flow<Boolean>` that flips to `true` the
   first time the reader thread sees an RMS above
   `NON_SILENT_DB_THRESHOLD`. The ViewModel watches this flow and
   transitions to `Recording` as soon as it sees `true`. This is the
   normal path: the user taps the mic, sees "Preparazione…" briefly,
   and the moment ambient sound (or their first word) reaches the
   stabilised pipeline, the UI switches to "Listening…".

2. **On a 1.5 s safety timeout** — `withTimeoutOrNull(1500ms)` wraps
   the audioReady wait. If the user taps the mic and sits in total
   silence, we don't want them stuck on "Preparazione…" forever; after
   1.5 s the pipeline is empirically warm regardless of whether
   anything has crossed the non-silent threshold, so we transition to
   `Recording` on the timeout. The user's subsequent speech will be
   captured normally.

3. **On user cancel** — tapping the big red button OR the "Discard"
   text affordance during `Preparing` is routed to `cancelRecording()`,
   which tears down the session and returns to `Idle` without
   transcribing. This is the right semantic during the warm-up window:
   we have at most ~1 s of garbage PCM, sending it through whisper
   would produce no useful note.

The visual treatment in `Preparing`:

- Centre copy reads "Preparazione…" (matches the existing Italian
  "Trascrizione…" pane copy).
- A small linear indeterminate `LinearProgressIndicator` below it,
  plus the sub-line "Mic stabilizing — speak in a moment."
- **No** `PulseRings` — showing reactive rings would falsely imply the
  mic is already capturing.
- The big button is red and shows the Stop icon, so it's clear that
  tapping cancels.
- The "Discard" text button is also visible (same affordance pattern
  as `Recording`).

The foreground microphone service (`RecordingForegroundService`) was
extended to consider `Preparing` part of the active capture window —
without it, the OS could in theory throttle the process during the
warm-up, and the notification shade would flicker on/off as the phase
moved Preparing → Recording.

### Interface contract

`SpeechToTextSession.audioReady` is declared on the interface with a
default of `flowOf(true)` — the legacy / non-batch implementations
(`AndroidSpeechToTextSession`, `FallbackSpeechToTextSession`) do not
need the warm-up grace period and signal "ready" immediately, which
makes the ViewModel's Preparing → Recording transition fire on the
next dispatcher tick. Only `BatchSpeechToTextSession` overrides the
property with a real `MutableStateFlow` driven by its reader thread.

In `CaptureViewModel` the collection uses `firstOrNull { it }` rather
than `first { it }` so that a mis-stubbed mock returning an empty
`Flow<Boolean>` cannot raise `NoSuchElementException` and kill the
recording job — empty flow falls through to the timeout, same as a
silent user.

## Consequences

**Positive**

- The "first words missing" bug is closed by surfacing the warm-up
  honestly instead of pretending the mic is alive immediately.
- The user gets explicit guidance ("speak in a moment") in the brief
  window where speaking would harm the result.
- The PulseRings remain a truthful signal: when they animate, the mic
  is capturing real audio.
- The transition is observable as a real test point (we added two
  unit tests in `CaptureViewModelTest`: one verifying the Preparing
  snapshot exists before audioReady fires, one verifying Cancel from
  Preparing returns to Idle without structuring).

**Neutral**

- The Preparing → Recording transition typically takes ~700–1000 ms.
  Users who tap the mic and start talking immediately will see the UI
  flip from "Preparazione…" to "Listening…" within roughly one
  second; this is a small but visible latency we have not been
  exposing before.

**Negative**

- The state machine grows by one phase. ViewModels and screens that
  consume `CaptureUiState.Phase` must now handle Preparing — the
  existing `when` exhaustiveness check in `CaptureScreen` routes it
  to `RecordingPane` via the catch-all `else`, so the cost is a small
  branch inside that pane rather than a new top-level surface.

## Alternatives considered

**Fixed delay (e.g. always wait 1000 ms before transitioning).**
Simpler — no `audioReady` plumbing, just `delay(1000)` after start.
Rejected because the actual warm-up varies (BT SCO paths add a few
hundred extra ms; some Bluetooth handsets need closer to 1.5 s),
and a fixed conservative delay would make every dictation feel
laggier than needed when the path warms quickly. The signal-based
approach with a timeout fallback is empirically tighter.

**No grace period; rely on the user to wait.**
This is the pre-existing behaviour and the source of the bug. The
prompt to "Tap the mic to capture your first thought" gives no
indication that the mic isn't actually live yet; experienced users
learn to pause for a second, new users don't. Rejected on UX grounds.

**Discard the first ~1 s of PCM at the encoder boundary.**
Solves the data problem without a UI change: drop the first
~16 000 samples before handing them to whisper. Rejected because (a)
it punishes users who *do* wait correctly by silently truncating
their note, and (b) it doesn't address the UX issue — the user still
gets no signal that the mic isn't ready.

## Links

- ADR 0018 — Continuous streaming ASR (amended for the whisper pivot);
  this work builds on the `BatchSpeechToTextSession` capture path.
- `BatchSpeechToTextSession.kt` — owns the `audioReady` MutableStateFlow
  and the diagnostic `BatchSession` log lines.
- `CaptureViewModel.kt` — owns the state transition.
- `CaptureRoute.kt` — owns the visual treatment.
- `CaptureViewModelTest.kt` — new tests `start recording lands in
  Preparing first…` and `cancel during preparing returns to Idle…`.
