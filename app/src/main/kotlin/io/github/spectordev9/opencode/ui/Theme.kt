package io.github.spectordev9.opencode.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Extended palette for the app surfaces (F59). Field names intentionally
 * mirror the legacy constants so call sites read identically.
 */
data class OpencodePalette(
    val DarkBg: Color,
    val Panel: Color,
    val Panel2: Color,
    val Panel3: Color,
    val BorderCol: Color,
    val BorderStrong: Color,
    val TextCol: Color,
    val Muted: Color,
    val Muted2: Color,
    val Accent: Color,
    val AccentStrong: Color,
    val AccentSoft: Color,
    val Highlight: Color,
    val SuccessCol: Color,
    val WarningCol: Color,
    val DangerCol: Color,
    val UserBubbleBg: Color,
    val UserBubbleBorder: Color,
    val ToolChipBg: Color,
    val ToolChipBorder: Color,
    val CodeBg: Color,
    val CodeBorder: Color,
    val CodeText: Color,
    val InlineCodeText: Color,
    val TopbarBg: Color,
    val TerminalBlack: Color,
    val TerminalGreen: Color,
    val ErrorSurface: Color,
    val ErrorBorder: Color,
    val ErrorText: Color,
)

fun darkPalette(): OpencodePalette = OpencodePalette(
    DarkBg = Color(0xFF10111B),
    Panel = Color(0xFF191A29),
    Panel2 = Color(0xFF1E1F30),
    Panel3 = Color(0xFF24253A),
    BorderCol = Color(0xFF2B2C42),
    BorderStrong = Color(0xFF3D3E5A),
    TextCol = Color(0xFFF7F6FF),
    Muted = Color(0xFFA7A5BC),
    Muted2 = Color(0xFF8E8CA6),
    Accent = Color(0xFF8B72F8),
    AccentStrong = Color(0xFF7B60EF),
    AccentSoft = Color(0x248B72F8),
    Highlight = Color(0xFFC084FC),
    SuccessCol = Color(0xFF68D6A4),
    WarningCol = Color(0xFFE6B86B),
    DangerCol = Color(0xFFF08B9D),
    UserBubbleBg = Color(0xFF2A2456),
    UserBubbleBorder = Color(0xFF4D8274EA),
    ToolChipBg = Color(0xFF19191E),
    ToolChipBorder = Color(0xFF303038),
    CodeBg = Color(0xFF14151F),
    CodeBorder = Color(0xFF32334E),
    CodeText = Color(0xFFD8D6EA),
    InlineCodeText = Color(0xFFE6C07B),
    TopbarBg = Color(0xFF151518),
    TerminalBlack = Color(0xFF0B0E14),
    TerminalGreen = Color(0xFF68D6A4),
    ErrorSurface = Color(0x12DF7B7B),
    ErrorBorder = Color(0x52DF7B7B),
    ErrorText = Color(0xFFE8B7B7),
)

fun lightPalette(): OpencodePalette = OpencodePalette(
    DarkBg = Color(0xFFF6F5FC),
    Panel = Color(0xFFFFFFFF),
    Panel2 = Color(0xFFF2F1FA),
    Panel3 = Color(0xFFE9E7F5),
    BorderCol = Color(0xFFDDDBEE),
    BorderStrong = Color(0xFFB9B5D8),
    TextCol = Color(0xFF23233A),
    Muted = Color(0xFF5B5A75),
    Muted2 = Color(0xFF6F6E88),
    Accent = Color(0xFF6A4DF0),
    AccentStrong = Color(0xFF5938E8),
    AccentSoft = Color(0x1A6A4DF0),
    Highlight = Color(0xFF9333EA),
    SuccessCol = Color(0xFF18855B),
    WarningCol = Color(0xFFA16207),
    DangerCol = Color(0xFFD64560),
    UserBubbleBg = Color(0xFFE8E3FF),
    UserBubbleBorder = Color(0xFFC4B8F5),
    ToolChipBg = Color(0xFFF3F2FA),
    ToolChipBorder = Color(0xFFE2E1EE),
    CodeBg = Color(0xFFF6F5FC),
    CodeBorder = Color(0xFFDEDCF0),
    CodeText = Color(0xFF3A3A55),
    InlineCodeText = Color(0xFF9A6A00),
    TopbarBg = Color(0xFFF0EFF9),
    TerminalBlack = Color(0xFF0B0E14),
    TerminalGreen = Color(0xFF68D6A4),
    ErrorSurface = Color(0x14DF7B7B),
    ErrorBorder = Color(0x47DF7B7B),
    ErrorText = Color(0xFFB23348),
)

