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
    DarkBg = Color(0xFF060A0F),
    Panel = Color(0xFF0B1218),
    Panel2 = Color(0xFF111A22),
    Panel3 = Color(0xFF18242D),
    BorderCol = Color(0xFF22323D),
    BorderStrong = Color(0xFF3A4E5C),
    TextCol = Color(0xFFF1F6F7),
    Muted = Color(0xFFA7B8BD),
    Muted2 = Color(0xFF7C9098),
    Accent = Color(0xFF7DE8D2),
    AccentStrong = Color(0xFF2CC9AC),
    AccentSoft = Color(0x2E7DE8D2),
    Highlight = Color(0xFFF3C97D),
    SuccessCol = Color(0xFF7DE8D2),
    WarningCol = Color(0xFFF3C97D),
    DangerCol = Color(0xFFFF8C9B),
    UserBubbleBg = Color(0xFF0D2528),
    UserBubbleBorder = Color(0x80408F80),
    ToolChipBg = Color(0xFF0E151C),
    ToolChipBorder = Color(0xFF253440),
    CodeBg = Color(0xFF080E14),
    CodeBorder = Color(0xFF20313C),
    CodeText = Color(0xFFD6E4E2),
    InlineCodeText = Color(0xFFF3C97D),
    TopbarBg = Color(0xFF070C12),
    TerminalBlack = Color(0xFF04070B),
    TerminalGreen = Color(0xFF89F3CC),
    ErrorSurface = Color(0x1AFF8C9B),
    ErrorBorder = Color(0x59FF8C9B),
    ErrorText = Color(0xFFFFB6C0),
)

fun lightPalette(): OpencodePalette = OpencodePalette(
    DarkBg = Color(0xFFF1F5F4),
    Panel = Color(0xFFFFFFFF),
    Panel2 = Color(0xFFEAF1EF),
    Panel3 = Color(0xFFDEE8E4),
    BorderCol = Color(0xFFD0DDD8),
    BorderStrong = Color(0xFFAEC2BA),
    TextCol = Color(0xFF0E1714),
    Muted = Color(0xFF435851),
    Muted2 = Color(0xFF556A63),
    Accent = Color(0xFF056B57),
    AccentStrong = Color(0xFF045B4A),
    AccentSoft = Color(0xFFD5F0E7),
    Highlight = Color(0xFF935600),
    SuccessCol = Color(0xFF056B57),
    WarningCol = Color(0xFF855500),
    DangerCol = Color(0xFFBC3348),
    UserBubbleBg = Color(0xFFDDF3EA),
    UserBubbleBorder = Color(0xFFAEDBC9),
    ToolChipBg = Color(0xFFEFF4F2),
    ToolChipBorder = Color(0xFFD9E4DF),
    CodeBg = Color(0xFFF7FAF9),
    CodeBorder = Color(0xFFD7E3DE),
    CodeText = Color(0xFF22312B),
    InlineCodeText = Color(0xFF855500),
    TopbarBg = Color(0xFFF7FAF8),
    TerminalBlack = Color(0xFF070B09),
    TerminalGreen = Color(0xFF89F3CC),
    ErrorSurface = Color(0x14BC3348),
    ErrorBorder = Color(0x47BC3348),
    ErrorText = Color(0xFFA92C40),
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
