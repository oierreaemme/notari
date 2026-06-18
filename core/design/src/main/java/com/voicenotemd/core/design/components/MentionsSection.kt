package com.voicenotemd.core.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicenotemd.core.common.domain.DateMention
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders the list of datetime mentions Gemma extracted from a note, with their
 * resolved ISO timestamps formatted in the user's locale.
 *
 * The intent is to make on-device temporal reasoning visible: every chip says
 * "the user said X → the model anchored it to Y". When a chip shows `null` it
 * means the model intentionally left the reference vague (e.g. "una di queste
 * sere") rather than inventing a date — that's the no-hallucination guarantee
 * in action.
 *
 * Renders nothing if [mentions] is empty.
 */
@Composable
fun MentionsSection(
    mentions: List<DateMention>,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    // Labels are passed in (rather than read from a resource here) so this design-system
    // component owns no string resources of its own — callers supply localised copy from
    // their own modules. Defaults keep the component usable standalone (previews/tests).
    header: String = "Datetime mentions",
    unresolvedLabel: String = "Left unresolved — reference too vague",
) {
    if (mentions.isEmpty()) return

    Column(modifier = modifier) {
        Text(
            text = header,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            mentions.forEach { mention ->
                MentionRow(mention, zone, locale, unresolvedLabel)
            }
        }
    }
}

@Composable
private fun MentionRow(
    mention: DateMention,
    zone: ZoneId,
    locale: Locale,
    unresolvedLabel: String,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint =
                    if (mention.resolved == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            )
            Column(
                modifier = Modifier.padding(start = 10.dp),
            ) {
                Text(
                    text = "“${mention.surfaceForm}”",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text =
                        mention.resolved?.let { formatInstant(it, zone, locale) }
                            ?: unresolvedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (mention.resolved == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
            }
        }
    }
}

/**
 * Format an [Instant] in the user's [locale]. If the instant lands on midnight in [zone]
 * (which is how we encode date-only ISO from Gemma — see `tryParseInstant` in
 * `StructureNoteUseCaseImpl`), drop the time-of-day component for readability.
 */
private fun formatInstant(
    instant: Instant,
    zone: ZoneId,
    locale: Locale,
): String {
    val zoned = instant.atZone(zone)
    val isDateOnly = zoned.hour == 0 && zoned.minute == 0 && zoned.second == 0
    val pattern = if (isDateOnly) "EEE d MMM yyyy" else "EEE d MMM yyyy · HH:mm"
    return DateTimeFormatter.ofPattern(pattern, locale).format(zoned)
}
