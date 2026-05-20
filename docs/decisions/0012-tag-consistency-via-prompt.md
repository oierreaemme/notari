# 12. Tag consistency via existing-tags prompt context

Date: 2026-05-15
Status: Accepted

## Context

Across several dictation sessions on the same topic Gemma 4 E2B drifts into
near-synonym tags for what is, semantically, the same subject. On real corpora
we captured:

- `app-development`, `app`, `dev`, `development` — all for notes about this
  project's own engineering work.
- `lavoro`, `work`, `task`, `tasks` — same idea, different surface form,
  sometimes within the same language.
- `riflessione`, `riflessioni`, `pensieri` — for personal reflection notes
  dictated within a single week.

Tag-based filtering in the notes list is one of the few discovery affordances
in the app: cluttered, near-duplicate tags make the filter chip row unusable
and the user has to mentally remember which spelling they used last time.

The model has no memory of prior notes. Every structuring call is stateless —
it sees the transcript and the prompt, nothing else. So we have to *give* it
the corpus context if we want consistency.

## Decision

Pass the user's current tag corpus (the union of tags already in use across
saved notes) into the structuring prompt as an `EXISTING TAGS` section, with
an explicit instruction to reuse a tag from that list when it fits the new
note's topic.

Mechanics:

1. `PromptTemplate.render(...)` accepts an `existingTags: List<String>`
   parameter (defaulted to empty for tests and the warm-up path).
2. `StaticPromptTemplate` substitutes `{{EXISTING_TAGS}}` with up to 50 tags
   joined by `, `. The cap protects the prompt token budget on power users
   with hundreds of tags — at that scale the user already has well-established
   conventions and the model will mimic the visible sample.
3. `StructureNoteUseCase.invoke(...)` takes `existingTags` and forwards to
   the prompt template on both Pass 1 (base) and Pass 2 (stricter).
4. `CaptureViewModel` maintains a `@Volatile` snapshot of
   `noteRepository.observeAllTags()` (mapped to `String`) and passes it on
   every structuring call. Room re-emits on every change so deletions and
   tag edits are reflected without us reloading manually.
5. `structure_note_v5.txt` adds:
   - A new `EXISTING TAGS (already used across the user's notes): {{EXISTING_TAGS}}`
     line in the context header.
   - A `Tags — REQUIRED reuse rule` block: *"If one of the EXISTING TAGS fits
     the new note's topic, REUSE that exact tag string. Do NOT coin a
     synonym."*
   - A worked Example 6 in which Gemma sees `EXISTING TAGS` containing
     `app-development` and reuses that exact tag for a note about a new app
     idea, rather than emitting `app` or `idea-app`.

## Alternatives considered

**Embed-based tag normalization at the data layer.** After Gemma emits a new
tag, look up its nearest neighbor in the existing-tag corpus via a small
on-device embedding model; if cosine similarity is above a threshold, snap
to the existing tag. Rejected because it adds a second model on the inference
path (latency + RAM), and because the normalization decision is exactly the
kind of judgment Gemma is already trained to make — we just hadn't given it
the inputs.

**Hard-coded synonym table.** Maintain a list of equivalence classes
(`["app", "dev", "app-development"]` → `app-development`). Rejected because
it doesn't scale across users, languages, or domains. The user's vocabulary
is their own.

**Post-hoc batch consolidation.** Periodically scan the corpus and surface
"these 4 tags look similar — merge?" prompts in Settings. We may still build
this for the long tail (cleaning up an existing corpus), but it doesn't solve
the *write-time* problem — the cluttered chip row appears immediately and
the user has to actively go fix it. Roadmap, not v1.

## Consequences

- **Prompt size grows linearly with corpus tag count**, capped at 50 tags.
  Each tag is short (avg ~12 chars + comma + space → ~14 chars), so 50 tags
  add ~700 chars ≈ 200 tokens of prefill. Negligible relative to the
  transcript+examples (~3000 chars baseline).
- **First note has no consistency pressure** — `existingTags` is empty by
  construction. This is correct: the first note gets to set the vocabulary.
- **A power user reaching 50+ tags** sees a sampled subset, not the full set.
  At that scale they have well-established conventions that surface in
  whichever 50 tags happen to land in the prompt — qualitatively this still
  works on our internal corpus.
- **Eval coverage**: Example 6 in `structure_note_v5.txt` is the canonical
  positive case. The prompt-eval suite under
  `core/inference/src/test/resources/prompt-eval/` will gain a fixture pair
  for the reuse case as soon as we have a real-corpus failure we want to
  pin.
- **Rollback** is point-and-shoot: revert `AssetPromptLoader.ACTIVE_PROMPT`
  to `structure_note_v4.txt`. The `existingTags` parameter remains on the
  interface and is ignored by v4's template substitution (no
  `{{EXISTING_TAGS}}` marker → nothing to replace).

## References

- `core/inference/src/main/assets/prompts/structure_note_v5.txt` — new prompt.
- `core/inference/src/main/java/com/voicenotemd/core/inference/prompt/PromptTemplate.kt` —
  template interface + `EXISTING_TAGS_MARKER` substitution.
- `core/inference/src/main/java/com/voicenotemd/core/inference/structure/StructureNoteUseCaseImpl.kt` —
  Pass 1 / Pass 2 call sites that forward `existingTags`.
- `feature/capture/src/main/java/com/voicenotemd/feature/capture/CaptureViewModel.kt` —
  `observeAllTags()` collector and snapshot field.
