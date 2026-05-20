package com.voicenotemd.feature.notes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.domain.Tag
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun NotesRoute(
    onBack: () -> Unit,
    onNoteClick: (String) -> Unit,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            viewModel.exportToZip { context.contentResolver.openOutputStream(uri) }
        }

    LaunchedEffect(viewModel.uiEvents) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is NotesUiEvent.TriggerZipPicker -> {
                    val date =
                        java.time.LocalDate.now().format(
                            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"),
                        )
                    exportLauncher.launch("VoiceNotesExport_$date.zip")
                }
                is NotesUiEvent.ExportCompleted -> snackbarHost.showSnackbar(event.message)
                is NotesUiEvent.SelectionDeleted -> {
                    val msg = if (event.count == 1) "1 note deleted." else "${event.count} notes deleted."
                    snackbarHost.showSnackbar(msg)
                }
            }
        }
    }

    NotesScreen(
        state = state,
        snackbarHost = snackbarHost,
        onBack = onBack,
        onNoteClick = onNoteClick,
        onIntent = viewModel::onIntent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesScreen(
    state: NotesUiState,
    snackbarHost: SnackbarHostState,
    onBack: () -> Unit,
    onNoteClick: (String) -> Unit,
    onIntent: (NotesUiIntent) -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.isSelectionMode) "${state.selectedNoteIds.size} selected" else "Notes") },
                navigationIcon = {
                    if (state.isSelectionMode) {
                        IconButton(onClick = { onIntent(NotesUiIntent.ClearSelection) }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear selection")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (state.isSelectionMode) {
                        IconButton(onClick = { onIntent(NotesUiIntent.SelectAll) }) {
                            Icon(Icons.Outlined.DoneAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = { onIntent(NotesUiIntent.RequestExport) }) {
                            Icon(Icons.Outlined.Download, contentDescription = "Export selected notes")
                        }
                        IconButton(onClick = { onIntent(NotesUiIntent.RequestDeleteSelected) }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Delete selected notes",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchField(
                query = state.query,
                onChange = { onIntent(NotesUiIntent.UpdateQuery(it)) },
            )
            if (state.availableTags.isNotEmpty()) {
                TagFilterRow(
                    tags = state.availableTags,
                    activeTag = state.activeTag,
                    onSelect = { onIntent(NotesUiIntent.SelectTag(it)) },
                )
            }
            if (state.notes.isEmpty() && !state.isLoading) {
                EmptyState(query = state.query, activeTag = state.activeTag)
            } else {
                NotesList(
                    notes = state.notes,
                    isSelectionMode = state.isSelectionMode,
                    selectedIds = state.selectedNoteIds,
                    onNoteClick = onNoteClick,
                    onIntent = onIntent,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (state.showDeleteSelectedConfirm) {
        AlertDialog(
            onDismissRequest = { onIntent(NotesUiIntent.DismissDeleteSelected) },
            title = {
                Text(
                    text =
                        if (state.selectedNoteIds.size == 1) {
                            "Delete this note?"
                        } else {
                            "Delete ${state.selectedNoteIds.size} notes?"
                        },
                )
            },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { onIntent(NotesUiIntent.ConfirmDeleteSelected) }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(NotesUiIntent.DismissDeleteSelected) }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        placeholder = { Text("Search notes") },
        singleLine = true,
        // Search queries often start with proper nouns ("Marco", "Federico") —
        // sentence capitalization here matches what the user will have written
        // in the notes themselves (the title and body fields default to
        // Sentences capitalization too).
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun TagFilterRow(
    tags: List<Tag>,
    activeTag: Tag?,
    onSelect: (Tag?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
    ) {
        item(key = "all") {
            FilterChip(
                selected = activeTag == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
            )
        }
        items(items = tags, key = { it.value }) { tag ->
            FilterChip(
                selected = activeTag == tag,
                onClick = { onSelect(if (activeTag == tag) null else tag) },
                label = { Text("#${tag.value}") },
            )
        }
    }
}

@Composable
private fun NotesList(
    notes: List<Note>,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    onNoteClick: (String) -> Unit,
    onIntent: (NotesUiIntent) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = notes, key = { it.id }) { note ->
            NoteCard(
                note = note,
                isSelected = selectedIds.contains(note.id),
                onClick = {
                    if (isSelectionMode) {
                        onIntent(NotesUiIntent.ToggleSelection(note.id))
                    } else {
                        onNoteClick(note.id)
                    }
                },
                onLongClick = { onIntent(NotesUiIntent.ToggleSelection(note.id)) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: Note,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Date sits above the title as a small, muted timestamp so it doesn't
            // compete with the tag chips for horizontal space. We use a relative
            // smart format (HH:mm today, "Yesterday HH:mm", "Dow HH:mm" for this
            // week, "d MMM" or "d MMM yyyy" for older) so the most common case
            // (today's notes) fits in ~5 characters and never wraps. The previous
            // absolute layout could collapse to a 5-line vertical date column when
            // 3+ tags pushed it sideways; this puts it on its own row up top.
            Text(
                text = formatRelativeTimestamp(note.createdAt, ZoneId.systemDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = note.title.ifBlank { "Untitled" }.take(60),
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val snippet =
                note.bodyMarkdown.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
            if (snippet.isNotBlank()) {
                Text(
                    text = snippet,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (note.tags.isNotEmpty()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    note.tags.take(3).forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = "#${tag.value}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Smart relative timestamp for note list cards. Optimized for "the most
 * recent note is the one the user opens": today's notes show only "HH:mm"
 * so the line stays narrow and never wraps, while older notes degrade
 * gracefully through more informative formats as the recency drops.
 *
 *  - Today                     → "HH:mm"           e.g. "08:37"
 *  - Yesterday                 → "Yesterday HH:mm" e.g. "Yesterday 22:14"
 *  - 2-6 days ago (this week)  → "Dow HH:mm"       e.g. "Sat 18:02"
 *  - Same year, older          → "d MMM"           e.g. "12 Apr"
 *  - Different year            → "d MMM yyyy"      e.g. "3 Nov 2024"
 *
 * UI is English-only in v1 (per CLAUDE.md §5 — UI localization is roadmap).
 * The dictation content can be any of the 6 supported languages; the
 * "Yesterday" / weekday abbreviations stay in English.
 */
internal fun formatRelativeTimestamp(
    timestamp: Instant,
    zone: ZoneId,
    now: Instant = Instant.now(),
): String {
    val noteZdt = timestamp.atZone(zone)
    val nowZdt = now.atZone(zone)
    val noteDate = noteZdt.toLocalDate()
    val today: LocalDate = nowZdt.toLocalDate()
    val daysBetween = ChronoUnit.DAYS.between(noteDate, today)

    return when {
        daysBetween == 0L -> noteZdt.format(TIME_ONLY)
        daysBetween == 1L -> "Yesterday " + noteZdt.format(TIME_ONLY)
        daysBetween in 2..6 -> noteZdt.format(WEEKDAY_TIME)
        noteDate.year == today.year -> noteZdt.format(DATE_NO_YEAR)
        else -> noteZdt.format(DATE_WITH_YEAR)
    }
}

private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val WEEKDAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")
private val DATE_NO_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val DATE_WITH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
private fun EmptyState(
    query: String,
    activeTag: Tag?,
) {
    val message =
        when {
            query.isNotBlank() -> "No notes match \"$query\"."
            activeTag != null -> "No notes tagged #${activeTag.value} yet."
            else -> "Tap the mic to capture your first thought."
        }
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
    }
}
