# Review sicurezza & performance — 2026-06-10

Scope: `core/asr`, `core/inference`, `feature/capture`, manifest, ProGuard, DI.
Ogni rilievo è stato verificato sul codice attuale. Ordinati per priorità.

> **Stato (2026-06-10, stessa sessione):** implementati #1, #2, #3, #4, #5, #6,
> #8, #9 (ADR 0027 — ownership buffer audio) e #7, #10, #11, #12, #13, #14
> (ADR 0028 — lifecycle engine, keep-alive nel VM, cap durata).
> #15 (cache I/O di `isAvailable`) è wontfix motivato: non è in hot path e una
> cache romperebbe il flusso dev `adb push` (vedi ADR 0028 "Explicitly NOT done").

---

## P1 — Bug e violazioni delle regole cardinali

### 1. Race cancel→restart: lo `stop()` ritardato azzera i buffer della NUOVA registrazione (perdita dati)

**Dove:** `CaptureViewModel.cancelRecording()` + `BatchSpeechToTextSession`.

`cancelRecording()` riporta subito la UI a `Idle` e lancia `stop()` in un coroutine
separato. `stop()` trascrive con whisper l'audio scartato (secondi di lavoro) e **solo
dopo** azzera e svuota `captured`. Ma `captured` è un campo condiviso della sessione
(una sola istanza per VM): se l'utente riavvia la registrazione mentre la vecchia
trascrizione è in corso, lo `stop()` ritardato esegue
`captured.forEach { it.fill(0) }; captured.clear()` **sui chunk della nuova sessione**
→ la nuova nota perde (o trascrive come silenzio) tutto l'audio catturato fino a quel
momento. Inoltre `captureStopped` viene resettato da `start()`, quindi un secondo
`stopCapture()` ritardato può rilasciare l'`AudioRecord` nuovo.

**Fix:**
- Aggiungere `discard()` a `SpeechToTextSession`: ferma la cattura e azzera+svuota i
  buffer **senza trascrivere**. `cancelRecording()` chiama `discard()`, non `stop()`.
- In `stop()`, fare uno **swap atomico** della lista sotto `capturedLock`
  (`val snapshot = captured; captured = ArrayList()`) e lavorare solo sullo snapshot,
  così uno stop tardivo non può toccare i dati di una sessione successiva.
- Idealmente: una *istanza* di stato per sessione (token/generation counter) invece di
  campi mutabili riusati tra start/stop.

### 2. PCM mai azzerato nei percorsi di abbandono (regola cardinale 2)

**Dove:** `BatchSpeechToTextSession`, `CaptureViewModel.onCleared()`.

- `awaitClose → stopCapture()` ferma la cattura ma **non azzera** `captured`.
- `onCleared()` cancella solo `recordingJob`: se il VM muore durante una registrazione,
  l'intero PCM resta in heap non azzerato a tempo indefinito.
- `start()` fa `captured.clear()` senza `fill(0)` → i chunk della sessione precedente
  restano in heap fino al GC.
- Chunk tardivo: se il reader thread supera i 500 ms di `join`, può appendere un chunk
  *dopo* lo zero+clear di `stop()`.

**Fix:** lo stesso `discard()` del punto 1 (zero+clear sotto lock) chiamato da
`onCleared()` e dal teardown; in `start()` azzerare prima di `clear()`; dopo il `join`
verificare `thread.isAlive` e in tal caso rifare zero+clear (o gated append con flag
sotto `capturedLock`).

### 3. Copia float dell'audio non azzerata

**Dove:** `WhisperBatchTranscriber.transcribe()`.

`FloatArray(pcm.size) { pcm[it] / PCM_FULL_SCALE }` è una copia completa della
dettatura che non viene mai sovrascritta. Fix:

```kotlin
val audio = FloatArray(pcm.size) { pcm[it] / PCM_FULL_SCALE }
try {
    whisper.transcribe(audio, language, threads).trim()
} finally {
    audio.fill(0f)
    whisper.release()
}
```

### 4. `release()` può chiudere l'Engine durante una generazione nativa

**Dove:** `LiteRtLmGemmaSession`.

Dopo un timeout di pass, la generazione nativa continua come "zombie" tenendo
`generationMutex`. Se in quel momento arriva `onTrimMemory(TRIM_MEMORY_COMPLETE)` (o
`release()` esplicito), `engine.close()` gira in parallelo a `sendMessage()` → rischio
SIGSEGV. Fix: in `release()` provare `generationMutex.tryLock()`; se occupato, settare
un flag `closeRequested` e chiudere nel `finally` di `runGenerate`.

---

## P2 — Sicurezza (hardening)

### 5. Manca `FLAG_SECURE`

Con il lock biometrico attivo le note restano visibili nella miniatura Recents e negli
screenshot. Fix in `MainActivity`, legato alla preferenza:

```kotlin
if (requireBiometricUnlock) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
```

(Eventualmente sempre attivo, coerente col posizionamento privacy-first.)

### 6. Marker injection nel prompt

**Dove:** `StaticPromptTemplate.render()`.

