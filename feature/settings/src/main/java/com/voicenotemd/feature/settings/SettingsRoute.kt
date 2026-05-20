package com.voicenotemd.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.repository.OnDeviceModelStatus

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val pickModelLauncher =
        rememberLauncherForActivityResult(
            // OpenDocument lets the user pick from any provider (Downloads, Drive, etc.) and
            // hands us a content:// Uri with persistable read access — perfect for streaming
            // a one-shot import without holding a permanent grant.
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            viewModel.importModelFromStream {
                context.contentResolver.openInputStream(uri)
            }
        }

    // Report platform-side biometric availability to the VM exactly once on entry.
    // We use BIOMETRIC_STRONG so face/fingerprint hardware that doesn't meet Class 3
    // (e.g. some on-display sensors at unlock time) cleanly degrade to "unavailable"
    // instead of letting the user enable a toggle that won't actually gate the app.
    LaunchedEffect(Unit) {
        val canAuth =
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        viewModel.onBiometricAvailability(canAuth == BiometricManager.BIOMETRIC_SUCCESS)
    }

    LaunchedEffect(state.notesDeleted) {
        if (state.notesDeleted) {
            snackbar.showSnackbar("All notes were deleted.")
            viewModel.onIntent(SettingsUiIntent.AcknowledgeDeletion)
        }
    }
    LaunchedEffect(state.lastImportError) {
        val err = state.lastImportError ?: return@LaunchedEffect
        snackbar.showSnackbar("Couldn't import the model: $err")
        viewModel.onIntent(SettingsUiIntent.DismissImportError)
    }

    SettingsScreen(
        state = state,
        snackbarHost = snackbar,
        onBack = onBack,
        onIntent = viewModel::onIntent,
        onPickModel = {
            // The .litertlm extension does not have a registered MIME — pass octet-stream
            // and let the user filter by name. Some providers honour the */* fallback.
            pickModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    snackbarHost: SnackbarHostState,
    onBack: () -> Unit,
    onIntent: (SettingsUiIntent) -> Unit,
    onPickModel: () -> Unit = {},
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        SettingsContent(
            padding = padding,
            state = state,
            onIntent = onIntent,
            onPickModel = onPickModel,
        )
    }

    if (state.showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { onIntent(SettingsUiIntent.DismissDeleteAll) },
            title = { Text("Delete every note?") },
            text = {
                Text(
                    "This deletes all notes from your device permanently. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onIntent(SettingsUiIntent.ConfirmDeleteAll) }) {
                    Text("Delete everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(SettingsUiIntent.DismissDeleteAll) }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SettingsContent(
    padding: PaddingValues,
    state: SettingsUiState,
    onIntent: (SettingsUiIntent) -> Unit,
    onPickModel: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        OnDeviceModelSection(
            status = state.modelStatus,
            isImporting = state.isImportingModel,
            onPickModel = onPickModel,
            onDeleteModel = { onIntent(SettingsUiIntent.DeleteModel) },
        )

        Section(title = "Security") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Require biometric unlock",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (state.biometricUnavailable) {
                            "Set up a fingerprint or face unlock in Android Settings " +
                                "to enable this."
                        } else {
                            "Ask for your fingerprint or face every time the app opens. " +
                                "Notes stay private even when your phone is unlocked."
                        },
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.requireBiometricUnlock,
                    enabled = !state.biometricUnavailable,
                    onCheckedChange = {
                        onIntent(SettingsUiIntent.SetRequireBiometricUnlock(it))
                    },
                )
            }
        }

        Section(title = "Privacy") {
            Text(
                "Notari does not request the INTERNET permission and " +
                    "makes zero network calls. Audio is held only in RAM during a " +
                    "recording and is overwritten the moment transcription completes — " +
                    "no audio file ever touches disk.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Verify with adb: adb shell dumpsys package com.voicenotemd " +
                    "| grep \"permission.INTERNET\" returns nothing.",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Permissions used:",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "• RECORD_AUDIO — needed to capture your voice. We never write the " +
                    "buffer to a file.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section(title = "Language") {
            Text(
                "By default we let the recognizer auto-detect the language. Pin a " +
                    "language here if auto-detection is unreliable on your device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            LanguagePicker(
                selected = state.forcedLanguage,
                onSelect = { onIntent(SettingsUiIntent.SetForcedLanguage(it)) },
            )
        }

        Section(title = "Danger zone") {
            Text(
                "Permanently delete every note stored on this device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { onIntent(SettingsUiIntent.RequestDeleteAll) },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                modifier =
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(),
            ) {
                Text("Delete all notes")
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Column(modifier = Modifier.padding(top = 8.dp)) { content() }
        }
    }
}

@Composable
private fun LanguagePicker(
    selected: Language?,
    onSelect: (Language?) -> Unit,
) {
    LazyRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "auto") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("Auto") },
            )
        }
        items(count = PinnableLanguages.size) { index ->
            val lang = PinnableLanguages[index]
            FilterChip(
                selected = selected == lang,
                onClick = { onSelect(if (selected == lang) null else lang) },
                label = { Text(lang.bcp47.uppercase()) },
            )
        }
    }
}

@Composable
private fun OnDeviceModelSection(
    status: OnDeviceModelStatus,
    isImporting: Boolean,
    onPickModel: () -> Unit,
    onDeleteModel: () -> Unit,
) {
    Section(title = "On-device model") {
        val (statusLabel, statusColor) =
            when (status) {
                OnDeviceModelStatus.Present ->
                    "Ready" to MaterialTheme.colorScheme.primary
                OnDeviceModelStatus.Missing ->
                    "Not imported — notes are saved as plain text" to
                        MaterialTheme.colorScheme.error
            }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(end = 4.dp)
                        .let { mod ->
                            // simple status dot
                            mod
                        },
            ) {
                Surface(
                    color = statusColor,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Box(modifier = Modifier.padding(6.dp)) {}
                }
            }
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text =
                "The Gemma 4 E2B model (~1.5 GB, .litertlm) lives entirely on your " +
                    "device. Pick the file you downloaded from Google AI — we copy it into " +
                    "private storage and never touch the network.",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onPickModel,
                enabled = !isImporting,
                modifier = Modifier.weight(1f),
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("Importing…")
                } else {
                    Text(
                        if (status == OnDeviceModelStatus.Present) {
                            "Replace model"
                        } else {
                            "Import .litertlm"
                        },
                    )
                }
            }
            if (status == OnDeviceModelStatus.Present) {
                OutlinedButton(
                    onClick = onDeleteModel,
                    enabled = !isImporting,
                ) { Text("Remove") }
            }
        }
    }
}
