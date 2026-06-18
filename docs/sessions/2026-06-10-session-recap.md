# Session recap — 2026-06-10

La sessione più densa del progetto: review completa sicurezza/performance,
quattordici fix, l'implementazione dell'ADR 0023, cinque superfici UX nuove e
— a fine giornata — lo sblocco della GPU che ribalta tre settimane di
diagnosi. Quattro ADR nuovi (0027-0030), tre esistenti aggiornati (0016,
0019→n/a, 0023 → Accepted).

## 1. Review sicurezza & performance (mattina)

Report completo in `docs/reviews/2026-06-10-security-performance-review.md`
(15 rilievi, tutti verificati sul codice). Implementati in giornata:

- **ADR 0027 — ownership dei buffer audio.** Il rilievo più grave: race
  cancel→restart in cui lo `stop()` ritardato (con whisper in corsa
  sull'audio scartato) azzerava i buffer della registrazione successiva.
  Fix: `discard()` senza trascrizione, snapshot-swap atomico sotto lock,
  gate `accepting`, zeroing su ogni percorso di uscita (incluse la copia
  float di whisper e la morte del VM via scope NonCancellable), teardown
  fuori dal main thread su riferimenti locali di sessione.
- **ADR 0028 — lifecycle e hardening.** Rilascio engine via
  ProcessLifecycleOwner + idle timer 5 min (TRIM_MEMORY_COMPLETE è no-op su
  API 34+); FGS pilotato dal VM tramite `RecordingKeepAlive` (non più dalla
  composizione); cap registrazione 15 min con auto-stop; corpus tag via
  query join dedicata invece di tutte le note in RAM; log della risposta
  modello gated anche in debug; keep rules ProGuard ridotte.
- Altri fix puntuali: guard `release()` vs generazione nativa in volo,
  FLAG_SECURE legato al lock biometrico, transcript sostituito per ultimo
  nel template del prompt (marker injection), advisory nota lunga passato
  dalla lunghezza transcript (morta in batch mode) alla durata catturata.

## 2. ADR 0023 implementato + superfici UX (pomeriggio)

- **ADR 0023 → Accepted** (variante "8 s poi background"): la cattura non
  blocca mai più di 8 s; oltre, nota salvata subito come testo e upgrade in
  place quando Gemma finisce, con regola concurrent-edit (la versione
  dell'utente vince sempre). Bottone "Save as text now" come via di fuga
  esplicita. Eccezione append-mode documentata.
- **ADR 0029 — superfici capture-first:** avviso CPU una-tantum-per-processo
  (`StructuringResult.cpuFallback`), review pane con tag editabili e toggle
  anteprima Markdown, shortcut launcher + Quick Settings tile, export su
  cartella SAF (vault Obsidian) con nomi file deterministici e
  delete-then-create per il re-export. Chiude il cerchio del caso d'uso
  fondante (dettato → strutturato on-device → vault).

## 3. Le leve di velocità (sera) — e la scoperta

- **ADR 0030 — GPU sbloccata.** Il "bug GPU-init di LiteRT-LM" su cui erano
  tarati i budget CPU (250 s max) era in realtà la dichiarazione
  `<uses-native-library libOpenCL.so>` mancante nel manifest: su API 31+ il
  loader rifiuta le librerie vendor non dichiarate. Due righe di manifest →
  `Engine initialized on Backend.GPU` sul Pixel di riferimento, structuring
  da 60-90 s a pochi secondi. I budget CPU e l'advisory restano per i
  dispositivi senza OpenCL reale. Lezione registrata nell'ADR: prima di
  attribuire un init-failure nativo a un bug upstream, verificare i
  requisiti di packaging.
- **Prompt v12** (`docs/prompt-evaluations/v12-slim-prefill.md`): 5.5 KB →
  ~2.9 KB di pura de-duplicazione (ogni regola era ripetuta 2-3 volte; 3
  esempi → 2). Su CPU vale ~20-25 s a passata di prefill. PENDING eval
  on-device: script di dettatura con 10 probe in
  `docs/prompt-evaluations/v12-test-script.md`, criterio pass/rollback
  concordato in anticipo (P1-P5 obbligatori).

## 4. Round 2 on-device (sera tardi): gate overlay, v12 bocciata, v13

- **Eval v12 fallita** (10 probe dettati, tabella in `v12-slim-prefill.md`):
  checkbox recall regredito su 3 probe a prosa mista, enumerazioni come
  checkbox, headings persi, mention EN persa → rollback a v11 secondo il
  criterio pre-concordato. Lezione: per un modello 2B la ripetizione delle
  regole È il meccanismo.
- **Due bug utente con lock biometrico attivo, stessa root cause**: il gate
  sostituiva il NavHost in composizione su ON_STOP, distruggendo NavController
  e ViewModel → (a) schermo spento mid-dettatura = nota persa; (b) picker SAF
  dell'export ZIP = callback distrutto = zip vuoto. Fix: NavHost sempre
  composto, `LockedGate` come overlay opaco che consuma i pointer (update in
  ADR 0013). FLAG_SECURE copre i Recents.
- **Round v11**: checkbox tornati, ma frasi perse ("layout…" sparito in due
  take), eco del titolo nel body, mention "venerdì" mai estratta →
  **prompt v13** (v11 + regole chirurgiche: completezza, liste buy/do come
  checkbox per scelta utente, verb-first, tag pertinenti, niente eco titolo)
  + **DeterministicMentionScanner** (backstop mentions dal transcript, mai
  inventa) + strip dell'eco titolo in post-processing.
  Doc: `v13-formatting-fixes.md`. PENDING eval con lo stesso probe script.
- Conferme on-device: GPU attiva (`Engine initialized on Backend.GPU`),
  structuring in secondi, log solo-lunghezza. ASR (whisper small-q5_1) è ora
  il collo di bottiglia della QUALITÀ: "riapilo", "pighe", "Intro venerdì".

## 5. Round 3-5: la pipeline deterministica di formattazione

Cinque round di eval on-device nella stessa giornata (cronaca completa in
`docs/prompt-evaluations/v13-eval-round3-and-v14.md`) hanno prodotto la
lezione architetturale della sessione: **per un modello 2B-effective, le
regole di formato si chiedono nel prompt ma si GARANTISCONO in codice**
(estensione naturale dell'ADR 0015).

Cronologia:

- **v13** (round 3): regola di completezza (frasi non più perse ✓), liste
  buy/do come checkbox (scelta utente), checkbox verb-first, tag pertinenti.
  Risultato: completezza risolta, ma il modello ora DUPLICAVA gli impegni
  (prosa + checkbox) — le due regole erano in tensione.
- **v14** (round 4): sopravvivenza esclusiva ribadita tre volte + esempio.
  Risultato: prime 2 note perfette di sempre (Anna, lista della spesa), ma
  duplicazione ancora in 4 note su 6 → verdetto: omettere-mentre-emetti è
  pianificazione globale fuori dalla portata affidabile del 2B.
- **Risposta in codice** (round 5): `CommitmentDeduplicator` — rimozione
  della copia in prosa con matching token-based tollerante al garbling ASR
  (prefisso ≥4), e PROMOZIONE del testo checkbox alla versione completa
  quando la prosa portava informazione extra. Mai perdita di contenuto.
  Risultato round 5: duplicazione eliminata 3/3.

Companion deterministici nati negli stessi round:

- `DeterministicMentionScanner`: backstop mention dal transcript (frasi
  future delle tabelle resolver + orari "alle 15", merge "domani alle 15");
  attivo quando il modello non emette mention, sulla surface delle mention
  non risolte (solo a copertura totale), e sui fallback plain-text.
- Normalizzazione **NFC** in scanner e resolver (whisper può emettere accenti
  decomposti — il "venerdì" sparito del round 4).
- Filtro junk esteso: surface senza lettere ("2") mai un chip.
- `MarkdownBodyFormatter`: riparazione bold spaiato (riga non bilanciabile →
  de-bold, le parole restano).
- Strip dell'eco del titolo esteso alla prima riga in prosa.

Residuo a fine giornata: checkbox-recall variance su singole note (proposta
a doc: un *promotore* deterministico simmetrico al deduplicatore — "devo X"
in prosa senza checkbox che lo copra → promosso a checkbox; alternativa di
lungo periodo il constrained decoding), garbling whisper (candidato
`medium-q5_0`), tag/titoli occasionalmente ballerini (varianza 2B), e un
fallimento JSON doppio sul multi-topic da diagnosticare con la debug card.

Fix trasversale dello stesso pomeriggio: il **gate biometrico è diventato un
overlay** (ADR 0013 update) — sostituendo il NavHost distruggeva i ViewModel
su ON_STOP: nota persa a schermo spento mid-dettatura e zip dell'export
vuoto dopo il picker SAF. Ora la composizione sopravvive al lock.

## Stato a fine sessione

- On-device confermato: GPU attiva, note 111/240/325 chars strutturate in
  pochi secondi, log solo-lunghezza (gate privacy attivo), FGS e snackbar
  background corretti.
- **Aperto:** esecuzione dei 10 probe v12 (Mario); poi marcare l'eval doc.
- **Decisioni rimandate con criterio:** full-async ADR 0023 (dopo uso
  reale del middle step), WorkManager per l'upgrade in background (solo se
  la process-death si rivela frequente), bump litertlm 0.11→0.13 (nessun
  fix rilevante documentato, rischio API non giustificato), sync continua
  del vault (dopo uso reale dell'export one-shot).

## File di riferimento

ADR: 0027, 0028, 0029, 0030 · 0016 (amendment 2026-06-10) · 0023 (Accepted)
Review: `docs/reviews/2026-06-10-security-performance-review.md`
Prompt: `v12-slim-prefill.md`, `v12-test-script.md`
Dipendenze nuove (tutte offline): lifecycle-process, documentfile
