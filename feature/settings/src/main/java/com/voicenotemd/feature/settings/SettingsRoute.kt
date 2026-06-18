package com.voicenotemd.feature.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.repository.ModelImportCandidate
import com.voicenotemd.core.common.repository.OnDeviceModelStatus

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // One SAF launcher per model. OpenDocument lets the user pick from any provider
    // (Downloads, Drive, etc.) and hands us a content:// Uri with read access — perfect for
    // streaming a one-shot import. We read the display name + size up front so the VM/repo
    // can validate the pick before streaming a multi-GB file; the Android Uri never leaves
    // the Composable layer.
    val pickGemmaLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val candidate = context.contentResolver.modelImportCandidate(uri)
            viewModel.importModelFromStream(ManagedModel.Gemma, candidate) {
                context.contentResolver.openInputStream(uri)
            }
        }
    val pickWhisperLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val candidate = context.contentResolver.modelImportCandidate(uri)
            viewModel.importModelFromStream(ManagedModel.Whisper, candidate) {
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

    val allDeletedMsg = stringResource(R.string.settings_snackbar_all_deleted)
    LaunchedEffect(state.notesDeleted) {
        if (state.notesDeleted) {
            snackbar.showSnackbar(allDeletedMsg)
            viewModel.onIntent(SettingsUiIntent.AcknowledgeDeletion)
        }
    }
    LaunchedEffect(state.gemma.lastImportError) {
        val err = state.gemma.lastImportError ?: return@LaunchedEffect
        snackbar.showSnackbar(err)
        viewModel.onIntent(SettingsUiIntent.DismissImportError(ManagedModel.Gemma))
    }
    LaunchedEffect(state.whisper.lastImportError) {
        val err = state.whisper.lastImportError ?: return@LaunchedEffect
        snackbar.showSnackbar(err)
        viewModel.onIntent(SettingsUiIntent.DismissImportError(ManagedModel.Whisper))
    }

    SettingsScreen(
        state = state,
        snackbarHost = snackbar,
        onBack = onBack,
        onIntent = viewModel::onIntent,
        // Neither .litertlm nor ggml .bin has a registered MIME — pass octet-stream and
        // */* so providers show the file; the user filters by name.
        onPickGemma = { pickGemmaLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
        onPickWhisper = { pickWhisperLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
    )
}

/**
 * Best-effort read of the SAF document's display name and size via [OpenableColumns].
 * Either field may come back `null` (not all providers report them); the repository's
 * validation treats missing metadata as "proceed". Stays in the presentation layer so the
 * Android `Uri`/`ContentResolver` never leak into the ViewModel.
 */
private fun ContentResolver.modelImportCandidate(uri: Uri): ModelImportCandidate {
    var name: String? = null
    var size: Long? = null
    runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
    }
    return ModelImportCandidate(displayName = name, declaredSizeBytes = size)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    snackbarHost: SnackbarHostState,
    onBack: () -> Unit,
    onIntent: (SettingsUiIntent) -> Unit,
    onPickGemma: () -> Unit = {},
    onPickWhisper: () -> Unit = {},
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.settings_cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        SettingsContent(
            padding = padding,
            state = state,
            onIntent = onIntent,
            onPickGemma = onPickGemma,
            onPickWhisper = onPickWhisper,
        )
    }

    if (state.showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { onIntent(SettingsUiIntent.DismissDeleteAll) },
            title = { Text(stringResource(R.string.settings_delete_all_confirm_title)) },
            text = {
                Text(stringResource(R.string.settings_delete_all_confirm_text))
            },
            confirmButton = {
                TextButton(onClick = { onIntent(SettingsUiIntent.ConfirmDeleteAll) }) {
                    Text(stringResource(R.string.settings_btn_delete_everything))
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(SettingsUiIntent.DismissDeleteAll) }) {
                    Text(stringResource(R.string.settings_btn_cancel))
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
    onPickGemma: () -> Unit,
    onPickWhisper: () -> Unit,
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
        Section(title = stringResource(R.string.settings_section_on_device_models)) {
            Text(
                stringResource(R.string.settings_models_intro),
                style = MaterialTheme.typography.bodySmall,
            )
            OnDeviceModelRow(
                title = stringResource(R.string.settings_gemma_title),
                description = stringResource(R.string.settings_gemma_desc),
                section = state.gemma,
                importLabel = stringResource(R.string.settings_import_gemma),
                onPickModel = onPickGemma,
                onDeleteModel = { onIntent(SettingsUiIntent.DeleteModel(ManagedModel.Gemma)) },
            )
            OnDeviceModelRow(
                title = stringResource(R.string.settings_whisper_title),
                description = stringResource(R.string.settings_whisper_desc),
                section = state.whisper,
                importLabel = stringResource(R.string.settings_import_whisper),
                onPickModel = onPickWhisper,
                onDeleteModel = { onIntent(SettingsUiIntent.DeleteModel(ManagedModel.Whisper)) },
            )
        }

        Section(title = stringResource(R.string.settings_section_security)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_biometric_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (state.biometricUnavailable) {
                            stringResource(R.string.settings_biometric_desc_unavailable)
                        } else {
                            stringResource(R.string.settings_biometric_desc_available)
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

        Section(title = stringResource(R.string.settings_section_privacy)) {
            Text(
                stringResource(R.string.settings_privacy_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_privacy_verify),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.settings_permissions_label),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.settings_permission_record_audio),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section(title = stringResource(R.string.settings_section_language)) {
            Text(
                stringResource(R.string.settings_language_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            LanguagePicker(
                selected = state.forcedLanguage,
                onSelect = { onIntent(SettingsUiIntent.SetForcedLanguage(it)) },
            )
        }

        Section(title = stringResource(R.string.settings_section_danger_zone)) {
            Text(
                stringResource(R.string.settings_danger_zone_desc),
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
                Text(stringResource(R.string.settings_btn_delete_all_notes))
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
                label = { Text(stringResource(R.string.settings_lang_auto)) },
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

/**
 * One model's import row inside the "On-device models" section. Symmetric for Gemma and
 * whisper — status dot + label, a short description, and Import/Replace/Remove controls.
 */
@Composable
private fun OnDeviceModelRow(
    title: String,
    description: String,
    section: ModelSectionState,
    importLabel: String,
    onPickModel: () -> Unit,
    onDeleteModel: () -> Unit,
) {
    val present = section.status == OnDeviceModelStatus.Present
    val (statusLabel, statusColor) =
        if (present) {
            stringResource(R.string.settings_model_status_ready) to MaterialTheme.colorScheme.primary
        } else {
            stringResource(R.string.settings_model_status_not_imported) to MaterialTheme.colorScheme.error
        }
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(color = statusColor, shape = RoundedCornerShape(50)) {
                Box(modifier = Modifier.padding(5.dp)) {}
            }
            Text(text = statusLabel, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = description,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onPickModel,
                enabled = !section.isImporting,
                modifier = Modifier.weight(1f),
            ) {
                if (section.isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(stringResource(R.string.settings_phase_importing))
                } else {
                    Text(if (present) stringResource(R.string.settings_btn_replace) else importLabel)
                }
            }
            if (present) {
                OutlinedButton(
                    onClick = onDeleteModel,
                    enabled = !section.isImporting,
                ) { Text(stringResource(R.string.settings_btn_remove)) }
            }
        }
    }
}