fun paletteFromScheme(scheme: ColorScheme): OpencodePalette = OpencodePalette(
    DarkBg = scheme.background,
    Panel = scheme.surface,
    Panel2 = scheme.surfaceContainer,
    Panel3 = scheme.surfaceContainerHigh,
    BorderCol = scheme.outlineVariant.copy(alpha = 0.6f),
    BorderStrong = scheme.outline,
    TextCol = scheme.onBackground,
    Muted = scheme.onSurfaceVariant,
    Muted2 = scheme.outline,
    Accent = scheme.primary,
    AccentStrong = scheme.primary,
    AccentSoft = scheme.primary.copy(alpha = 0.16f),
    Highlight = scheme.tertiary,
    SuccessCol = Color(0xFF3F9E7B),
    WarningCol = Color(0xFFC99A45),
    DangerCol = scheme.error,
    UserBubbleBg = scheme.primary.copy(alpha = 0.18f),
    UserBubbleBorder = scheme.primary.copy(alpha = 0.35f),
    ToolChipBg = scheme.surfaceContainer,
    ToolChipBorder = scheme.outlineVariant.copy(alpha = 0.5f),
    CodeBg = scheme.surfaceContainerLow,
    CodeBorder = scheme.outlineVariant,
    CodeText = scheme.onSurface,
    InlineCodeText = scheme.tertiary,
    TopbarBg = scheme.surface,
    TerminalBlack = Color(0xFF0B0E14),
    TerminalGreen = Color(0xFF68D6A4),
    ErrorSurface = scheme.error.copy(alpha = 0.08f),
    ErrorBorder = scheme.error.copy(alpha = 0.35f),
    ErrorText = scheme.error,
)

fun materialScheme(palette: OpencodePalette, dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = palette.Accent,
            onPrimary = Color.White,
            secondary = palette.AccentStrong,
            onSecondary = Color.White,
            tertiary = palette.Highlight,
            background = palette.DarkBg,
            surface = palette.Panel,
            onSurface = palette.TextCol,
            onBackground = palette.TextCol,
            surfaceVariant = palette.Panel3,
            onSurfaceVariant = palette.Muted,
            error = palette.DangerCol,
        )
    } else {
        lightColorScheme(
            primary = palette.Accent,
            onPrimary = Color.White,
            secondary = palette.AccentStrong,
            onSecondary = Color.White,
            tertiary = palette.Highlight,
            background = palette.DarkBg,
            surface = palette.Panel,
            onSurface = palette.TextCol,
            onBackground = palette.TextCol,
            surfaceVariant = palette.Panel3,
            onSurfaceVariant = palette.Muted,
            error = palette.DangerCol,
        )
    }

val LocalOpencodePalette = staticCompositionLocalOf { darkPalette() }

/** Resolves the effective palette from user prefs and the system setting. */
@Composable
fun rememberOpencodePalette(uiPrefs: UiPrefs?): OpencodePalette {
    val mode = uiPrefs?.themeMode?.collectAsState()?.value ?: UiPrefs.ThemeMode.SYSTEM
    val dynamic = uiPrefs?.dynamicColors?.collectAsState()?.value ?: false
    val dark = when (mode) {
        UiPrefs.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        UiPrefs.ThemeMode.LIGHT -> false
        UiPrefs.ThemeMode.DARK -> true
    }
    if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        return paletteFromScheme(if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))
    }
    return if (dark) darkPalette() else lightPalette()
}
