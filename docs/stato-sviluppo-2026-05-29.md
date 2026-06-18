# Notari — Stato sviluppo al 2026-05-29

## Sintesi

La submission v1.0.0 per il Gemma 4 DEV Challenge è stata completata e documentata. Il core funzionale dell'app (cattura vocale → trascrizione → strutturazione con Gemma → salvataggio) è **funzionante end-to-end** sul dispositivo di riferimento (Pixel 6a). A fine giornata (2026-05-29) **tutti i fronti post-submission identificati sono chiusi**: ASR whisper.cpp con test BT in-auto (ADR 0018 Accepted), encryption at rest SQLCipher validata on-device incl. migrazione plaintext→encrypted (ADR 0019 Accepted), empty-catch loggati, test passphrase su Keystore reale (androidTest), model delivery deciso GitHub-only/SAF (ADR 0022 Accepted) con onboarding SAF migliorato, e re-strutturazione on-demand delle note nel dettaglio. Restano solo **miglioramenti post-v1** (localizzazione UI, accuratezza in-car, qualità formattazione) e follow-up minori non bloccanti. Tutto il lavoro post-submission vive sul branch `feature/asr-whisper`; `main`/tag `v1.0.0` restano la versione SpeechRecognizer intatta per la challenge.

> **Nota fonte di verità:** questo file è stato consolidato a fine giornata 2026-05-29. Le sezioni sotto riflettono lo stato reale a quel punto.

---

## Cosa è implementato e funzionante

### Architettura e infrastruttura
- Struttura multi-modulo Gradle con convention plugin (`build-logic`): `:app`, `:feature:*`, `:core:*`
- Clean Architecture MVI: ogni feature ha `UiContract` + `ViewModel` + `Route`; nessun layer skipping
- Hilt per la DI su tutti i layer
- Detekt + ktlint configurati con CI gate
- `NoAudioPersistenceTest` e privacy CI gate (`check-no-internet-permission.sh`) attivi
- 22 ADR documentati in `docs/decisions/` (fino a ADR 0022); nota di ricerca su constrained decoding in `docs/research/`

### Inferenza (`:core:inference`)
- `LiteRtLmGemmaSession` con Gemma 4 E2B INT4 via LiteRT-LM
- Backend probing automatico GPU → CPU (ADR 0011)
- MTP speculative decoding abilitato (ADR 0011, ~25% speedup su CPU)
- Timeout scalati sulla lunghezza del trascritto, cold/warm separati (ADR 0016)
- `onTrimMemory` rilascia l'engine; ricarica lazy alla prossima generate (ADR 0009)
- Prompt v10 (`structure_note_v10.txt`) con: date anchoring ISO-8601, tag reuse, anti-hallucination clause, Thinking Mode strip difensivo (ADR 0010, 0012, 0014)
- Retry con `StricterPromptTemplate` su parse failure; fallback a nota plain-text (ADR 0005)
- `MarkdownBodyFormatter`, `RelativeDateTimeResolver`, `TagValidator` nel layer normalize
- Suite prompt-eval con trascritti reali in 6 lingue

### ASR (`:core:asr`)
- `SpeechToTextSession` interface con seam reale (`FakeSpeechToTextSession`)
- `BatchSpeechToTextSession`: cattura PCM continua con `AudioRecord` in RAM, batch transcribe a fine registrazione
- `WhisperBatchTranscriber` / `WhisperContext`: whisper.cpp via JNI, `-O3` forzato nel CMakeLists
- Auto-selezione del miglior modello whisper disponibile (preferenza: file importato via SAF → `ggml-small-q5_1.bin` → … → `ggml-tiny.bin`); path canonico condiviso in `WhisperModelLocation`
- `BluetoothAudioRouter`: routing verso microfono BT (scenario in-auto)
- `RecordingForegroundService` per cattura con schermo spento
- `FallbackSpeechToTextSession`: `AndroidSpeechToTextSession` come fallback (ADR 0003)
- `VoskSpeechToTextSession` presente ma non cablato (spike di riferimento, ADR 0018)
- Validato su Pixel 6a con `ggml-base.bin` (2026-05-27): italiano fedele, brand/jargon inglese corretto

