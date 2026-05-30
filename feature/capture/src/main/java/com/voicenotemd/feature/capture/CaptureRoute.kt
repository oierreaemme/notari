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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
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
    val fallbackMessage = stringResource(R.string.capture_fallback_snackbar)

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
                    snackbarHost.showSnackbar(fallbackMessage)
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
    // access. Stays alive through Preparing, Recording AND Transcribing — Preparing covers
    // the AudioRecord warm-up window (~700-1000 ms), so the FGS is already up by the time
    // the user actually starts dictating. Without it, the OS could (in theory) kill the
    // process mid-warm-up; including it also avoids a momentary "no service" flicker
    // visible in the notification shade during cold-start. See ADR 0018.
    LaunchedEffect(state.phase) {
        val captureActive =
            state.phase == CaptureUiState.Phase.Preparing ||
                state.phase == CaptureUiState.Phase.Recording ||
                state.phase == CaptureUiState.Phase.Transcribing
        if (captureActive) {
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
                title = { Text(stringResource(R.string.capture_app_name)) },
                actions = {
                    IconButton(onClick = { onIntent(CaptureUiIntent.ToggleTextInput) }) {
                        Icon(
                            Icons.Outlined.Keyboard,
                            contentDescription = stringResource(R.string.capture_cd_type_note),
                        )
                    }
                    IconButton(onClick = onOpenNotes) {
                        Icon(Icons.Outlined.Notes, contentDescription = stringResource(R.string.capture_cd_notes))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.capture_cd_settings))
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
            CaptureUiState.Phase.Transcribing ->
                TranscribingPane(padding = padding)
            CaptureUiState.Phase.Structuring ->
                StructuringPane(
                    padding = padding,
                    startedAtMs = state.structuringStartedAtMs,
                    estimatedSeconds = estimateStructuringSeconds(state.partialTranscript.length),
                )
            else ->
                RecordingPane(
                    state = state,
                    padding = padding,
                    onIntent = onIntent,
                    onOpenSettings = onOpenSettings,
                )
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
                text = stringResource(R.string.capture_sheet_title_silent_mic),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(200.dp),
                placeholder = { Text(stringResource(R.string.capture_text_input_placeholder)) },
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
                Text(stringResource(R.string.capture_btn_process_with_ai))
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
                text = stringResource(R.string.capture_sheet_title_dictation_language),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.capture_lang_auto_label)) },
                supportingContent = {
                    Text(stringResource(R.string.capture_lang_auto_description))
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

/**
 * Idle-screen banner shown when a model hasn't been imported yet (ADR 0022). Whisper
 * missing is the blocking case (no transcription at all); Gemma missing is advisory (notes
 * still save, as plain text). Tapping "Set up" sends the user to Settings → On-device models.
 */
@Composable
private fun SetupNeededBanner(
    whisperMissing: Boolean,
    gemmaMissing: Boolean,
    onOpenSettings: () -> Unit,
) {
    val message =
        when {
            whisperMissing && gemmaMissing ->
                "Import the transcription and structuring models to start. " +
                    "Until then, dictation won't produce text."
            whisperMissing ->
                "Import a transcription model to dictate. " +
                    "Without it, recordings can't be turned into text."
            else ->
                "Import the Gemma model for structured notes. " +
                    "Until then, notes are saved as plain text."
        }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.capture_btn_set_up)) }
        }
    }
}

