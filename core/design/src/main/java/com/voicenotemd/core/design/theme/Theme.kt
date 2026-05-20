package com.voicenotemd.core.design.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Brand fallback palette (used on devices < Android 12 without dynamic color).
private val BrandLight =
    lightColorScheme(
        primary = Color(0xFF4A5BD0),
        onPrimary = Color.White,
        secondary = Color(0xFF615CB7),
        background = Color(0xFFF8F9FF),
        surface = Color(0xFFFCFCFF),
    )

private val BrandDark =
    darkColorScheme(
        primary = Color(0xFFB6BFFF),
        onPrimary = Color(0xFF1B266E),
        secondary = Color(0xFFC5C2FF),
        background = Color(0xFF111318),
        surface = Color(0xFF14171E),
    )

@Composable
fun VoiceNoteMarkdownTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val ctx = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            }
            darkTheme -> BrandDark
            else -> BrandLight
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