`{{TRANSCRIPT}}` è sostituito **per primo**: un transcript che contiene
`{{EXISTING_TAGS}}` / `{{NOW_ISO}}` (plausibile dall'input da tastiera) viene espanso
dalle replace successive. Fix: sostituire il transcript **per ultimo**.

### 7. Log del contenuto note nelle build debug

`Log.d(TAG, "Gemma response …: $text")` è strippato in release (ADR 0021), ma le build
debug usate quotidianamente sul Pixel lasciano il contenuto integrale delle note nel
ring buffer di logcat. Suggerito un gate esplicito (`BuildConfig.LOG_MODEL_OUTPUT`)
così anche in debug il default è off.

---

## P3 — Bug funzionali minori

### 8. Avviso "nota lunga" e auto-scroll sono codice morto dalla migrazione a whisper

**Dove:** `CaptureRoute.RecordingPane`.

In batch mode `partialTranscript` resta `""` per tutta la registrazione, quindi
`partialTranscript.length > LONG_NOTE_CHAR_THRESHOLD` non scatta mai e l'auto-scroll
non ha nulla da scrollare. L'advisory promesso dall'ADR 0016 (UX CPU lenta / nota
lunga) di fatto non appare più. Fix: basare la soglia sulla **durata** di
registrazione (es. > 3 min, derivata da elapsed o dai campioni catturati esposti come
`StateFlow<Duration>` dalla sessione).

### 9. Teardown audio sul main thread (jank fino a ~500 ms)

`awaitClose` esegue `stopCapture()` nel contesto del collector (Main):
`Thread.join(500)` + `AudioRecord.stop/release` + clear del routing BT bloccano la UI
a ogni stop/cancel. Fix: rendere il teardown asincrono — `stop()`/`discard()`
interamente su `Dispatchers.Default`, e in `awaitClose` delegare a un thread/scope di
background invece di joinare inline.

### 10. Il foreground service è governato solo dalla composizione di CaptureRoute

`LaunchedEffect(state.phase)` avvia/ferma il FGS solo finché la route è composta. Se in
futuro la navigazione cambiasse (capture non più sempre in backstack), il servizio
resterebbe orfano. Più robusto: pilotare start/stop dal VM (che possiede già la state
machine delle fasi), con la route come fallback.

### 11. `onTrimMemory(TRIM_MEMORY_COMPLETE)` è un no-op su API 34+

Già noto (commento in codice + follow-up ADR 0016). Conviene chiudere il follow-up:
`ProcessLifecycleOwner` per il background reale + timer di idle (5 min fuori da
capture) per rilasciare l'engine da 1.5 GB in modo deterministico.

---

## P4 — Performance / memoria

### 12. Memoria di registrazione illimitata

PCM 16 kHz mono ≈ 115 MB/h in `captured`; alla trascrizione si aggiunge la copia float
(2×) → picco ~3× la durata. Per sessioni in-car lunghe è un rischio OOM concreto su
4 GB. Fix minimo: cap di durata (es. 30 min) con stop automatico + avviso. Fix
migliore: trascrizione a finestre incrementali per non accumulare l'intera dettatura.

### 13. `existingNotes` tiene tutte le note in RAM nel CaptureViewModel

Serve solo il corpus tag scoped per lingua. Fix: query DAO dedicata
(`SELECT DISTINCT t.value, n.language FROM note_tags t JOIN notes n …`) e collect di
quella, invece dell'intero `observeAll()`.

### 14. Keep rules ProGuard troppo ampie

`-keep class com.google.android.gms.** { *; }` e `com.google.mediapipe.**` (non più
dipendenza diretta dopo ADR 0008) gonfiano l'APK e disattivano ottimizzazioni R8.
Restringere a ciò che LiteRT-LM effettivamente referenzia via JNI.

### 15. `ModelFileProvider.isAvailable()` fa I/O (listFiles) a ogni chiamata

Minore: per whisper, `anyModel()` lista directory a ogni invocazione. Cache con
invalidazione su import/delete se mai comparisse in hot path.

---

## Cose verificate e a posto

- Manifest: zero permessi di rete + scrubbing transitive (`tools:node="remove"`) ✓
- `allowBackup=false` + `dataExtractionRules` ✓
- SQLCipher at-rest con chiave Keystore (ADR 0019) ✓, passphrase non azzerata
  prematuramente (fix 2026-05-29 in essere) ✓
- `engineLoadMutex` + `generationMutex` coerenti con ADR 0016/0017 ✓
- Parser: sanitizzazione fence/BOM/thinking-tags + riparazione newline ✓
- FGS microfono: permessi `FOREGROUND_SERVICE_MICROPHONE` dichiarati nel modulo ✓
- Budget backend-aware GPU/CPU coerenti con l'ADR 0016 ✓

## Ordine d'attacco suggerito

1. `discard()` + swap atomico dei buffer (chiude #1, #2, #3 e gran parte di #9)
2. `FLAG_SECURE` (#5) e replace-order del prompt (#6) — fix da 5 minuti l'uno
3. Guard su `release()` vs generazione in volo (#4)
4. Advisory nota lunga basato su durata (#8) + cap durata registrazione (#12)
