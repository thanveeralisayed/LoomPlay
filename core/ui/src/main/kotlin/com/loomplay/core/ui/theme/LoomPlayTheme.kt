package com.loomplay.core.ui.theme

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
import com.loomplay.core.model.ThemeMode

private val LoomLightColors = lightColorScheme(
    primary = Color(0xFF494BD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E0FF),
    onPrimaryContainer = Color(0xFF07006C),
    secondary = Color(0xFF006C4A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF6FFBBE),
    onSecondaryContainer = Color(0xFF002113),
    tertiary = Color(0xFF00687A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFACEDFF),
    onTertiaryContainer = Color(0xFF001F26),
    background = Color(0xFFF8F8FC),
    onBackground = Color(0xFF1A1A1F),
    surface = Color(0xFFF8F8FC),
    onSurface = Color(0xFF1A1A1F),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF464554),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F2F8),
    surfaceContainer = Color(0xFFECECF4),
    surfaceContainerHigh = Color(0xFFE5E5EE),
    surfaceContainerHighest = Color(0xFFDEDEE8),
    outline = Color(0xFF767586),
    outlineVariant = Color(0xFFC7C5D6),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
)

private val LoomDarkColors = darkColorScheme(
    primary = LoomColors.Primary,
    onPrimary = LoomColors.OnPrimary,
    primaryContainer = LoomColors.PrimaryContainer,
    onPrimaryContainer = LoomColors.OnPrimaryContainer,
    secondary = LoomColors.ExoEmerald,
    onSecondary = LoomColors.OnExo,
    secondaryContainer = LoomColors.ExoContainer,
    tertiary = LoomColors.MpvCyan,
    onTertiary = LoomColors.OnMpv,
    tertiaryContainer = LoomColors.MpvContainer,
    background = LoomColors.Surface,
    onBackground = LoomColors.OnSurface,
    surface = LoomColors.Surface,
    onSurface = LoomColors.OnSurface,
    surfaceVariant = LoomColors.SurfaceHighest,
    onSurfaceVariant = LoomColors.OnSurfaceVariant,
    surfaceContainerLowest = LoomColors.SurfaceLowest,
    surfaceContainerLow = LoomColors.SurfaceLow,
    surfaceContainer = LoomColors.SurfaceContainer,
    surfaceContainerHigh = LoomColors.SurfaceHigh,
    surfaceContainerHighest = LoomColors.SurfaceHighest,
    outline = LoomColors.Outline,
    outlineVariant = LoomColors.OutlineVariant,
    error = LoomColors.Error,
    onError = LoomColors.OnError,
    errorContainer = LoomColors.ErrorContainer,
)

@Composable
fun LoomPlayTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    amoledDarkMode: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> LoomDarkColors
        else -> LoomLightColors
    }

    val colorScheme = if (darkTheme && amoledDarkMode) {
        baseScheme.copy(
            background = LoomColors.Black,
            surface = LoomColors.Black,
            surfaceContainerLowest = LoomColors.Black,
            surfaceContainerLow = Color(0xFF0B0B0D),
            surfaceContainer = Color(0xFF121215),
        )
    } else {
        baseScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LoomTypography,
        content = content,
    )
}
