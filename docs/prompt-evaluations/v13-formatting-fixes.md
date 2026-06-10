# v13 — formatting fixes (base: v11 integrale, niente slim)

Date: 2026-06-10
Status: **PENDING on-device eval** (attivo in `AssetPromptLoader.ACTIVE_PROMPT`)

## Origine

Secondo round on-device (post-rollback a v11, GPU attiva): i checkbox sono
tornati, ma tre difetti restavano — verificati su note reali esportate:

1. **Frasi perse**: "il nuovo layout mi convince…" sparito in DUE take
   consecutive della stessa dettatura (il titolo lo citava, il body no).
2. **Eco del titolo** come prima riga del body ("Lista della spesa.").
3. **Mention deboli**: "entro venerdì" mai estratto (concausa ASR).

Più due scelte di stile confermate dall'utente: liste di acquisti come
**checkbox** (spuntabili facendo la spesa — il modello già lo faceva, ora la
regola lo dice) e testo checkbox **all'infinito** senza il "Devo" iniziale.

## Cosa cambia (v11 → v13) — solo aggiunte chirurgiche, ripetizione intatta

- **COMPLETENESS rule** (Rules + Body + FINAL CHECKLIST): ogni frase del
  transcript sopravvive; solo i filler puri si eliminano; "dropping a
  sentence is as wrong as inventing one". Example A ora include un inciso
  che SOPRAVVIVE nella nota.
- **Liste buy/do = checkbox** (one per item, niente checkbox-ombrello "Fare
  la spesa"); enumerazioni informative restano bullet.
- **Checkbox text verb-first** (niente "Devo/I need to" nel testo).
- **No duplicazione** prosa+checkbox della stessa frase.
- **Tag pertinenti**: riuso EXISTING_TAGS solo se il tema corrisponde
  davvero (le pile del telecomando non ereditano "lavoro").
- **No eco del titolo** come prima riga del body.
- **Paragrafo ogni 3-4 frasi** sulla prosa lunga (lezione della nota
  "Volpi": muro di testo monolitico).

## Companion deterministici in codice (stesso commit)

- `DeterministicMentionScanner` — backstop: se il modello emette ZERO
  mentions, il transcript viene scansionato per riferimenti futuri non
  ambigui (frasi delle tabelle del resolver + orari "alle 15"/"at 9:30",
  merge "domani alle 15"). Mai inventa: surface form = substring letterale;
  i giorni con modificatore passato ("venerdì scorso", "last friday") e la
  narrazione ("oggi", "ieri") sono esclusi. Si attiva SOLO a mentions vuote.
- `stripDuplicateTitleHeading` esteso: rimuove anche l'eco *in prosa* del
  titolo come prima riga (prima gestiva solo `# Heading`).
- Test: `DeterministicMentionScannerTest` (9 casi) + 3 test nuovi in
  `StructureNoteUseCaseImplTest` (backstop attivo, backstop silenzioso su
  narrazione, eco titolo rimosso).

## Re-test on-device (clean build!)

Riusare `v12-test-script.md` con attenzione a:
- P1: la frase sul layout DEVE sopravvivere nel body + checkbox presente +
  mention "venerdì" presente (dal modello o dal backstop).
- P8: checkbox per gli articoli ✓ (ora è il comportamento richiesto), MA
  niente riga "Lista della spesa." nel body e niente checkbox-ombrello.
- P9: checkbox "Controllare la mail di Anna" senza "Devo".
- P5: niente duplicazione prosa+checkbox della stessa frase.
- P10 (o nota lunga): paragrafi ogni 3-4 frasi.

Rollback: `ACTIVE_PROMPT = "structure_note_v11.txt"` + clean build (i
companion in codice restano validi con qualunque prompt).
