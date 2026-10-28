package io.github.spectordev9.opencode.features

import android.content.Context
import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.SecretRef
import io.github.spectordev9.opencode.core.security.SecretStore
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Project-scoped environment variables for build/test commands (F54).
 *
 * Only variable names are persisted in ordinary preferences; values live in
 * the Android Keystore-backed [SecretStore] and are never rendered by the UI
 * or written into the workspace, so they cannot leak through source control.
 */
class ProjectEnvironmentStore(
    context: Context,
    private val secrets: SecretStore,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun names(directory: String): List<String> {
        val raw = prefs.getString(key(directory), null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrDefault(emptyList())
            .filter { validateName(it) != null }
            .distinct()
            .sorted()
    }

    suspend fun set(directory: String, name: String, value: String): ApiResult<Unit> {
        val cleanName = validateName(name)
            ?: return failure("Environment name must match [A-Za-z_][A-Za-z0-9_]*")
        if (!validateValue(value)) return failure("Environment value is empty or too large")
        val result = secrets.put(secretRef(directory, cleanName), value.toCharArray())
        if (result is ApiResult.Failure) return result
        val updated = (names(directory) + cleanName).distinct().sorted()
        persist(directory, updated)
        return ApiResult.Success(Unit)
    }

    suspend fun remove(directory: String, name: String): ApiResult<Unit> {
        val cleanName = validateName(name) ?: return failure("Invalid environment name")
        secrets.delete(secretRef(directory, cleanName))
        persist(directory, names(directory).filterNot { it == cleanName })
        return ApiResult.Success(Unit)
    }

    suspend fun clear(directory: String): ApiResult<Unit> {
        names(directory).forEach { name -> secrets.delete(secretRef(directory, name)) }
        prefs.edit().remove(key(directory)).apply()
        return ApiResult.Success(Unit)
    }

    /** Resolves values for a child process; callers must not persist or display them. */
    suspend fun resolve(directory: String): Map<String, String> = withContext(Dispatchers.IO) {
        buildMap {
            names(directory).forEach { name ->
                val ref = secretRef(directory, name)
                val result = secrets.get(ref)
                if (result is ApiResult.Success) {
                    val chars = result.value
                    try {
                        put(name, String(chars))
                    } finally {
                        chars.fill('\u0000')
                    }
                }
            }
        }
    }

    private fun persist(directory: String, names: List<String>) {
        prefs.edit()
            .putString(
                key(directory),
                json.encodeToString(ListSerializer(String.serializer()), names),
            )
            .apply()
    }

    private fun key(directory: String): String = "project_${digest(directory)}"

    private fun secretRef(directory: String, name: String): SecretRef =
        SecretRef("opencode.project.env.${digest(directory)}.$name")

    private fun digest(directory: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(directory.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
            .take(24)

    private fun failure(message: String): ApiResult.Failure =
        ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, message))

    companion object {
        const val PREFS_NAME = "opencode_project_environment"
        const val MAX_NAME_LENGTH = 64
        const val MAX_VALUE_LENGTH = 8192

        private val NAME_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun validateName(raw: String?): String? {
            val name = raw?.trim() ?: return null
            if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return null
            return name.takeIf { NAME_PATTERN.matches(it) }
        }

        fun validateValue(value: String): Boolean =
            value.isNotEmpty() &&
                value.length <= MAX_VALUE_LENGTH &&
                value.none { it == '\u0000' || it == '\n' || it == '\r' }
    }
}
