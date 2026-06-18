# Round 3 (v13 + scanner + strip) — report e correzioni v14

Date: 2026-06-10 (sera) · 10 note ridettate, esportate e revisionate una per una.

## Quadro: cosa si è AGGIUSTATO rispetto al round 2

| Comportamento | Round 2 (v11) | Round 3 (v13+code) |
|---|---|---|
| Completezza (frase "layout") | PERSA in 2 take | **SOPRAVVIVE** ✓ |
| Mention "venerdì" | assente | **2026-06-12** ✓ (backstop) |
| Mention "sabato" / "Monday" | assenti | **risolte e future** ✓ |
| Checkbox verb-first | "Devo controllare…" | **"Controllare la mail di Anna"** ✓ |
| Meta-speech ("non ricordo se…") | ok | ok ✓ |
| Prosa lunga (favola) | muro monolitico | **paragrafi** ✓ |
| Eco titolo lista | "Lista della spesa." | parz. (vedi sotto) |

## Difetti residui, per responsabile

### A. Duplicazione prosa+checkbox (4 note su 10) — COLPA DEL PROMPT v13, corretta in v14
"Devo ricordarmi di mandare il riapilogo a Luca…" compare in prosa E come
checkbox (idem dentista, Anna, lista). Causa: la regola COMPLETENESS di v13
("ogni frase sopravvive") era in tensione con "non duplicare" e il 2B ha
risolto tenendo entrambe. **v14** rende la sopravvivenza esclusiva: ogni
frase sopravvive ESATTAMENTE UNA VOLTA — un impegno sopravvive COME la sua
checkbox, un'enumerazione COME i suoi item, il resto come prosa. Ribadito in
Rules, Body, FINAL CHECKLIST e nella didascalia dell'Example A.

### B. Mention sporche — CORRETTE IN CODICE (deterministico)
- chip junk `"2"` (da "il 2 o 21"): il filtro junk ora scarta ogni surface
  senza lettere — un numero nudo non è mai un riferimento temporale.
- `"domani alle 15"` emessa dal modello ma `iso: null`: ora la surface stessa
  viene risolta dallo scanner (merge giorno+ora), accettata SOLO se la
  scansione copre l'intera surface — "the third Friday of the month" resta
  correttamente null.

### C. Bold spaiato (nota Sarah) — CORRETTO IN CODICE
"…budget review** on **Monday morning**": numero dispari di `**` sulla riga
→ asterischi letterali nel rendering. `MarkdownBodyFormatter` ora de-bolda
interamente la riga non bilanciabile (le parole restano sempre).

### D. Checkbox recall incostante — PARZIALE, v14 ci prova
- "comprare le file per il telecomando" senza "devo" → prosa (round 2 era
  checkbox). v14: una nota che È solo un'azione = checkbox anche senza marker.
- "Per notari devo sistemare…" → prosa (fail anche nel round 2); "Secondo,
  per il sito devo aggiornare le foto e chiedere un preventivo" → 0 checkbox
  su 2 e niente `##`. Il multi-commitment dentro un periodo lungo resta il
  punto debole strutturale del 2B. Se persiste dopo v14, la strada seria è
  il **constrained decoding** (già investigato in
  docs/research/constrained-decoding-investigation.md) o un secondo passo
  deterministico di estrazione "devo X" → checkbox.

