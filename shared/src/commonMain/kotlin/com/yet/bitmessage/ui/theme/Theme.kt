package com.yet.bitmessage.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * bitMessage theme — the bitchat terminal-green Material3 scheme with monospace
 * typography. Platform chrome (status/navigation bar appearance) is handled by
 * the platform entry point, not here: common code stays Context-free.
 *
 * [darkTheme] defaults to the system setting; the settings-driven override
 * (ThemePreference) plugs in here once the settings feature lands.
 */
@Composable
fun BitMessageTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = BitMessageTypography,
        content = content,
    )
}
