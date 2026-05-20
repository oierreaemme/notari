# DEV post — Section 6: What's Next

> Section 6 per CLAUDE.md §12: roadmap teaser, "1–2 paragraphs". Restrained
> on purpose — the goal is to signal direction without overpromising, and
> to leave readers curious enough to follow the repo. Anything I list here
> needs to be defensible (i.e., the architecture already supports it or
> the gap is well-characterized), not invented for the post.

---

## What's Next

The most consequential next step is **replacing Android's
`SpeechRecognizer` with Gemma 4's audio-native input**. Today the
pipeline is voice → SpeechRecognizer (text) → Gemma 4 (text) →
structured note; tomorrow it can collapse to voice → Gemma 4
(multimodal) → structured note in a single forward pass. The model
weights are already designed for that — Gemma 4 E2B and E4B are
natively multimodal — but public LiteRT-LM inference of the audio
variant on Android is not yet shipped. Once it is, the rewrite is
local to `:core:asr`: `SpeechToTextSession` becomes a thin wrapper
around the same `GemmaSession` that already handles structuring, the
intermediate text transcript can be exposed as a side product for
display only, and the multilingual story tightens because the model
detects language at the audio level instead of relying on the system
recognizer's hint. Everything from `:feature:capture` upward stays the
same.

Two smaller items round out the v2 roadmap. **Function calling** on
edge Gemma 4 — verified by other Build-track submissions, deferred for
v1 per ADR 0005 because the runtime exposure is inconsistent — would
let the structuring step return a typed JSON via constrained sampling
rather than prompt-engineered string output, removing most of the
sanitization layer and unlocking calendar integration ("Add this
datetime mention to your calendar") without leaving the device. And
**a small bug in relative date resolution** surfaced during real-device
testing: a meeting transcribed without an explicit date but with a
specific time anchored to a past date instead of the next future
occurrence. The resolver in `core/inference/.../normalize/
RelativeDateTimeResolver.kt` needs a "future bias" rule for ambiguous
time-only references — small change, real-world impact. Both items
are in the public issue tracker; both are tractable; neither blocks
the v1 promise of *"speak, get a clean Markdown note, audio never
leaves your phone."*
