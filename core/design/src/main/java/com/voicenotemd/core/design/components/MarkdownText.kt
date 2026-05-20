package com.voicenotemd.core.design.components

import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

/**
 * A read-only Markdown renderer. Wraps a Markwon-driven TextView so that headings, lists,
 * code spans, tables, and task-list checkboxes show up as rich content rather than raw `*`
 * characters in the body of a note.
 *
 * Edits stay on a plain [androidx.compose.material3.OutlinedTextField] — Markwon's whole
 * design is render-side; we don't want to fight it on the input path.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = MaterialTheme.typography.bodyLarge.fontSize,
) {
    val context = LocalContext.current
    val color = LocalContentColor.current.toArgb()
    val sizeSp = fontSize.value

    val markwon =
        remember(context) {
            Markwon.builder(context)
                .usePlugin(TablePlugin.create(context))
                .usePlugin(TaskListPlugin.create(context))
                .build()
        }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                typeface = Typeface.DEFAULT
                markwon.setMarkdown(this, markdown)
            }
        },
        update = { textView ->
            textView.setTextColor(color)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            markwon.setMarkdown(textView, markdown)
        },
    )
}
