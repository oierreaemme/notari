# Notari — Stato sviluppo al 2026-05-29

## Sintesi

La submission v1.0.0 per il Gemma 4 DEV Challenge è stata completata e documentata. Il core funzionale dell'app (cattura vocale → trascrizione → strutturazione con Gemma → salvataggio) è **funzionante end-to-end** sul dispositivo di riferimento (Pixel 6a). I due fronti post-submission sono stati chiusi entrambi in giornata: il motore ASR whisper.cpp ha completato il test BT in-auto e ADR 0018 è ora Accepted; SQLCipher è stato implementato e ADR 0019 è Accepted. Restano follow-up minori (model delivery, ripulitura `catch (_:) {}`, miglioramento accuratezza in-car).

---

## Cosa è implementato e funzionante

### Architettura e infrastruttura
- Struttura multi-modulo Gradle con convention plugin (`build-logic`): `:app`, `:feature:*`, `:core:*`
- Clean Architecture MVI: ogni feature ha `UiContract` + `ViewModel` + `Route`; nessun layer skipping
- Hilt per la DI su tutti i layer
- Detekt + ktlint configurati con CI gate
- `NoAudioPersistenceTest` e privacy CI gate (`check-no-internet-permission.sh`) attivi
- 21 ADR documentati in `docs/decisions/`

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
- `WhisperBatchTranscriber` / `WhisperContext`: whisper.cpp via JNI, modello `ggml-base.bin`, `-O3` forzato nel CMakeLists
- Auto-selezione del miglior modello whisper disponibile
- `BluetoothAudioRouter`: routing verso microfono BT (scenario in-auto)
- `RecordingForegroundService` per cattura con schermo spento
- `FallbackSpeechToTextSession`: `AndroidSpeechToTextSession` come fallback (ADR 0003)
- `VoskSpeechToTextSession` presente ma non cablato (spike di riferimento, ADR 0018)
- Validato su Pixel 6a con `ggml-base.bin` (2026-05-27): italiano fedele, brand/jargon inglese corretto

### Feature
- **`:feature:capture`**: waveform live, fasi Idle / Preparazione / Recording / Transcribing / Structuring, discard senza salvare
- **`:feature:notes`**: lista note con timestamp relativo, ricerca, tag filter
- **`:feature:notedetail`**: rendering Markdown, sezione Mentions con datetime risolte
- **`:feature:settings`**: language pin, biometric lock toggle, privacy section
- **`:feature:onboarding`**: import modello via SAF (ADR 0008)

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
- **Cifratura at rest attiva** (ADR 0019 Accepted): SQLCipher via `SupportOpenHelperFactory`, passphrase 32 byte random wrapped con chiave AES-256-GCM hardware-backed (StrongBox preferito, fallback TEE), persistita in `<filesDir>/db_passphrase.enc`. Migrazione one-shot `voice_note.db` plaintext → encrypted via `sqlcipher_export()` (crash-safe). Test TDD su `DatabasePassphraseProvider` (Robolectric).

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
| **Encryption at rest** | Implementato. SQLCipher + chiave AES Keystore device-bound + migrazione one-shot crash-safe + test TDD. ADR flippato ad Accepted. | ADR 0019 Accepted |
| **ASR whisper in-auto con BT** | Test reale completato sul Pixel 6a (interno mic + HFP BT); accuracy trade-off del codec BT documentato; ADR flippato ad Accepted. | ADR 0018 amendment 2026-05-29 |
| **Diagnostic log cleanup** | _Superato_ da ADR 0021: i log restano nel sorgente, R8 li strippa solo in release via `-assumenosideeffects`. | ADR 0021 |
| **Release ABI restore** | Verificato: `abiFilters` (arm64-v8a, armeabi-v7a, x86_64) è in `defaultConfig`, nessun override release. Nessuna riduzione. | `core/asr/build.gradle.kts:24` |
| **`NoAudioPersistenceTest` whisper** | Esteso: guard statico sul bridge JNI (`whisper_jni.cpp`) per `fopen`/`fwrite`/`ofstream`/`freopen`. Vendored `whisper.cpp/` escluso (non linkato nel runtime target). | ADR 0018 follow-up |

### 🟡 Importante — roadmap immediata

| Area | Descrizione | Riferimento |
|------|-------------|-------------|
| **Model delivery** | SAF funziona ma non scala. Decisione bundle vs. Play Asset Delivery aperta; whisper.cpp aggiunge un secondo asset. | ADR 0008, ADR 0018 |
| **Empty catch blocks** | `AndroidSpeechToTextSession` ha `catch (_: Exception) {}` silenziosi segnalati dalla review esterna. | ADR 0019 follow-up |

### 🟢 Miglioramenti noti (post-v1)

| Area | Descrizione |
|------|-------------|
| **Localizzazione UI** | L'app è solo in inglese; le note sono nella lingua del dettato. |
| **Gemma function calling** | Investigazione aperta: se MediaPipe Android lo espone, sostituisce il prompt engineering per la strutturazione (ADR 0015 intent). |
| **whisper accuracy** | "synth" → "sint" osservato; possibile fine-tuning o upgrade a `ggml-small`. |
| **In-car VAD** | Voiceactivity detection per ridurre rumore in auto prima di passare il PCM a whisper. |
| **Gemma audio-native (v3)** | Ancora nel backlog come path a lungo termine; non rilevante ora. |

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

1. ~~**Smoke test on-device di ADR 0019**~~ → ✅ **fatto (2026-05-29)**: due note dettate, leggibili dopo process-kill e dopo reboot del telefono; la chiave Keystore hardware-backed sopravvive al cold boot. Encryption at rest confermata end-to-end sul Pixel 6a.
2. **Test migrazione upgrade da DB plaintext** → lo smoke sopra copre il fresh-install path; resta da validare su un device con un `voice_note.db` pre-0019 reale (oppure `adb push` di un DB plaintext) che il `sqlcipher_export()` preservi le note esistenti
3. **Decidi model delivery** (whisper + Gemma: bundle? PAD? SAF-only?) → sblocca la distribuzione
4. **Fix empty catch blocks** in `AndroidSpeechToTextSession` → cleanup richiesto dalla review esterna (ADR 0019 follow-up)
5. **Migliorie accuracy in-car** (post-v1) → pre-processing noise reduction sul PCM prima di whisper; eventuale upgrade `ggml-medium` se RAM/tempo lo consentono
