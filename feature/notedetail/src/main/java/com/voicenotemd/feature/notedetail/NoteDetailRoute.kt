package com.voicenotemd.feature.notedetail

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.design.components.MarkdownText
import com.voicenotemd.core.design.components.MentionsSection
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NoteDetailRoute(
    @Suppress("UNUSED_PARAMETER") noteId: String,
    onBack: () -> Unit,
    onAppendVoice: (String) -> Unit,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                NoteDetailUiEvent.Closed -> onBack()
                is NoteDetailUiEvent.ShareMarkdown -> {
                    val sendIntent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/markdown"
                            putExtra(Intent.EXTRA_TITLE, event.title)
                            putExtra(Intent.EXTRA_SUBJECT, event.title)
                            putExtra(Intent.EXTRA_TEXT, event.markdown)
                        }
                    context.startActivity(Intent.createChooser(sendIntent, event.title))
                }
                is NoteDetailUiEvent.NavigateToAppend -> onAppendVoice(event.noteId)
            }
        }
    }

    NoteDetailScreen(
        state = state,
        onBack = onBack,
        onIntent = viewModel::onIntent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteDetailScreen(
    state: NoteDetailUiState,
    onBack: () -> Unit,
    onIntent: (NoteDetailUiIntent) -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit note" else "Note") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.isEditing && state.note != null) {
                        IconButton(
                            onClick = { onIntent(NoteDetailUiIntent.Restructure) },
                            enabled = !state.isRestructuring,
                        ) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = "Structure with AI")
                        }
                        IconButton(onClick = { onIntent(NoteDetailUiIntent.Share) }) {
                            Icon(Icons.Outlined.Share, contentDescription = "Share as Markdown")
                        }
                        IconButton(onClick = { onIntent(NoteDetailUiIntent.AppendVoice) }) {
                            Icon(Icons.Outlined.Mic, contentDescription = "Append voice note")
                        }
                        IconButton(onClick = { onIntent(NoteDetailUiIntent.EnterEdit) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.notFound -> NotFoundView(padding)
            state.note != null ->
                NoteBody(
                    state = state,
                    padding = padding,
                    onIntent = onIntent,
                )
            else -> Box(modifier = Modifier.fillMaxSize().padding(padding)) {}
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this note?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onIntent(NoteDetailUiIntent.Delete)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun NoteBody(
    state: NoteDetailUiState,
    padding: PaddingValues,
    onIntent: (NoteDetailUiIntent) -> Unit,
) {
    val note: Note = state.note ?: return
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (state.isEditing) {
            OutlinedTextField(
                value = state.draftTitle,
                onValueChange = { onIntent(NoteDetailUiIntent.UpdateDraftTitle(it)) },
                label = { Text("Title") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.draftBody,
                onValueChange = { onIntent(NoteDetailUiIntent.UpdateDraftBody(it)) },
                label = { Text("Body") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(360.dp),
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(onClick = { onIntent(NoteDetailUiIntent.CancelEdit) }) {
                    Text("Cancel")
                }
                Button(onClick = { onIntent(NoteDetailUiIntent.SaveEdit) }) {
                    Text("Save")
                }
            }
        } else {
            // Plain-text note (structuring failed or was never run): offer an on-demand
            // "Structure with AI" retry. The text itself is already safe in bodyMarkdown;
            // this just upgrades it when the device is in better shape. See ADR 0022 follow-up.
            if (!note.structured) {
                RestructureBanner(
                    isRestructuring = state.isRestructuring,
                    error = state.restructureError,
                    onRestructure = { onIntent(NoteDetailUiIntent.Restructure) },
                )
            }
            Text(text = note.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.headlineSmall)
            Text(
                text =
                    "Created " +
                        note.createdAt
                            .atZone(ZoneId.systemDefault())
                            .format(DATE_FORMAT),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer,
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
            MarkdownText(
                markdown = note.bodyMarkdown,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
            )
            // Show the datetime mentions Gemma extracted, with their resolved ISO
            // timestamps formatted in the user's locale. Renders nothing when there
            // are no temporal references in the note.
            MentionsSection(
                mentions = note.mentions,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}

/**
 * Banner shown on a plain-text (unstructured) note offering an on-device "Structure with
 * AI" retry. Shows a spinner while running and an inline retry hint if it didn't succeed.
 */
@Composable
private fun RestructureBanner(
    isRestructuring: Boolean,
    error: String?,
    onRestructure: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "This note was saved as plain text — structuring didn't run.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            OutlinedButton(
                onClick = onRestructure,
                enabled = !isRestructuring,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                if (isRestructuring) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("Structuring…")
                } else {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.height(18.dp).padding(end = 8.dp),
                    )
                    Text("Structure with AI")
                }
            }
        }
    }
}

@Composable
private fun NotFoundView(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text("This note no longer exists.")
    }
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