### E. ASR (whisper small) — ORA È IL COLLO DI BOTTIGLIA DELLA QUALITÀ
"riapilogo", "richiamiamare", "l'l'appuntamento", "abbeverarsiarsi",
"Rifronione", "file/pighe per il telecomando", "sullo ritmi" (era "README"),
favola piena di garbling. Nessun prompt può ripararli in modo affidabile
(e non deve: "inventing meaning is worse"). Opzioni concrete, in ordine:
1. **pinnare l'italiano** dal chip lingua (anche per le note IT abituali);
2. provare **ggml-medium-q5_0** (~540 MB, più lento ma nettamente più
   accurato — con la GPU che ha liberato secondi, c'è budget);
3. dettare a ~10 cm dal mic, frasi staccate.

### F. Tag "lavoro" ovunque — feedback loop del corpus di test
EXISTING_TAGS ormai è saturo di "lavoro/idea" creati dai test stessi, e il
riuso si auto-rinforza. La regola di pertinenza v13/v14 mitiga ma il vero
fix è igiene del corpus: eliminare le note di test (o i loro tag) prima
dell'uso reale.

### G. Eco del titolo dentro la prima frase — limite noto dello strip
"Rifronione sulla giornata. Mi sento…" — l'eco è FUSO nella prima riga col
resto, quindi lo strip (che confronta la riga intera) non scatta. v14 chiede
al modello di non emetterlo; se ricapita, si estende lo strip al prefisso.

## Stato

- Prompt attivo: **v14** (clean build necessaria).
- Test aggiunti: bold repair ×2, junk numerico, risoluzione surface composta.
- Probe per il round 4: P1 (niente frase duplicata + mention venerdì), lista
  (niente riga-eco, niente prosa-copia), telecomando (checkbox senza "devo"),
  readme + "due cose" (checkbox recall — il punto da osservare).

---

## Round 4 (v14) — risultati e svolta deterministica

**Vittorie nette:** Anna e la lista della spesa sono le prime note PERFETTE
(dedup ✓, eco ✓, tag pertinenti [casa, personale] ✓); il bold repair ha
ripulito la nota Sarah; "comprare le pile" è checkbox senza "devo" (regola
action-only ✓); il README ha finalmente la sua checkbox (primo successo in
4 round); "domani alle 5" risolto via scanner-on-surface ✓.

**Il verdetto sulla duplicazione:** ancora presente in 4 note su 6
applicabili (Luca, dentista, readme, Sarah). Dopo DUE round di prompt
(v13 exclusive-survival, v14 tripla ribadita + esempio) la conclusione è
strutturale: omettere una frase dalla prosa mentre la si emette come
checkbox è pianificazione globale che il 2B non sa fare in modo affidabile.
**Risolto in codice** (filosofia ADR 0015): `CommitmentDeduplicator` —
una frase di prosa coperta da una checkbox (matching token-based con
tolleranza di prefisso ≥4 char per reggere il garbling ASR
"rieffilogo"/"rieffogo") viene rimossa; se la prosa portava informazione in
più ("…per spostare l'appuntamento"), il testo della checkbox viene
PROMOSSO alla versione completa prima della rimozione — mai perdita di
contenuto, solo della seconda copia. 5 test sui body reali del round 4.

**Altri interventi dello stesso round:**
- "venerdì" sparito nonostante il backstop → sospetto Unicode: whisper può
  emettere accenti decomposti (i + combining grave) che mancano le chiavi
  composte delle tabelle. Fix: normalizzazione NFC in scanner e resolver.
- "Due cose" finita in fallback plain (structured=false, doppio fail JSON
  del modello sul multi-topic) e senza chip "sabato" → lo scanner ora gira
  anche su `plainTextFallback`, quindi pure le note non strutturate hanno
  le loro mention deterministiche. Per il doppio fail JSON: alla prossima
  occorrenza serve il contenuto della debug card ("Last model response")
  per capire cosa emette il modello su questi input.
- Residui accettati per ora: titolo errato sul telecomando ("Lista della
  spesa" — varianza 2B), tag coniati strani ("compravio", "naturale"),
  paragrafi della favola tornati monolitici (varianza; eventuale split
  deterministico dei paragrafi >5 frasi resta un'opzione futura), eco del
  titolo fuso nella prima frase (limite noto dello strip).

**Tendenza sui 4 round:** 2/10 perfette al round 4 contro 0 nei precedenti,
e ogni difetto sistematico ha ora un guard deterministico. Il residuo è
varianza del modello (titoli/tag) + rumore ASR — il primo si accetta o si
constrained-decoda, il secondo si cura con il modello whisper.

---

## Round 5 (deduplicatore attivo) — 4 note mirate

**3 su 4 perfette.** Dentista: una sola checkbox "Domani alle 5 richiamare il
dentista per spostare l'appuntamento" — è l'upgrade del deduplicatore al
lavoro (versione completa promossa, copia in prosa rimossa). README e Sarah:
solo checkbox, bold pulito, mention Monday ✓. La duplicazione è SPARITA in
tutte le note dove esisteva una checkbox: 3/3.

**La quarta (Riunione/Luca)** non è un fail del dedup: il modello non ha
emesso NESSUNA checkbox ("Devo ricordarmi di mandare…" rimasto solo prosa),
quindi non c'era nulla da deduplicare. È il checkbox-recall variance già
noto — e da timeline (created 19:26, updated 19:32, "ho dovuto
riformattarla") il primo structuring era fallito/plain e l'utente ha usato
il restructure manuale. Nota positiva: mention "venerdì" presente e risolta
(il fix NFC + backstop funziona anche qui).

**Prossimo passo candidato per chiudere il recall:** simmetrico al
deduplicatore — un *promotore* deterministico: una frase di prosa che inizia
con un commitment marker ("devo…", "I need to…") e che NESSUNA checkbox
copre viene promossa a checkbox (stessa tokenizzazione, marker strippato).
Insieme, promoter + deduplicator renderebbero il contratto
"un impegno = una checkbox, una volta sola" indipendente dal modello.
Alternativa di lungo periodo: constrained decoding
(docs/research/constrained-decoding-investigation.md).

Residui minori osservati: tag "personal" (EN) su nota IT (cross-language
leak occasionale), garbling ASR invariato ("riepidoco", "sul ritmi",
"ripubpubblicare").
