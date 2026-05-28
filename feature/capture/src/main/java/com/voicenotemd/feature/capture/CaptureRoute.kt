package com.voicenotemd.feature.capture

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.design.components.MentionsSection
import kotlinx.coroutines.delay

/**
 * Public entry point for the capture feature, called from the app navigation graph.
 *
 * The home screen IS the capture screen — see ADR 0001. We keep this composable thin so
 * that the navigation contract is decoupled from the internal screen state machine
 * (which lives in [CaptureScreen] and [CaptureViewModel]).
 */
@Composable
fun CaptureRoute(
    onOpenNotes: () -> Unit,
    onOpenSettings: () -> Unit,
    onNoteSaved: (String) -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            viewModel.onIntent(CaptureUiIntent.PermissionResult(granted = granted))
        }

    // Best-effort: the foreground-service "recording" notification needs POST_NOTIFICATIONS
    // on Android 13+. If denied, recording still works — only the visible indicator is missing.
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { /* best-effort; the foreground service starts regardless */ }

    LaunchedEffect(viewModel) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is CaptureUiEvent.NavigateToNote -> onNoteSaved(event.noteId)
                CaptureUiEvent.RequestPermission -> {
                    val alreadyGranted =
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    if (alreadyGranted) {
                        viewModel.onIntent(CaptureUiIntent.PermissionResult(granted = true))
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                CaptureUiEvent.StructuringFellBack -> {
                    snackbarHost.showSnackbar(
                        "Could not auto-structure this note — saved as plain text.",
                    )
                }
            }
        }
    }

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage ?: return@LaunchedEffect
        snackbarHost.showSnackbar(msg)
        viewModel.onIntent(CaptureUiIntent.DismissError)
    }

    // Re-arm the Gemma engine every time the user returns to capture. The VM's
    // init-time warm-up only fires once per VM lifetime; this hook fires every
    // ON_RESUME, so it picks up the case where `onTrimMemory(>= TRIM_MEMORY_BACKGROUND)`
    // unloaded the 1.5 GB engine while the user was elsewhere (notes list, settings,
    // home screen). Without it, the user's next dictation hits a cold-load + prefill
    // that often blows past even the bumped 45s cold budget — the dominant cause of
    // plain-text fallbacks we saw on real-device traces 2026-05-16.
    LifecycleResumeEffect(viewModel) {
        viewModel.warmUpIfNeeded()
        onPauseOrDispose { /* no cleanup needed — warm-up is fire-and-forget */ }
    }

    // Keep capture alive while the screen is off / the app is backgrounded (hands-free,
    // in-car use): a microphone foreground service holds the process and background mic
    // access for the duration of the Recording phase. The service lifecycle tracks the
    // phase exactly — start on enter, stop on any other phase. See ADR 0018.
    LaunchedEffect(state.phase) {
        if (state.phase == CaptureUiState.Phase.Recording) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            RecordingForegroundService.start(context)
        } else {
            RecordingForegroundService.stop(context)
        }
    }

    CaptureScreen(
        state = state,
        snackbarHost = snackbarHost,
        onOpenNotes = onOpenNotes,
        onOpenSettings = onOpenSettings,
        onIntent = viewModel::onIntent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureScreen(
    state: CaptureUiState,
    snackbarHost: SnackbarHostState,
    onOpenNotes: () -> Unit,
    onOpenSettings: () -> Unit,
    onIntent: (CaptureUiIntent) -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Notari") },
                actions = {
                    IconButton(onClick = { onIntent(CaptureUiIntent.ToggleTextInput) }) {
                        Icon(Icons.Outlined.Keyboard, contentDescription = "Type note")
                    }
                    IconButton(onClick = onOpenNotes) {
                        Icon(Icons.Outlined.Notes, contentDescription = "Notes")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        when (state.phase) {
            CaptureUiState.Phase.Reviewing ->
                ReviewPane(
                    state = state,
                    padding = padding,
                    onIntent = onIntent,
                )
            CaptureUiState.Phase.Structuring ->
                StructuringPane(
                    padding = padding,
                    startedAtMs = state.structuringStartedAtMs,
                    estimatedSeconds = estimateStructuringSeconds(state.partialTranscript.length),
                )
            else -> RecordingPane(state = state, padding = padding, onIntent = onIntent)
        }
    }

    if (state.showLanguagePicker) {
        LanguagePickerSheet(
            current = state.activeLanguage,
            onPick = { onIntent(CaptureUiIntent.PickLanguage(it)) },
            onDismiss = { onIntent(CaptureUiIntent.DismissLanguagePicker) },
        )
    }

    if (state.showTextInput) {
        TextInputSheet(
            onSubmit = { onIntent(CaptureUiIntent.SubmitText(it)) },
            onDismiss = { onIntent(CaptureUiIntent.ToggleTextInput) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextInputSheet(
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { androidx.compose.runtime.mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp).padding(horizontal = 16.dp)) {
            Text(
                text = "Silent Mic",
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(200.dp),
                placeholder = { Text("Jot down rough notes, let Gemma structure them...") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onSubmit(text)
                    } else {
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Text("Process with AI")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePickerSheet(
    current: Language?,
    onPick: (Language?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "Dictation language",
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ListItem(
                headlineContent = { Text("Auto (phone language)") },
                supportingContent = {
                    Text("Uses your phone's language — pin one below if you dictate in another")
                },
                trailingContent =
                    if (current == null) {
                        { Text("✓", style = MaterialTheme.typography.titleMedium) }
                    } else {
                        null
                    },
                modifier = Modifier.clickable { onPick(null) },
            )
            CapturePinnableLanguages.forEach { lang ->
                ListItem(
                    headlineContent = { Text(languageDisplayName(lang)) },
                    supportingContent = { Text(lang.recognizerLocale) },
                    trailingContent =
                        if (current == lang) {
                            { Text("✓", style = MaterialTheme.typography.titleMedium) }
                        } else {
                            null
                        },
                    modifier = Modifier.clickable { onPick(lang) },
                )
            }
            Text(
                text =
                    "If offline dictation in your language returns nothing, install " +
                        "the matching language pack from Android Settings → System → " +
                        "Languages → Speech → Offline.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val CapturePinnableLanguages: List<Language> =
    listOf(
        Language.English,
        Language.Italian,
        Language.Spanish,
        Language.French,
        Language.German,
        Language.Portuguese,
    )

private fun languageDisplayName(language: Language): String =
    when (language) {
        Language.English -> "English"
        Language.Italian -> "Italiano"
        Language.Spanish -> "Español"
        Language.French -> "Français"
        Language.German -> "Deutsch"
        Language.Portuguese -> "Português"
        Language.Unknown -> "Auto"
    }

@Composable
private fun RecordingPane(
    state: CaptureUiState,
    padding: PaddingValues,
    onIntent: (CaptureUiIntent) -> Unit,
) {
    val isRecording = state.phase == CaptureUiState.Phase.Recording
    val transcriptScroll = rememberScrollState()
    // Keep the latest words in view as the transcript grows during long dictation.
    LaunchedEffect(state.partialTranscript) {
        if (transcriptScroll.maxValue > 0) {
            transcriptScroll.animateScrollTo(transcriptScroll.maxValue)
        }
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        LanguageChip(
            // When no language is pinned, "Auto" means the recognizer uses the phone's
            // system locale (SpeechRecognizer does not detect the spoken language). Show
            // that effective locale — e.g. "AUTO · EN" — so a multilingual user on an
            // English phone can see at a glance that Italian dictation needs pinning.
            label =
                state.activeLanguage?.recognizerLocale?.uppercase()
                    ?: "AUTO · ${java.util.Locale.getDefault().language.uppercase()}",
            onClick = { onIntent(CaptureUiIntent.OpenLanguagePicker) },
        )

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(transcriptScroll)
                    .padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text =
                    if (isRecording) {
                        state.partialTranscript.ifBlank { "Listening…" }
                    } else if (state.isAppending) {
                        "Tap the mic to append to note..."
                    } else {
                        "Tap the mic to capture your first thought."
                    },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            // Long-note advisory: Gemma E2B starts to feel slow past ~2000 chars of
            // transcript (≈ 3-4 min of dictation). Inference still completes within
            // the warm budget, but structuring quality degrades on context this long
            // because of the model's effective 2B-parameter size. The banner sets
            // expectations honestly instead of pretending nothing changed.
            if (isRecording && state.partialTranscript.length > LONG_NOTE_CHAR_THRESHOLD) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                ) {
                    Text(
                        text =
                            "Long note — structuring may take a bit longer and may " +
                                "simplify long stretches.",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // Anelli reattivi visibili solo durante la registrazione
                if (isRecording) {
                    PulseRings(rmsLevel = state.rmsLevel, isRecording = true)
                    PulseRings(rmsLevel = state.rmsLevel * 0.5f, isRecording = true)
                }

                FilledIconButton(
                    onClick = { onIntent(CaptureUiIntent.ToggleRecord) },
                    modifier = Modifier.size(112.dp),
                    shape = CircleShape,
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor =
                                if (isRecording) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                        ),
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Outlined.Stop else Icons.Outlined.Mic,
                        contentDescription = if (isRecording) "Stop recording" else "Start recording",
                        modifier = Modifier.size(48.dp),
                    )
                }
            }

            // Discard the in-progress take without structuring it. Shown only while
            // recording; a low-emphasis text button so Stop stays the single prominent
            // action (CLAUDE.md §8 recording UI).
            if (isRecording) {
                TextButton(
                    onClick = { onIntent(CaptureUiIntent.CancelRecording) },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Discard")
                }
            }
        }
    }
}

@Composable
private fun PulseRings(
    rmsLevel: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
) {
    // Normalizziamo l'RMS (da -2 a 12 dB in un range 0-1)
    val normalizedRms = ((rmsLevel + 2f) / 12f).coerceIn(0f, 1f)

    // Il targetScale decide quanto si "gonfia" il cerchio
    val targetScale = if (isRecording) 1.2f + (normalizedRms * 1.5f) else 1f
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        // Reattivo e veloce
        animationSpec = tween(durationMillis = 100),
        label = "pulse_scale",
    )

    // L'Alpha sfuma man mano che l'anello si allarga
    val targetAlpha = if (isRecording) (0.25f - (normalizedRms * 0.1f)).coerceAtLeast(0f) else 0f
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 100),
        label = "pulse_alpha",
    )

    Box(
        modifier =
            modifier
                .size(112.dp)
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                    alpha = animatedAlpha
                }
                .background(MaterialTheme.colorScheme.error, CircleShape),
    )
}

@Composable
private fun StructuringPane(
    padding: PaddingValues,
    startedAtMs: Long?,
    @Suppress("UNUSED_PARAMETER") estimatedSeconds: Int,
) {
    // Tick the elapsed counter every 500ms so the user has feedback that work is
    // actually progressing. Anchored to wall-clock `System.currentTimeMillis()`,
    // so when the screen turns off and back on, the displayed value catches up
    // to reality on the next tick. Resets when `startedAtMs` changes (e.g. a new
    // structuring call after Discard + retry).
    val elapsedSeconds by produceState(initialValue = 0, key1 = startedAtMs) {
        if (startedAtMs == null) {
            value = 0
            return@produceState
        }
        while (true) {
            value = ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt().coerceAtLeast(0)
            delay(500L)
        }
    }

    // Keep the screen on for the duration of this pane. Without it, an Android
    // screen-off → process-throttling chain was the dominant cause of cold-start
    // timeouts in the wild (see 2026-05-16 traces): structuring would slow to
    // a crawl while backgrounded and overshoot the budget, triggering a
    // plain-text fallback even though the engine itself was working correctly.
    // The flag is added when this Composable enters composition (i.e., we're in
    // Phase.Structuring) and removed when it leaves (we move to Reviewing /
    // Saved / Idle), so the screen stays awake only for the few seconds that
    // actually matter.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = "Structuring your note…",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            // Honest elapsed-only readout. We deliberately dropped the
            // "estimated ~Xs" half because the prior estimate routinely
            // undershot reality by 10-20s on cold-start CPU fallback runs —
            // and a wrong promise is worse than no promise. The user gets
            // accurate feedback ("38s elapsed") instead of false comfort.
            Text(
                text = "${elapsedSeconds}s elapsed",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text =
                    "Gemma 4 E2B is running locally on your device. Structuring " +
                        "time depends on your hardware (typically 20–90 s, longer on " +
                        "older phones without GPU acceleration). Your audio and " +
                        "transcript never leave the phone.",
                modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Vestigial estimator kept for the StructuringPane call-site signature. The
 * value isn't displayed anymore (see ADR 0014 / 2026-05-16 changelog) but
 * removing the parameter would touch every test and call site for no benefit.
 * Returns a generous fixed value so any downstream consumer that still reads
 * it doesn't crash on division-by-zero or similar.
 */
private fun estimateStructuringSeconds(transcriptLength: Int): Int =
    (15 + transcriptLength * 0.04).toInt().coerceIn(5, 150)

@Composable
private fun ReviewPane(
    state: CaptureUiState,
    padding: PaddingValues,
    onIntent: (CaptureUiIntent) -> Unit,
) {
    val note: Note = state.structuredPreview ?: return
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
    ) {
        if (state.structuringFailed) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Saved as plain text — auto-structuring is unavailable right now.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val raw = state.lastInferenceRaw
            if (!raw.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Last model response (debug)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = raw.take(MAX_DEBUG_RAW_CHARS),
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (!state.isAppending) {
            OutlinedTextField(
                value = note.title,
                onValueChange = { onIntent(CaptureUiIntent.EditTitle(it)) },
                label = { Text("Title") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
            )
        }
        OutlinedTextField(
            value = note.bodyMarkdown,
            onValueChange = { onIntent(CaptureUiIntent.EditBody(it)) },
            label = { Text("Body") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(280.dp),
        )
        if (note.tags.isNotEmpty()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                note.tags.forEach { tag ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = "#${tag.value}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        // On-device temporal reasoning made visible: each chip shows the surface form
        // Gemma saw and the ISO timestamp it anchored to (or "unresolved" when the
        // reference was intentionally too vague to anchor).
        MentionsSection(
            mentions = note.mentions,
            modifier = Modifier.padding(top = 16.dp),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            TextButton(onClick = { onIntent(CaptureUiIntent.DiscardPreview) }) {
                Text("Discard")
            }
            Button(
                onClick = { onIntent(CaptureUiIntent.Save) },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
            ) {
                Text("Save note")
            }
        }
    }
}

@Composable
private fun LanguageChip(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        modifier =
            Modifier
                .padding(top = 8.dp)
                .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = "Change dictation language",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// Cap the debug raw-response display so a runaway generation doesn't blow up the UI.
private const val MAX_DEBUG_RAW_CHARS = 4_000

// Roughly 3-4 minutes of normal-pace dictation. Past this point we surface a soft
// advisory about structuring latency + quality; see RecordingPane.
private const val LONG_NOTE_CHAR_THRESHOLD = 2_000
