# 0022. Model delivery: keep SAF now, Play Asset Delivery when distribution scales

- **Status:** Proposed
- **Date:** 2026-05-29

## Context

Notari ships **two** on-device model assets, and how they reach a user's
device is still an open question that ADR 0008 (Gemma via SAF) and ADR 0018
(whisper.cpp added as a second asset) each touched but neither settled for a
non-technical audience:

1. **Gemma 4 E2B** — `gemma-4-E2B-it.litertlm`, **> 1 GB**. Weights are
   **gated by Google's Gemma terms of use**: the user accepts the terms on
   Google AI before downloading. ADR 0008 read this as "we cannot host a
   public, no-auth mirror without violating those terms" and chose SAF
   import for exactly that reason.
2. **whisper.cpp** — e.g. `ggml-small-q5_1.bin`, **~180 MB**. The ggml
   models are **freely redistributable** (MIT-licensed project, open model
   files). No licensing constraint on bundling or hosting them.

Today both arrive via the **Storage Access Framework**: the user downloads
the files manually and imports them through a file picker (Settings →
On-device model for Gemma; `adb push` into `whisper/` for the ASR model).
This works and is provably zero-network, but for anyone who is not the
author it is a high-friction, power-user flow — realistically most
non-technical users abandon at "download 1.2 GB of files and import them by
hand".

The hard constraint that shapes every option is the product's strongest
privacy claim, enforced by CI: **the app holds no `INTERNET` permission at
all** (ADR 0007, the no-network cardinal rule, CLAUDE.md §1). Any delivery
mechanism that makes *the app itself* fetch bytes over the network would
require adding `INTERNET`, which would both break the CI gate and gut the
headline "this app cannot phone home — it doesn't even have the permission".

Distribution channel is **not yet decided**: near-term the app is shared via
GitHub (author + technical early adopters); Google Play is a maybe, pending
the author's appetite for the Play Console process. The delivery decision
depends on that channel choice, which is why this ADR is **Proposed**, not
Accepted — it documents the trade-offs so the channel decision can be made
with eyes open.

## Decision (proposed)

**Keep SAF as the baseline, and adopt Play Asset Delivery (PAD) for the
zero-friction path if and when the app is published on Google Play. Never
make the app download models itself.** Treat the two assets separately,
because their licences differ.

1. **Now (GitHub / technical users): keep SAF** (ADR 0008 + 0018 stand). It
   is zero-network, requires no extra permission, and is honest. Improve the
   *onboarding copy* (clear file names, direct links to where each model is
   obtained, validation + clear errors on import) rather than the delivery
   mechanism. This is cheap and helps the current audience.
2. **When publishing on Play: Play Asset Delivery.** PAD is the key insight
   here — **Play, not the app, downloads the assets**, so the app keeps its
   no-`INTERNET`-permission posture *and* the user gets the models with zero
   manual steps. PAD handles multi-GB asset packs.
3. **Per-asset licence split:**
   - **whisper** (free) — safe to ship via PAD, or even bundle, with no
     licensing question.
   - **Gemma** (gated) — redistributing the weights (in an APK, an asset
     pack, or anywhere) means propagating Google's Gemma use-restrictions
     and is a **licence question to verify before shipping**, not a settled
     fact. If redistribution is not acceptable, Gemma stays on SAF even on
     Play while whisper moves to PAD — a hybrid is allowed.

## Alternatives considered

- **(A) SAF manual import for both (status quo).** Zero network, zero extra
  permission, fully honest privacy story. *Rejected as the long-term answer
  for a general audience* — the friction is prohibitive for non-technical
  users — but **retained as the baseline and the dev/dogfooding path**, and
  as the only clearly-licence-clean route for Gemma until redistribution is
  confirmed.
- **(B) Bundle the models inside the APK.** Zero friction, zero network.
  *Rejected*: a > 1 GB APK is hostile to iterate and update (every app
  update re-ships > 1 GB), GitHub release assets cap at 2 GB per file, and
  for Gemma it raises the same redistribution-licence question as PAD with
  none of PAD's delivery benefits.
- **(C) In-app download (DownloadManager / HTTP at first run).** Lowest
  user friction on paper. **Rejected hard**: it requires the `INTERNET`
  permission, which breaks ADR 0007's CI gate and destroys the central
  privacy claim. The distinction between "fetch a public read-only asset"
  and "exfiltrate user data" is real *conceptually*, but the *permission*
  is binary and the marketing/trust value of "no INTERNET permission" is
  worth more than the saved friction. This is the option the no-network
  cardinal rule exists to forbid.
- **(D) Play Asset Delivery (the proposed path).** Zero user friction, and
  crucially **preserves the no-`INTERNET`-permission posture** because the
  Play runtime performs the transfer, not the app. *Cost*: requires
  publishing on Play (Console account, listing, signing), and for Gemma the
  redistribution-licence question must be answered first.

## Consequences and trade-offs

- **The privacy posture is preserved in every accepted option.** SAF and
  PAD both keep the app free of `INTERNET`; only the rejected option (C)
  would have changed that.
- **The decision is gated on the distribution channel**, which is the
  author's to make. Until then, SAF is correct and nothing needs to change
  in code.
- **Play Console is a smaller lift than it looks for this app**: $25 one
  time, and an offline-first app that collects no data files the simplest
  possible Data Safety form. The real work is signing, listing, and (for
  Gemma) the licence check.
- **A hybrid is explicitly allowed**: whisper via PAD, Gemma via SAF, if the
  Gemma redistribution question lands on "don't". This keeps the friction
  reduction for the asset we *can* ship freely without taking on licence
  risk for the one we can't.
- **No auto-update regression**: SAF already has no model auto-update
  (ADR 0008); PAD would actually improve this for whisper (asset packs
  update with the app) without touching the network-permission story.

## Open questions (resolve before flipping to Accepted)

1. **Distribution channel** — GitHub-only, Play, or both? Everything else
   follows from this.
2. **Gemma redistribution licence** — do Google's Gemma terms permit
   shipping the weights inside a Play asset pack (with the use-restrictions
   propagated), or must Gemma remain user-imported via SAF? This is the
   single fact that decides whether Gemma can join whisper on PAD.
3. **Asset-pack sizing on Play** — confirm the > 1 GB Gemma pack fits the
   install-time / fast-follow PAD limits, or whether it must be an
   on-demand pack fetched at first launch.

## Links

- ADR 0007 — Strip transitive network permissions (the no-`INTERNET` gate).
- ADR 0008 — LiteRT-LM runtime + SAF-based model delivery (Gemma).
- ADR 0018 — whisper.cpp batch ASR (the second model asset).
- ADR 0004 — superseded Gemma packaging ADR that first explored
  "bundle vs Play Asset Delivery"; this ADR revisits that framing now that
  there are two assets with different licences.
