# v12 — script di test da dettare (on-device, Pixel, Backend.GPU)

Data: 2026-06-10 · Build: clean install con prompt v12 + fix libOpenCL
Per ogni probe: detta il testo tra virgolette così com'è, poi annota l'esito
nella tabella in fondo. Annota anche il **percorso**: review entro ~8 s
(sincrono) oppure snackbar "structuring continues in the background".

---

## P1 — Checkbox in mezzo alla prosa (il probe più importante)

Detta (IT, lingua su Auto):
> «Oggi è andata bene la riunione col team. Devo ricordarmi di mandare il
> riepilogo a Luca entro venerdì. Poi volevo dire che il nuovo layout mi
> convince molto di più del precedente.»

Atteso: prosa per riunione e layout; **una sola checkbox** `- [ ] Mandare il
riepilogo a Luca entro venerdì` (o simile); mention `entro venerdì` risolta al
**prossimo** venerdì; 2-4 tag italiani (es. lavoro).
Fallisce se: nessuna checkbox, o checkbox anche per la riunione/layout.

## P2 — Nota cortissima (leakage dagli esempi)

Detta:
> «Comprare le pile per il telecomando.»

Atteso: nota corta, una checkbox, `mentions: []`, niente titolo gonfiato.
Fallisce se: compaiono call, preventivi, commercialisti, dentisti o Tom —
qualunque cosa dagli esempi del prompt — o se la nota viene "allungata".

## P3 — Inglese con lingua PINNATA (language lock)

Pinna **English** dal chip lingua, poi detta:
> "Quick thought: I should email Sarah about the budget review on Monday
> morning."

Atteso: titolo, tag e body **interamente in inglese**; checkbox per l'email;
mention "on Monday morning" → lunedì prossimo. `language: "en"`.
Fallisce se: tag o titolo in italiano (bleed), o lingua mista.
(Ripinna Auto dopo il test.)

## P4 — Nessun riferimento temporale (junk mentions)

Detta:
> «Riflessione sulla giornata: mi sento molto più tranquillo quando lavoro
> senza notifiche, dovrei farlo più spesso.»

Atteso: prosa, `mentions: []` (nessun chip data), tag tipo riflessione/
personale. "Dovrei farlo più spesso" può legittimamente diventare checkbox.
Fallisce se: compare un chip datetime "null" o inventato.

## P5 — Risoluzione orario + future bias

Detta:
> «Domani alle quindici devo richiamare il dentista per spostare
> l'appuntamento.»

Atteso: checkbox; mention `domani alle quindici` → domani ore 15:00 **con
offset** (es. `+02:00`), mai una data passata.
Fallisce se: data nel passato (il bug storico del 2026-05-19) o non risolta.

## P6 — Riuso tag dal corpus

Detta una nota su un tema per cui HAI GIÀ un tag (guarda prima la lista tag
nelle note). Esempio se hai già `notari`:
> «Per Notari devo sistemare la descrizione sul README prima di pubblicare.»

Atteso: riusa il tag esistente **identico**, non un sinonimo nuovo.
Fallisce se: conia `app-notari`, `progetto-notari` ecc. accanto al tuo tag.

## P7 — Due argomenti → headings con prosa prima

Detta:
> «Due cose. Primo: sabato pranzo dai miei, devo portare il dolce. Secondo:
> per il sito devo aggiornare le foto e poi chiedere un preventivo al
> fotografo.»

Atteso: due `##`; sotto il primo, la prosa dell'evento PRIMA della checkbox
del dolce; mention `sabato` → date-only del prossimo sabato; le due checkbox
del secondo topic.
Fallisce se: heading senza prosa sotto, o un solo blocco indistinto.

## P8 — Enumerazione → bullet, non checkbox

Detta:
> «Lista della spesa: pane, latte, uova, caffè e detersivo per i piatti.»

Atteso: bullet list `- ` (cinque voci), NON checkbox, `mentions: []`.
Fallisce se: prosa con virgole, o checkbox su ogni voce.

## P9 — Meta-speech preservato

Detta:
> «Allora, non ricordo bene se la scadenza è il venti o il ventuno, comunque
> devo controllare la mail di Anna.»

Atteso: il "non ricordo bene se il venti o il ventuno" resta NEL body (è
contenuto, non filler); checkbox per la mail; nessuna data inventata (la
scadenza ambigua NON va risolta in mention).
Fallisce se: il dubbio viene "ripulito" o la data viene decisa dal modello.

## P10 — Nota lunga (stress: 60-90 s di parlato continuo)

Detta a ruota libera per ~1 minuto su com'è andata la giornata, infilando
TRE impegni sparsi («devo…») in punti diversi. Atteso: tutte e tre le
checkbox catturate, struttura leggibile, latenza da GPU (review entro pochi
secondi o background breve).

---

## Tabella risultati

| Probe | Esito (ok/fail) | Percorso (sync/bg) | Tempo ~s | Note |
|-------|-----------------|--------------------|----------|------|
| P1    |                 |                    |          |      |
| P2    |                 |                    |          |      |
| P3    |                 |                    |          |      |
| P4    |                 |                    |          |      |
| P5    |                 |                    |          |      |
| P6    |                 |                    |          |      |
| P7    |                 |                    |          |      |
| P8    |                 |                    |          |      |
| P9    |                 |                    |          |      |
| P10   |                 |                    |          |      |

Criterio: P1–P5 devono passare tutti (sono i comportamenti che la v11
proteggeva con la ripetizione). Un fail su P6–P9 è discutibile caso per caso;
due o più fail → rollback a v11 (`AssetPromptLoader.ACTIVE_PROMPT` + clean
build) e si ri-ingrassa solo la regola regredita.
