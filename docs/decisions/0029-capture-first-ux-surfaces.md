# 29. Capture-first UX surfaces: CPU advisory, editable review, quick entries, vault export

Date: 2026-06-10
Status: Accepted

## Context

With ADR 0023's middle step shipped (structuring off the critical path), the
remaining UX gaps from the 2026-06-10 review discussion were: opacity about WHY
structuring is slow on some hardware, a review pane that allowed editing title
and body but not tags, no rendered Markdown preview before saving, no fast path
from anywhere-on-the-phone to the mic, and no first-class export into an
Obsidian vault — the app's founding use case.

## Decisions

### 1. One-time CPU-fallback advisory

`StructuringResult` gains `cpuFallback: Boolean` (set by
`StructureNoteUseCaseImpl` from `session.backend() == CPU`; UNKNOWN is not
reported — no engine, nothing to advise). The capture VM emits a one-time
`CpuFallbackAdvisory` snackbar the first time a result reports CPU.
**Process-scoped, not persisted** (companion latch): repeating it every note
would nag, persisting it would hide a later GPU-driver fix after a LiteRT-LM
bump. Closes the ADR 0016 UX follow-up.

### 2. Review pane: editable tags + Markdown preview

Tags render as removable chips (✕) in a `FlowRow` plus an "Add tag" field;
normalization and dedupe go through `Tag.normalize` in the VM (`AddTag` /
`RemoveTag` intents), so review-added tags obey the same rules as model tags.
The body gets an Edit/Preview toggle backed by the existing render-only
`MarkdownText` (Markwon); editing stays plain-text by design.

### 3. Quick-capture entries: launcher shortcut + Quick Settings tile

A static shortcut (`res/xml/shortcuts.xml`) and a `CaptureTileService` QS tile
both launch `MainActivity` — the home destination IS capture (ADR 0001), so no
new navigation surface is needed. Privacy-neutral: no logic, no data, the
biometric gate (ADR 0013) still applies. The tile uses the PendingIntent
overload of `startActivityAndCollapse` on API 34+ (the Intent overload throws
there) with the deprecated path kept for minSdk 28.

### 4. Folder (Obsidian vault) export

The notes screen gains "export all to a folder" (SAF `OpenDocumentTree` +
DocumentFile): every note is written as the same frontmattered `.md` the ZIP
export produces (`Note.toMarkdownWithFrontmatter`), directly into the user's
vault. Selection-scoped when a selection is active, whole collection
otherwise. Filenames are deterministic (`YYYY-MM-DD_title_id6.md`, extracted
to `exportFilenameFor` and shared with the ZIP path) and the route
deletes-then-creates, so re-exports UPDATE the vault instead of accumulating
copies. The VM keeps a `writeFile: (name, content) -> Boolean` seam so it
stays free of ContentResolver/DocumentFile and unit-testable.
New dependency: `androidx.documentfile:documentfile:1.0.1` — pure local file
I/O, no network surface; the no-INTERNET CI gate is unaffected.

## Alternatives considered

- **Persisting the CPU advisory flag in DataStore** — rejected, see §1.
- **Rich Markdown editor** instead of the Edit/Preview toggle — rejected:
  Markwon is render-side by design; an editable rich view is a project of its
  own and the raw-Markdown audience (Obsidian users) tolerates source editing.
- **Auto-start recording from the shortcut/tile** — deferred: it needs the
  permission flow and the Preparing warm-up to be entered headlessly; v1 lands
  on the capture screen one tap away. Revisit with real usage.
- **Continuous vault sync** (write-through on every save) — deferred: needs a
  persisted tree-URI permission, conflict policy, and a settings surface.
  One-tap re-export covers the daily Obsidian workflow at a fraction of the
  complexity. Natural follow-up if the export sees real use.

## Consequences

- Slow hardware is now explained once, honestly, instead of silently degraded.
- The review pane can fix the model's tag choices on the spot — combined with
  EXISTING_TAGS (ADR 0012), user corrections feed the corpus that steers the
  next note's tags.
- Time-to-mic drops to one gesture from anywhere (QS tile) without touching
  the privacy posture.
- The Obsidian loop the project was started for is closed: dictate → structure
  on-device → one tap → vault.
