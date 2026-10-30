package io.github.spectordev9.opencode.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

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
    DarkBg = Color(0xFF07090B),
    Panel = Color(0xFF0D1114),
    Panel2 = Color(0xFF12171B),
    Panel3 = Color(0xFF192026),
    BorderCol = Color(0xFF232D33),
    BorderStrong = Color(0xFF35444C),
    TextCol = Color(0xFFF2F7F5),
    Muted = Color(0xFFA4B2AC),
    Muted2 = Color(0xFF6F8079),
    Accent = Color(0xFF7DF0C2),
    AccentStrong = Color(0xFF2BD79A),
    AccentSoft = Color(0x2E7DF0C2),
    Highlight = Color(0xFFF4C77A),
    SuccessCol = Color(0xFF7DF0C2),
    WarningCol = Color(0xFFF4C77A),
    DangerCol = Color(0xFFFF8393),
    UserBubbleBg = Color(0xFF10261F),
    UserBubbleBorder = Color(0x80408C70),
    ToolChipBg = Color(0xFF0F1417),
    ToolChipBorder = Color(0xFF253037),
    CodeBg = Color(0xFF0A0E10),
    CodeBorder = Color(0xFF223038),
    CodeText = Color(0xFFD7E3DE),
    InlineCodeText = Color(0xFFF4C77A),
    TopbarBg = Color(0xFF080B0D),
    TerminalBlack = Color(0xFF050708),
    TerminalGreen = Color(0xFF8CF5C8),
    ErrorSurface = Color(0x1AFF8393),
    ErrorBorder = Color(0x59FF8393),
    ErrorText = Color(0xFFFFB3BE),
)

fun lightPalette(): OpencodePalette = OpencodePalette(
    DarkBg = Color(0xFFF4F6F3),
    Panel = Color(0xFFFFFFFF),
    Panel2 = Color(0xFFEEF2EE),
    Panel3 = Color(0xFFE3EAE5),
    BorderCol = Color(0xFFD7E0DA),
    BorderStrong = Color(0xFFB6C4BC),
    TextCol = Color(0xFF111815),
    Muted = Color(0xFF4F5F57),
    Muted2 = Color(0xFF72837A),
    Accent = Color(0xFF087A57),
    AccentStrong = Color(0xFF056647),
    AccentSoft = Color(0xFFD9F2E7),
    Highlight = Color(0xFF9A5B00),
    SuccessCol = Color(0xFF087A57),
    WarningCol = Color(0xFF8A5A00),
    DangerCol = Color(0xFFC0364B),
    UserBubbleBg = Color(0xFFE2F5EC),
    UserBubbleBorder = Color(0xFFB9E0CE),
    ToolChipBg = Color(0xFFF1F5F2),
    ToolChipBorder = Color(0xFFDEE7E1),
    CodeBg = Color(0xFFF7FAF8),
    CodeBorder = Color(0xFFDCE5DF),
    CodeText = Color(0xFF24312B),
    InlineCodeText = Color(0xFF8A5500),
    TopbarBg = Color(0xFFF8FAF7),
    TerminalBlack = Color(0xFF080B0A),
    TerminalGreen = Color(0xFF8CF5C8),
    ErrorSurface = Color(0x14C0364B),
    ErrorBorder = Color(0x47C0364B),
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

fun materialScheme(palette: OpencodePalette, dark: Boolean): ColorScheme {
    val onAccent = if (dark) palette.DarkBg else Color.White
    return if (dark) {
        darkColorScheme(
            primary = palette.Accent,
            onPrimary = onAccent,
            secondary = palette.AccentStrong,
            onSecondary = onAccent,
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
            onPrimary = onAccent,
            secondary = palette.AccentStrong,
            onSecondary = onAccent,
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
}

val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

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
