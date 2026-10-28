package io.github.spectordev9.opencode.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-interface preferences persisted in SharedPreferences (F59):
 * theme mode, optional dynamic colors and configurable font sizes for
 * chat text, code blocks and the terminal.
 */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class ThemeMode(val label: String) {
        SYSTEM("System"),
        LIGHT("Light"),
        DARK("Dark"),
    }

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColors = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, false))
    val dynamicColors: StateFlow<Boolean> = _dynamicColors.asStateFlow()

    private val _chatTextSize = MutableStateFlow(clamp(loadInt(KEY_CHAT_SIZE, DEFAULT_CHAT_SIZE), CHAT_MIN, CHAT_MAX))
    val chatTextSize: StateFlow<Int> = _chatTextSize.asStateFlow()

    private val _codeTextSize = MutableStateFlow(clamp(loadInt(KEY_CODE_SIZE, DEFAULT_CODE_SIZE), CODE_MIN, CODE_MAX))
    val codeTextSize: StateFlow<Int> = _codeTextSize.asStateFlow()

    private val _terminalTextSize =
        MutableStateFlow(clamp(loadInt(KEY_TERMINAL_SIZE, DEFAULT_TERMINAL_SIZE), TERMINAL_MIN, TERMINAL_MAX))
    val terminalTextSize: StateFlow<Int> = _terminalTextSize.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun setDynamicColors(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
        _dynamicColors.value = enabled
    }

    fun setChatTextSize(size: Int) {
        prefs.edit().putInt(KEY_CHAT_SIZE, clamp(size, CHAT_MIN, CHAT_MAX)).apply()
        _chatTextSize.value = clamp(size, CHAT_MIN, CHAT_MAX)
    }

    fun setCodeTextSize(size: Int) {
        prefs.edit().putInt(KEY_CODE_SIZE, clamp(size, CODE_MIN, CODE_MAX)).apply()
        _codeTextSize.value = clamp(size, CODE_MIN, CODE_MAX)
    }

    fun setTerminalTextSize(size: Int) {
        prefs.edit().putInt(KEY_TERMINAL_SIZE, clamp(size, TERMINAL_MIN, TERMINAL_MAX)).apply()
        _terminalTextSize.value = clamp(size, TERMINAL_MIN, TERMINAL_MAX)
    }

    private fun loadThemeMode(): ThemeMode =
        runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name).orEmpty())
        }.getOrDefault(ThemeMode.SYSTEM)

    private fun loadInt(key: String, fallback: Int): Int = prefs.getInt(key, fallback)

    private fun clamp(value: Int, min: Int, max: Int): Int = value.coerceIn(min, max)

    private companion object {
        const val PREFS_NAME = "opencode_ui_prefs"
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_colors"
        const val KEY_CHAT_SIZE = "chat_text_size"
        const val KEY_CODE_SIZE = "code_text_size"
        const val KEY_TERMINAL_SIZE = "terminal_text_size"

        const val DEFAULT_CHAT_SIZE = 14
        const val DEFAULT_CODE_SIZE = 12
        const val DEFAULT_TERMINAL_SIZE = 13

        const val CHAT_MIN = 12
        const val CHAT_MAX = 18
        const val CODE_MIN = 10
        const val CODE_MAX = 16
        const val TERMINAL_MIN = 10
        const val TERMINAL_MAX = 18
    }
}
