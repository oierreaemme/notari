# v12 — prefill slim (5.5 KB → ~2.9 KB)

Date: 2026-06-10
Status: **FAILED on-device eval — ROLLED BACK to v11 same day** (see Results below)

## Why

On the CPU fallback path the static-prompt prefill dominates structuring latency:
~46 s of the Pass 1 baseline (ADR 0016 amendment), re-paid on EVERY Conversation
because LiteRT-LM has no KV-cache reuse across calls. Prefill cost is linear in
prompt size, so halving the prompt should cut ~20-25 s per pass on the Pixel —
the single biggest speed lever available without runtime changes.

(Note: if the `uses-native-library` manifest fix lands the GPU path, this matters
less — GPU prefill is cheap — but it still helps cold capture and any device that
genuinely lacks OpenCL.)

## What changed vs v11

Pure de-duplication, no behavioral retreat intended:

- Every rule was stated 2-3 times in v11 (rules block, "Body — REQUIRED" block,
  FINAL CHECKLIST). v12 states each rule once, in one consolidated Rules list.
- Examples A + C merged into one (two topics + event-prose-first + checkbox +
  resolved mention in a single Italian example); Example B (English, checkboxes,
  bold entity, empty mentions) kept. 3 examples → 2.
- Schema moved up top, single line.
- Kept verbatim-in-spirit: anti-invention clause, meta-speech preservation,
  orthographic cleanup ("fix typos, keep unclear fragments"), language lock +
  "never borrow the examples' language", checkbox-vs-event-vs-enumeration
  distinction with the full multilingual commitment-marker list, `##` topic rule
  with prose-first, datetime-only mentions + shortest-span + future resolution +
  junk-mention ban, 2-4 tags + EXISTING TAGS reuse + same-language constraint,
  no `# Title` in body, example-leakage CRITICAL block (condensed), triple-quote
  transcript delimiters, all four template markers.

## Risk

A 2B-effective model leans on repetition; the rules were repeated *because*
repetition worked. The slim version may regress exactly the behaviors the
repetition was protecting — checkbox recall and example leakage are the two to
watch first (see `few-shot-en-checkbox-v11.md` and `example-leakage-v10.md`).

## On-device checklist (Pixel, CLEAN build: `./gradlew clean :app:installDebug`)

Re-run the standard probes and compare against the v11 notes:

1. IT short note with "devo ricordarmi di..." mid-prose → checkbox present?
2. EN note, language pinned IT off → no Italian bleed in title/tags?
3. Short note (< 10 words) → output stays short, nothing from the examples?
4. Note with no time reference → `mentions: []`, no "null" chip?
5. "domani alle quindici" → resolved to tomorrow (future-biased), offset form?
6. Tag reuse: dictate on an existing topic → reuses the existing tag verbatim?
7. **Latency**: time Pass 1 on a ~300-char note; expect roughly half the v11
   prefill share on CPU (logcat `VoiceNoteGemma`).

## Rollback

`AssetPromptLoader.ACTIVE_PROMPT = "structure_note_v11.txt"` + **clean build**
(incremental builds keep the old asset — the known prompt-cache trap).

---

## Results — 2026-06-10, on-device (Pixel, Backend.GPU, exported notes reviewed)

| Probe | Esito | Dettaglio |
|-------|-------|-----------|
| P1 checkbox in prosa | **FAIL** | "Devo ricordarmi di mandare il riepilogo a Luca" rimasto in PROSA. Mention "entro venerdì" assente (probabile concausa ASR: whisper ha reso "il riapilo con Luca"). |
| P2 nota corta / leakage | PASS | Corta, una checkbox, `mentions: []`, zero leakage dagli esempi. Tag semanticamente pigri (`idea, lavoro` per le pile). |
| P3 EN pinnato | PARZIALE | Lingua EN ovunque ✓, checkbox ✓ — ma mention "on Monday morning" NON estratta. |
| P4 nessun datetime | PASS | `mentions: []`, prosa + checkbox legittima. Nit: checkbox "Devo farlo più spesso" conserva il "Devo". |
| P5 domani alle 15 | PASS | `2026-06-11T15:00+02:00`, futuro, offset ✓. Nit: contenuto duplicato (stessa frase in prosa E in checkbox). |
| P6 riuso tag | **FAIL** | "devo sistemare la descrizione" in PROSA (secondo fail checkbox). "Per Notari"→"Per notare" è ASR. |
| P7 due topic | **FAIL** | Nessun `##`; "Primo…/Secondo…" come prosa piatta e mutilata; mention "sabato" assente. Checkbox ok (3/3). |
| P8 enumerazione | **FAIL** | Checkbox invece di bullet `- `, più una "Fare la spesa" extra derivata dall'incipit. |
| P9 meta-speech | PARZIALE | "Non ricordo se il 20 o il 21" preservato ✓, nessuna data inventata ✓ — ma "Devo controllare la mail di Anna" in PROSA (terzo fail checkbox). |
| P10 nota lunga | n/a | Dettata una favola (fuori script): prosa corretta ma monolitica, nessuna spezzatura in paragrafi. Trascrizione ASR rumorosa. |

**Pattern della regressione:** la checkbox viene emessa solo quando l'intera
nota È il commitment (P2, P3, P5); quando il "devo" è incastonato in prosa con
altro contenuto, resta prosa (P1, P6, P9). È esattamente il failure mode che
v4 corresse con il framing "REQUIRED — every time" e che la FINAL CHECKLIST di
v11 teneva inchiodato: per un modello 2B-effective la ripetizione è il
meccanismo, non ridondanza. Idem enumerazioni (P8) e headings (P7), entrambe
regole che in v11 comparivano due volte.

**Decisione:** rollback a v11 (criterio pre-concordato: P1-P5 tutti
obbligatori → P1 fail). Con la GPU attiva (ADR 0030) il costo del prefill non
è più il collo di bottiglia sul device di riferimento, quindi qualità > size.

**Note per una futura v13** (solo se servirà di nuovo il prefill corto, es.
device CPU-only):
- ripartire da v12 + reintrodurre SOLO la FINAL CHECKLIST (3 righe) e la
  doppia menzione di enumerazioni/headings;
- valutare la regola "checkbox text all'infinito, senza 'Devo'" (nit P4) e
  "non duplicare la stessa frase in prosa + checkbox" (nit P5) — valgono
  anche per v11;
- osservazione trasversale (anche v11): EXISTING_TAGS spinge il riuso anche
  quando semanticamente non c'entra ("lavoro" sulle pile del telecomando) —
  candidata una clausola "reuse ONLY if topically pertinent".
- P10: su prosa narrativa lunga valutare una regola "spezza in paragrafi
  ogni 3-4 frasi".
