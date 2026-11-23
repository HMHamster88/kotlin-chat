package org.kotlinchat.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.kotlinchat.client.settings.AppDensity
import org.kotlinchat.client.settings.AppTheme
import org.kotlinchat.client.settings.ClientSettings

/** Applies the theme before the first frame, and re-applies it as soon as settings change. */
@Composable
fun KotlinChatTheme(settings: ClientSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }
    val current = LocalDensity.current
    val fontScale = if (settings.density == AppDensity.COMPACT) current.fontScale * 0.92f else current.fontScale
    CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale)) {
        MaterialTheme(
            colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
            content = content,
        )
    }
}
