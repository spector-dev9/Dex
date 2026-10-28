package io.github.spectordev9.opencode.chat

import android.content.Context
import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A saved OpenCode server connection (F72). */
@Serializable
data class ServerProfile(
    val name: String,
    val url: String,
    val hasToken: Boolean = false,
    val active: Boolean = false,
    /** Server-side project directory; never resolved against the device filesystem (F73). */
    val directory: String = DEFAULT_REMOTE_DIRECTORY,
) {
    companion object {
        const val DEFAULT_REMOTE_DIRECTORY = "."
    }
}

/**
 * Persists named server endpoints (F72). Ordinary configuration (name, URL)
 * and optional access tokens are stored in separate preference files so
 * credentials never mix with ordinary configuration data.
 */
interface ServerProfileStore {
    val profiles: StateFlow<List<ServerProfile>>

    /** Validates and saves (or updates) a profile; the token is optional. */
    suspend fun add(
        name: String,
        url: String,
        token: String?,
        directory: String = ServerProfile.DEFAULT_REMOTE_DIRECTORY,
    ): ApiResult<ServerProfile>

    /** Marks exactly one profile active; every other profile becomes inactive. */
    suspend fun select(name: String): ApiResult<Unit>

    suspend fun remove(name: String): ApiResult<Unit>

    /** Removes every profile and its stored token; returns profiles removed (F78). */
    suspend fun clearAll(): Int

    /** Removes only stored tokens; returns tokens removed (F78). */
    suspend fun clearTokens(): Int

    /** Returns the stored token for [name], if any. Never expose it in the UI. */
    suspend fun tokenFor(name: String): String?
}

class SharedPrefsServerProfileStore(context: Context) : ServerProfileStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Credentials are kept in a dedicated preference file (F72). */
    private val tokenPrefs = context.getSharedPreferences(TOKEN_PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    private val _profiles = MutableStateFlow(loadProfiles())
    override val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    private fun loadProfiles(): List<ServerProfile> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ServerProfile.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<ServerProfile>) {
        prefs.edit()
            .putString(KEY_LIST, json.encodeToString(ListSerializer(ServerProfile.serializer()), list))
            .apply()
        _profiles.value = list
    }

    private fun persistTokens(list: List<ServerProfile>) {
        val names = list.map { it.name }.toSet()
        val toRemove = tokenPrefs.all.keys.filterNot { key ->
            names.any { profileName -> tokenKey(profileName) == key }
        }
        val editor = tokenPrefs.edit()
        toRemove.forEach(editor::remove)
        editor.apply()
    }

    override suspend fun add(
        name: String,
        url: String,
        token: String?,
        directory: String,
    ): ApiResult<ServerProfile> {
        val cleanName = validateProfileName(name)
            ?: return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "Profile name is empty or too long"))
        val cleanUrl = validateProfileEndpoint(url)
            ?: return ApiResult.Failure(
                ApiError(
                    ErrorCode.E_PATH_DENIED,
                    "URL must use HTTPS for remote hosts or HTTP only for loopback",
                ),
            )
        val cleanDirectory = validateRemoteDirectory(directory)
            ?: return ApiResult.Failure(
                ApiError(ErrorCode.E_PATH_DENIED, "Remote directory contains invalid characters"),
            )
        if (!prefs.contains(key(cleanName)) && _profiles.value.size >= MAX_PROFILES) {
            return ApiResult.Failure(ApiError(ErrorCode.E_TOO_LARGE, "Too many profiles (max $MAX_PROFILES)"))
        }
        val updated = _profiles.value
            .filterNot { it.name == cleanName }
            .plus(
                ServerProfile(
                    name = cleanName,
                    url = cleanUrl,
                    hasToken = !token.isNullOrBlank(),
                    active = false,
                    directory = cleanDirectory,
                ),
            )
            .sortedBy { it.name.lowercase(Locale.US) }
        persist(updated)
        val editor = tokenPrefs.edit()
        if (token.isNullOrBlank()) {
            editor.remove(tokenKey(cleanName))
        } else {
            editor.putString(tokenKey(cleanName), token)
        }
        editor.apply()
        return ApiResult.Success(updated.first { it.name == cleanName })
    }

    override suspend fun select(name: String): ApiResult<Unit> {
        if (_profiles.value.none { it.name == name }) {
            return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "Profile not found: $name"))
        }
        persist(_profiles.value.map { it.copy(active = it.name == name) })
        return ApiResult.Success(Unit)
    }

    override suspend fun remove(name: String): ApiResult<Unit> {
        val remaining = _profiles.value.filterNot { it.name == name }
        if (remaining.size == _profiles.value.size) {
            return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "Profile not found: $name"))
        }
        persist(remaining)
        persistTokens(remaining)
        return ApiResult.Success(Unit)
    }

    override suspend fun clearAll(): Int {
        val count = _profiles.value.size
        tokenPrefs.edit().clear().apply()
        persist(emptyList())
        return count
    }

    override suspend fun clearTokens(): Int {
        val count = tokenPrefs.all.size
        tokenPrefs.edit().clear().apply()
        _profiles.value = _profiles.value.map { it.copy(hasToken = false) }
        return count
    }

    override suspend fun tokenFor(name: String): String? = tokenPrefs.getString(tokenKey(name), null)

    private fun key(name: String): String = "profile_$name"

    private fun tokenKey(name: String): String = "token_$name"

    companion object {
        const val PREFS_NAME = "opencode_server_profiles"
        const val TOKEN_PREFS_NAME = "opencode_server_profile_tokens"
        const val KEY_LIST = "list"
        const val MAX_PROFILES = 24
        const val MAX_NAME_LENGTH = 64
        const val MAX_REMOTE_DIRECTORY_LENGTH = 512

        /** Trims and validates a profile name; null when invalid. */
        fun validateProfileName(raw: String?): String? {
            val name = raw?.trim() ?: return null
            if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return null
            return name
        }

        /**
         * Normalizes and validates a profile endpoint (F75). Cleartext HTTP is
         * only permitted for loopback endpoints; remote hosts must use HTTPS.
         */
        fun validateProfileEndpoint(raw: String?): String? {
            val normalized = normalizeProfileUrl(raw) ?: return null
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
            if (uri.scheme.equals("http", ignoreCase = true) && !isLoopbackHost(uri.host)) {
                return null
            }
            return normalized
        }

        /** Validates a remote server directory without treating it as a local path (F10/F73). */
        fun validateRemoteDirectory(raw: String?): String? {
            val directory = raw?.trim()?.takeIf { it.isNotEmpty() } ?: ServerProfile.DEFAULT_REMOTE_DIRECTORY
            if (directory.length > MAX_REMOTE_DIRECTORY_LENGTH) return null
            if (directory.any { it == '\u0000' || it.isISOControl() }) return null
            return directory
        }

        /** True for host names and literals that never leave the device. */
        fun isLoopbackHost(host: String?): Boolean {
            val value = host?.trim()?.lowercase(Locale.US)?.removeSurrounding("[", "]") ?: return false
            return value == "localhost" ||
                value == "::1" ||
                value == "10.0.2.2" ||
                value.startsWith("127.")
        }

        /** Normalizes an http(s) endpoint URL; null when it is not a valid endpoint. */
        fun normalizeProfileUrl(raw: String?): String? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val hasScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(text)
            val withScheme = if (hasScheme) text else "http://$text"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            val port = uri.port
            val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
            val authority = when {
                defaultPort -> host
                port > 0 -> "$host:$port"
                else -> host
            }
            val path = uri.path?.trimEnd('/') ?: ""
            return "$scheme://$authority$path"
        }
    }
}