@Composable
private fun RecordingPane(
    state: CaptureUiState,
    padding: PaddingValues,
    onIntent: (CaptureUiIntent) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val isRecording = state.phase == CaptureUiState.Phase.Recording
    // During Preparing we hold the same layout as Recording but render a subtler, more
    // honest version: no reactive PulseRings (the mic isn't "alive" yet), a Linear
    // indeterminate progress under the central text to communicate ongoing setup, and a
    // distinct copy ("Preparazione…") so the user knows not to start speaking yet. The
    // big button and the Discard text button still work — both route to cancelRecording
    // in the VM (see CaptureViewModel.handleToggleRecord).
    val isPreparing = state.phase == CaptureUiState.Phase.Preparing
    val isCaptureActive = isRecording || isPreparing
    val transcriptScroll = rememberScrollState()
    // Keep the latest words in view as the transcript grows during long dictation.
    LaunchedEffect(state.partialTranscript) {
        if (transcriptScroll.maxValue > 0) {
            transcriptScroll.animateScrollTo(transcriptScroll.maxValue)
        }
    }

    val preparingText = stringResource(R.string.capture_phase_preparing)
    val listeningText = stringResource(R.string.capture_listening)
    val tapToAppendText = stringResource(R.string.capture_tap_to_append)
    val tapToCaptureText = stringResource(R.string.capture_tap_to_capture)

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Setup nudge: only on the idle screen, and only when a model is actually missing.
        // Hidden during Preparing/Recording so it never competes with the live transcript.
        if (state.phase == CaptureUiState.Phase.Idle && state.setupNeeded) {
            SetupNeededBanner(
                whisperMissing = state.whisperModelMissing,
                gemmaMissing = state.gemmaModelMissing,
                onOpenSettings = onOpenSettings,
            )
        }

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
                    when {
                        isPreparing -> preparingText
                        isRecording -> state.partialTranscript.ifBlank { listeningText }
                        state.isAppending -> tapToAppendText
                        else -> tapToCaptureText
                    },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            // Discreet "we're warming up, please wait a moment" indicator. Indeterminate
            // because the warm-up duration is variable (~700-1000 ms on a Pixel 6a, longer
            // on some Bluetooth SCO paths). We deliberately do NOT estimate seconds — the
            // window is short enough that a counter would just add visual noise.
            if (isPreparing) {
                LinearProgressIndicator(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.4f)
                            .padding(top = 12.dp),
                )
                Text(
                    text = stringResource(R.string.capture_mic_stabilizing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
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
                        text = stringResource(R.string.capture_long_note_warning),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // Anelli reattivi visibili solo durante la registrazione vera. Durante
                // Preparing l'rmsLevel può oscillare per via dell'AGC che si stabilizza,
                // ma mostrarli sarebbe disonesto — implicherebbe che il mic stia già
                // catturando, mentre stiamo ancora aspettando il primo frame utile.
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
                            // Both Recording AND Preparing render the destructive (error)
                            // color so the user can tell at a glance "tapping this will
                            // stop / cancel". Only fully Idle shows the primary mic color.
                            containerColor =
                                if (isCaptureActive) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                        ),
                ) {
                    Icon(
                        imageVector = if (isCaptureActive) Icons.Outlined.Stop else Icons.Outlined.Mic,
                        contentDescription =
                            when {
                                isPreparing -> stringResource(R.string.capture_cd_cancel_preparing)
                                isRecording -> stringResource(R.string.capture_cd_stop_recording)
                                else -> stringResource(R.string.capture_cd_start_recording)
                            },
                        modifier = Modifier.size(48.dp),
                    )
                }
            }

            // Discard the in-progress take without structuring it. Shown during both
            // Preparing and Recording so the user always has a low-emphasis "abandon"
            // path (CLAUDE.md §8 recording UI). The big button does the same job, but
            // having an explicit "Discard" text affordance avoids relying on the user
            // recognising that the red button means cancel during Preparing.
            if (isCaptureActive) {
                TextButton(
                    onClick = { onIntent(CaptureUiIntent.CancelRecording) },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.capture_btn_discard))
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
                text = stringResource(R.string.capture_structuring_your_note),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            // Honest elapsed-only readout. We deliberately dropped the
            // "estimated ~Xs" half because the prior estimate routinely
            // undershot reality by 10-20s on cold-start CPU fallback runs —
            // and a wrong promise is worse than no promise. The user gets
            // accurate feedback ("38s elapsed") instead of false comfort.
            Text(
                text = stringResource(R.string.capture_structuring_elapsed, elapsedSeconds),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.capture_structuring_info),
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
@Composable
private fun TranscribingPane(padding: PaddingValues) {
    // Shown while whisper.cpp is turning the captured PCM into text (ADR 0018 phase 2).
    // Deliberately minimal — the meaningful state machine work happens behind the scenes;
    // this surface is just an honest "we're transcribing, then we'll structure" signal so
    // the user doesn't think the long Structuring step is what's slow.
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Text(
            text = stringResource(R.string.capture_phase_transcribing),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = stringResource(R.string.capture_transcribing_subtitle),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

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
                    text = stringResource(R.string.capture_structuring_failed_banner),
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
                label = { Text(stringResource(R.string.capture_label_title)) },
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
            label = { Text(stringResource(R.string.capture_label_body)) },
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
                Text(stringResource(R.string.capture_btn_discard))
            }
            Button(
                onClick = { onIntent(CaptureUiIntent.Save) },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
            ) {
                Text(stringResource(R.string.capture_btn_save_note))
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
                contentDescription = stringResource(R.string.capture_cd_change_language),
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
