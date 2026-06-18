# Bozza LinkedIn — Notari (ITA)
> Stato: BOZZA v3 · voce personale · da pubblicare dopo il 4 giugno 2026

---

Le mie idee migliori arrivano quando cammino, quando guido, quando sto facendo altro. Quasi mai da seduto davanti alla tastiera. E ho un vault Obsidian che vorrei riempire proprio con quelle.

Per un po' ho usato Pixel Recorder. Trascrive bene, ma quello che ti restituisce è un muro di testo dentro l'account Google. Non un file mio, non nel formato che voglio, non privato nel senso in cui intendo io questa parola.

Così ho iniziato a costruire Notari. Parli, il telefono trascrive, un modello linguistico mette in ordine tutto in Markdown, e la nota finisce dritta nel vault.

La cosa su cui non volevo scendere a compromessi è la privacy. E qui non è una frase scritta in una policy. L'app non ha proprio il permesso INTERNET. Niente cloud, niente abbonamenti, nessun analytics. È una cosa verificabile, basta aprire il manifest. L'audio resta in RAM mentre registri e viene sovrascritto subito dopo la trascrizione, sul disco non finisce niente.

Gira tutto sul telefono. La parte di strutturazione la fa Gemma 4 (E2B INT4) con MediaPipe LiteRT-LM, la trascrizione la fa whisper.cpp. Lo sviluppo l'ho fatto sul mio Pixel 6a, che onestamente non è il dispositivo più adatto per far girare un sistema del genere. È lento, dalla fine della dettatura alla nota pronta passano diversi secondi, a volte anche un minuto o più. Ma per come lo uso io va più che bene, e comunque preferisco aspettare un po' di più sapendo che le mie parole non escono dal telefono.

La trascrizione è stata la parte più rognosa, e anche quella che mi ha insegnato di più. Ho provato tre motori prima di trovare quello giusto. Lo SpeechRecognizer di Android nelle dettature lunghe perdeva pezzi, è fatto per i comandi brevi, non per parlare due minuti di fila. Vosk quel problema lo risolveva, ma impastava l'inglese e si perdeva quando mescolo le lingue, che per le mie note piene di termini tecnici è un disastro. Alla fine la versione che uso ora gira su whisper.cpp in modalità batch, multilingua, fedele. In macchina, a mani libere, di vedere la trascrizione in tempo reale non me ne faccio niente, quindi il batch va benissimo.

Una cosa la voglio dire chiara, non sono uno sviluppatore di professione. Però nell'ultimo anno e mezzo l'AI è entrata stabilmente nel mio flusso di lavoro, ormai la uso ogni giorno per un sacco di cose, e mi ha permesso di costruirmi su misura gli strumenti che mi servono davvero. Un paio di PWA per gestire le mie spese e i miei clienti, con tanto di preventivi e fatture, una per il meteo del volo libero, un'app in Flutter per il trekking, qualche plugin per WordPress, un'altra app in Kotlin che usa Gemma 4 E2B per fare RAG sui documenti. E poi diverse cose rimaste allo stato di prototipo, lasciate lì per ora.

Notari l'ho costruito quasi tutto insieme a Claude, dall'architettura alla pipeline di inferenza, dalla UI in Compose ai workaround per i bug di MediaPipe. Ogni scelta tecnica discussa, provata, scritta in un ADR. Quello che mi appassiona sono gli LLM in locale, usati per tirare su piccole utilità come questa, e la cosa che trovo bella è che oggi, con gli strumenti che ci sono, si riesce davvero a fare tantissimo.

Quello che ho capito è dove questa collaborazione funziona davvero, fin dal brainstorming e dalla roadmap, e poi nella progettazione, nel debug, nello scrivere codice pulito, e dove invece devi metterci la testa tu. La storia dei tre motori di trascrizione è proprio l'esempio. Non è una decisione che un assistente prende al posto tuo, perché dipende da come usi l'app, dal fatto che la uso in macchina, a mani libere, in due lingue. Il contesto, i compromessi, la coerenza nel tempo restano roba tua.

Notari è nato perché volevo una cosa che funzionasse per me. Ma alla fine è diventato anche la risposta a una domanda che mi porto dietro da quando sono usciti i primi modelli, ChatGPT, Bard. Da subito mi sono chiesto cosa sanno fare, cosa ci posso fare, dove arriveremo. E fin dall'inizio mi sono messo a esplorare le possibilità e i limiti di questa tecnologia. Quanto riesco davvero a costruire con gli strumenti che ci sono oggi? Più di quello che pensavo. I limiti me li aspettavo, l'aiuto che mi è arrivato un po' meno.

E forse è questo il punto. Non diventeremo tutti programmatori, però l'idea, l'avere in testa qualcosa di utile da costruire, conta sempre di più del saperlo scrivere a mano riga per riga.

Il codice è open source 👇
https://github.com/oierreaemme/notari
