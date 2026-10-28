package io.github.spectordev9.opencode.chat

import android.content.Context
import io.github.spectordev9.opencode.core.opencode.client.PermissionResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Client-side record of per-tool permission choices (F39).
 *
 * The server is the authority: rules here only (a) remember answers the user
 * explicitly gave via "Always allow" and (b) let the client pre-answer the
 * same prompt the next time the server asks. Nothing here bypasses or
 * overrides server-side policy; the settings screen presents them as
 * "remembered choices", not as enforced restrictions.
 */
interface PermissionRuleStore {
    val rules: StateFlow<Map<String, PermissionResponse>>

    fun ruleFor(tool: String): PermissionResponse?

    fun setRule(tool: String, response: PermissionResponse)

    fun clearRule(tool: String)
}

class SharedPrefsPermissionRuleStore(context: Context) : PermissionRuleStore {
    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _rules = MutableStateFlow(loadRules())
    override val rules: StateFlow<Map<String, PermissionResponse>> = _rules.asStateFlow()

    override fun ruleFor(tool: String): PermissionResponse? = rules.value[tool]

    override fun setRule(tool: String, response: PermissionResponse) {
        val normalized = tool.trim().lowercase()
        if (normalized.isEmpty()) return
        prefs.edit().putString(ruleKey(normalized), response.name).apply()
        _rules.update { it + (normalized to response) }
    }

    override fun clearRule(tool: String) {
        val normalized = tool.trim().lowercase()
        prefs.edit().remove(ruleKey(normalized)).apply()
        _rules.update { it - normalized }
    }

    private fun loadRules(): Map<String, PermissionResponse> {
        val all = prefs.all
        return all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { key ->
                val tool = key.removePrefix(KEY_PREFIX)
                val value = all[key] as? String
                val response = value?.let {
                    runCatching { PermissionResponse.valueOf(it) }.getOrNull()
                }
                if (tool.isNotEmpty() && response != null) tool to response else null
            }
            .toMap()
    }

    private fun ruleKey(tool: String) = KEY_PREFIX + tool

    private companion object {
        const val PREFS_NAME = "opencode.permission.rules"
        const val KEY_PREFIX = "rule."
    }
}

/**
 * Flags shell commands that destroy data on a large scale (F40). Used only to
 * trigger extra confirmation in the UI — the server still decides whether to
 * run the tool at all.
 */
object DestructiveCommandDetector {
    private val patterns = listOf(
        Regex("""(^|[\s;&(])rm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+|--recursive|--force)"""),
        Regex("""git\s+push\b.*\s(-f\b|--force(?![-\w]))"""),
        Regex("""git\s+reset\s+--hard\b"""),
        Regex("""git\s+clean\b.*(-[a-zA-Z]*f|-\w*f)"""),
        Regex("""git\s+checkout\s+--\s+\.?\*?\s*$"""),
        Regex("""(^|[\s;&(])sudo\b"""),
        Regex("""(^|[\s;&(])dd\s+if="""),
        Regex("""(^|[\s;&(])mkfs(\.\w+)?\b"""),
        Regex("""(^|[\s;&(])shred\b"""),
        Regex("""(^|[\s;&(])truncate\s+-s\s*0"""),
        Regex("""curl\b[^|]*\|\s*(sudo\s+)?(ba|z|)sh\b"""),
        Regex("""wget\b[^|]*\|\s*(sudo\s+)?(ba|z|)sh\b"""),
        Regex("""DROP\s+(TABLE|DATABASE|SCHEMA)\b""", RegexOption.IGNORE_CASE),
        Regex("""TRUNCATE\s+TABLE\b""", RegexOption.IGNORE_CASE),
        Regex("""(^|[\s;&(])> */dev/[shv]d[a-z]"""),
        Regex("""chmod\s+(-\w+\s+)*-R\b"""),
        Regex("""chown\s+(-\w+\s+)*-R\b"""),
    )

    /** Returns the first matching pattern source, or null when not flagged. */
    fun match(command: String): String? {
        if (command.isBlank()) return null
        return patterns.firstOrNull { it.containsMatchIn(command) }?.pattern
    }
}