### Feature
- **`:feature:capture`**: waveform live, fasi Idle / Preparazione / Recording / Transcribing / Structuring, discard senza salvare; **banner "Set up"** quando manca un modello (ADR 0022)
- **`:feature:notes`**: lista note con timestamp relativo, ricerca, tag filter
- **`:feature:notedetail`**: rendering Markdown, sezione Mentions con datetime risolte; **"Struttura con AI"** — retry on-demand della strutturazione su note rimaste plain-text (banner + azione ✨ in topbar)
- **`:feature:settings`**: language pin, biometric lock toggle, privacy section; **import SAF di entrambi i modelli** (Gemma + whisper) con validazione (nome/dimensione) ed errori chiari (ADR 0022)
- **`:feature:onboarding`**: 3 slide informative (l'import modello vive in Settings, ADR 0008)

### Sicurezza e privacy
- Biometric launch lock opzionale (ADR 0013)
- Nessuna persistenza audio: buffer in RAM, zeroed dopo la trascrizione
- `NoAudioPersistenceTest` esteso al path whisper (guard statico anche sul bridge JNI cpp)
- `INTERNET` permission assente e bloccata via manifest merger (ADR 0007)
- `EXTRA_PREFER_OFFLINE = true` sul vecchio path `SpeechRecognizer`
- Strip dei log informativi nella release via R8 (ADR 0021)
- Encryption at rest del DB Room via SQLCipher (ADR 0019, vedi sezione Database)

### Database (`:core:database`)
- Room con entità `NoteEntity`, `TagEntity`, `MentionEntity`
- `NoteRepositoryImpl` con DAOs e mapper
- **Cifratura at rest attiva** (ADR 0019 Accepted): SQLCipher via `SupportOpenHelperFactory`, passphrase 32 byte random (passata come stringa base64 a tutti i call-site per coerenza della chiave) wrapped con chiave AES-256-GCM hardware-backed (StrongBox preferito, fallback TEE), persistita in `<filesDir>/db_passphrase.enc`. Migrazione one-shot `voice_note.db` plaintext → encrypted via `sqlcipher_export()` (crash-safe, detection su magic-byte). **Validata on-device** (fresh install + reboot + migrazione con note reali). Test `DatabasePassphraseProvider` in `androidTest/` (Keystore reale, 5/5 sul Pixel 6a).

### Docs e submission
- `docs/dev-post-submission.md`: post DEV finalizzato e pubblicato
- `docs/submission-checklist.md`: checklist pre-submission compilata
- `README.md`, `CHANGELOG.md`, `LICENSE` presenti
- Launcher icon, ProGuard keep rule per LiteRT-LM

---

## Cosa manca / è aperto

### 🔴 Critico — non ancora fatto

_(Nessuna voce critica aperta — i fronti ASR whisper e DB encryption sono chiusi.)_

### 🟢 Chiuso in giornata (2026-05-29)

| Area | Esito | Riferimento |
|------|-------|-------------|
| **Encryption at rest** | SQLCipher + chiave AES Keystore device-bound + migrazione one-shot crash-safe. Validata on-device (fresh install + reboot). ADR Accepted. | ADR 0019 |
| **Migrazione plaintext→encrypted** | Validata sul Pixel 6a con un `voice_note.db` pre-0019 seedato (DDL Room + identity hash + note/tag/mention reali): migra e Room apre il DB cifrato; note leggibili nella UI. | ADR 0019 |
| **Bug chiave-zero** | Trovato/fixato: `SupportOpenHelperFactory` tiene il byte[] per riferimento (lazy) → non va azzerato; passphrase normalizzata a stringa base64 su tutti i call-site. | ADR 0019 |
| **ASR whisper in-auto con BT** | Test reale (interno mic + HFP BT); accuracy trade-off del codec BT documentato; ADR Accepted. | ADR 0018 |
| **Diagnostic log cleanup** | _Superato_ da ADR 0021: i log restano nel sorgente, R8 li strippa in release via `-assumenosideeffects`. | ADR 0021 |
| **Release ABI** | Verificato: `abiFilters` (arm64-v8a, armeabi-v7a, x86_64) in `defaultConfig`, nessun override release. | `core/asr/build.gradle.kts` |
| **`NoAudioPersistenceTest` whisper** | Esteso al bridge JNI (`whisper_jni.cpp`): guard su `fopen`/`fwrite`/`ofstream`/`freopen`. Vendored `whisper.cpp/` escluso. | ADR 0018 follow-up |
| **Empty catch blocks** | I 5 `catch (_: Exception) {}` di `AndroidSpeechToTextSession` ora loggano via `Log.w` (tag `AsrFallback`). | ADR 0019 follow-up |
| **Test passphrase su Keystore reale** | `DatabasePassphraseProviderTest` spostato in `androidTest/` (era `@Ignore`'d sotto Robolectric, niente shadow AndroidKeyStore); 5/5 sul Pixel 6a. | ADR 0019 follow-up |
| **Model delivery** | **Deciso (ADR 0022 Accepted): solo GitHub + import SAF.** Play/PAD rimandato (reversibile). Con SAF non si ridistribuiscono i pesi → licenza Gemma e sizing PAD diventano non-problemi. Findings (Gemma 4 ≈ Apache 2.0; PAD pack 512 MB/totale 2 GB) archiviati nell'ADR. | ADR 0022 |
| **Onboarding SAF** | Import whisper via SAF (prima solo adb push), validazione import (nome/dimensione) con errori chiari, copy+link in Settings, banner "Set up" in cattura. | ADR 0022 |
| **Re-strutturazione on-demand** | "Struttura con AI" nel dettaglio nota: retry della strutturazione su note rimaste plain-text, senza perdere il testo. | ADR 0005 / ADR 0022 follow-up |
| **Indagine constrained decoding** | `litertlm 0.11.0` espone `ExperimentalFlags.enableConversationConstrainedDecoding`, ma legato al path tool-calling (OpenApiTool): adozione = re-architettura di `:core:inference`. Promettente, non urgente. | `docs/research/constrained-decoding-investigation.md` |
| **16 KB page-size compatibility** | App **verificata 16 KB-compatibile**. ELF fix: flag NDK per le native whisper + SQLCipher `4.5.6 → 4.16.0` (LiteRT-LM 0.11.0 e DataStore 1.1.1 erano già allineate, verificato readelf). Packaging già OK (`.so` uncompressed + zipalign 16 KB). L'avviso "Android app compatibility" su HyperOS è un falso positivo della **debug build**; **release build = nessun avviso** (verificato on-device). | `docs/research/16kb-page-size-alignment.md` |

### 🟢 Miglioramenti noti (post-v1, mai iniziati)

| Area | Descrizione |
|------|-------------|
| **Localizzazione UI** | L'app è solo in inglese, **con stringhe italiane hardcoded** ("Preparazione…", "Trascrizione…", "Silent Mic", "Process with AI") → incoerente. Lavoro: estrarre tutte le stringhe in `strings.xml` (oggi nei Composable) + tradurre nelle lingue v1. Le note restano nella lingua del dettato. |
| **Qualità formattazione** | Leve in ordine di costo: (1) **few-shot nel prompt** (economico, nessun training); (2) **constrained decoding** (vedi indagine — risolve solo i fallback da JSON malformato, non i timeout); (3) **LoRA** — scartato per ora: richiede backend GPU, ma il Pixel 6a gira su CPU. |
| **whisper accuracy** | Degrado su codec Bluetooth HFP (documentato in ADR 0018); possibile pre-processing/noise-reduction sul PCM o upgrade `ggml-medium` (RAM/tempo permettendo). |
| **Gemma function calling** | Se l'API lo espone bene, alternativa al prompt engineering per la strutturazione (intersect con constrained decoding / ADR 0015 intent). |
| **In-car VAD** | Voice-activity detection per ridurre rumore in auto prima di passare il PCM a whisper. |
| **Gemma audio-native (v3)** | Path a lungo termine, backlog; non rilevante ora. |

---

## Stato per pilastro (DEV Challenge framing)

| Pilastro | Stato |
|----------|-------|
| **Pillar 1 — Trascrizione vocale locale** | ✅ Funzionante con whisper.cpp batch; BT in-auto validato (ADR 0018 Accepted) |
| **Pillar 2 — Privacy (no rete, no audio su disco)** | ✅ Architetturalmente garantito + encryption at rest attiva (ADR 0019 Accepted); guard JNI nel test di persistenza |
| **Pillar 3 — Strutturazione Markdown con Gemma** | ✅ Funzionante, prompt v10 stabile |
| **Pillar 4 — Output Markdown per Obsidian** | ✅ `NoteMarkdownExporter` implementato; export via SAF disponibile |

---

## Prossimi passi consigliati (in ordine di priorità)

Tutti i fronti bloccanti sono chiusi. I prossimi passi sono miglioramenti/igiene, non urgenti:

1. **Localizzazione UI** → estrarre le stringhe hardcoded (incl. quelle italiane) in `strings.xml` e tradurre nelle 6 lingue v1; sistemare l'incoerenza EN/IT attuale.
2. **Qualità formattazione — few-shot nel prompt** → la leva più economica (1-2 esempi trascritto→JSON ideale nel prompt), nessun training né rete.
3. **Merge `feature/asr-whisper` → `main`** quando la challenge lo consente (dopo il 4 giugno): il branch contiene whisper, encryption, onboarding SAF, re-strutturazione. `main` oggi è volutamente intatto (SpeechRecognizer) per la valutazione.
4. **Signing keystore reale** → la release build è verificata (16 KB OK, nessun avviso, no crash) ma è firmata con la debug key placeholder. Serve una keystore di firma vera prima di distribuire un APK release su GitHub.
5. **Constrained decoding (spike)** → solo se la qualità JSON lo richiede; prototipo dietro flag + misura su Pixel 6a (vedi `docs/research/`). Meriterebbe un ADR proprio.
6. **whisper accuracy in-car** (post-v1) → pre-processing/noise-reduction sul PCM; eventuale `ggml-medium` se RAM/tempo lo consentono.
