package io.github.spectordev9.opencode.chat

import android.content.Context
import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** User-selected OpenAI-compatible model endpoint for the on-device server. */
data class LocalProviderSettings(
    val baseUrl: String = "",
    val modelId: String = "",
    val name: String = DEFAULT_NAME,
    val hasApiKey: Boolean = false,
) {
    val configured: Boolean
        get() = baseUrl.isNotBlank() && modelId.isNotBlank()

    companion object {
        const val DEFAULT_NAME = "Device provider"
    }
}

interface ProviderSettingsStore {
    val settings: StateFlow<LocalProviderSettings>

    /**
     * Saves an OpenAI-compatible endpoint. A null [apiKey] keeps the stored
     * key; a blank string removes it.
     */
    fun save(
        baseUrl: String,
        modelId: String,
        name: String = LocalProviderSettings.DEFAULT_NAME,
        apiKey: String? = null,
    ): ApiResult<LocalProviderSettings>

    /** Removes the custom endpoint and returns to the offline demo provider. */
    fun clear()

    /** Stored API key for config generation; never expose this to UI state. */
    fun apiKey(): String?
}

/**
 * Persists the custom provider configuration in app-private preferences.
 * The API key lives in a separate preference file and is never returned to UI
 * state or written to diagnostics.
 */
class SharedPrefsProviderSettingsStore(context: Context) : ProviderSettingsStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secretPrefs = context.applicationContext
        .getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    override val settings: StateFlow<LocalProviderSettings> = _settings.asStateFlow()

    private fun load(): LocalProviderSettings {
        val baseUrl = prefs.getString(KEY_BASE_URL, null).orEmpty()
        val modelId = prefs.getString(KEY_MODEL, null).orEmpty()
        val name = prefs.getString(KEY_NAME, null)
            ?.takeIf { it.isNotBlank() }
            ?: LocalProviderSettings.DEFAULT_NAME
        val configured = baseUrl.isNotBlank() && modelId.isNotBlank()
        return LocalProviderSettings(
            baseUrl = if (configured) baseUrl else "",
            modelId = if (configured) modelId else "",
            name = name,
            hasApiKey = secretPrefs.contains(KEY_API_KEY),
        )
    }

    override fun save(
        baseUrl: String,
        modelId: String,
        name: String,
        apiKey: String?,
    ): ApiResult<LocalProviderSettings> {
        val cleanBaseUrl = SharedPrefsServerProfileStore.validateProfileEndpoint(baseUrl)
            ?: return ApiResult.Failure(
                ApiError(
                    ErrorCode.E_PATH_DENIED,
                    "Base URL must use HTTPS for remote hosts or HTTP only for loopback",
                ),
            )
        val cleanModel = validateModelId(modelId)
            ?: return ApiResult.Failure(
                ApiError(ErrorCode.E_PATH_DENIED, "Model name is empty or contains invalid characters"),
            )
        val cleanName = name.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
            ?: LocalProviderSettings.DEFAULT_NAME
        val editor = prefs.edit()
            .putString(KEY_BASE_URL, cleanBaseUrl)
            .putString(KEY_MODEL, cleanModel)
            .putString(KEY_NAME, cleanName)
        if (apiKey != null) {
            val cleanKey = apiKey.trim()
            if (cleanKey.isEmpty()) {
                secretPrefs.edit().remove(KEY_API_KEY).apply()
            } else {
                secretPrefs.edit().putString(KEY_API_KEY, cleanKey).apply()
            }
        }
        if (!editor.commit()) {
            return ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "provider settings could not be saved"))
        }
        val updated = load()
        _settings.value = updated
        return ApiResult.Success(updated)
    }

    override fun clear() {
        prefs.edit().clear().apply()
        secretPrefs.edit().clear().apply()
        _settings.value = LocalProviderSettings()
    }

    override fun apiKey(): String? = secretPrefs.getString(KEY_API_KEY, null)

    companion object {
        const val PREFS_NAME = "opencode_provider_settings"
        const val SECRET_PREFS_NAME = "opencode_provider_secrets"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model_id"
        const val KEY_NAME = "display_name"
        const val KEY_API_KEY = "api_key"
        const val MAX_MODEL_LENGTH = 160
        const val MAX_NAME_LENGTH = 80

        fun validateModelId(raw: String?): String? {
            val model = raw?.trim() ?: return null
            if (model.isEmpty() || model.length > MAX_MODEL_LENGTH) return null
            if (model.any { it.isISOControl() || it == '\n' || it == '\r' }) return null
            return model
        }
    }
}

/**
 * Produces the OpenCode configuration for either the offline demo provider or
 * a user-configured OpenAI-compatible endpoint.
 */
internal fun buildProviderConfig(
    settings: LocalProviderSettings,
    apiKey: String?,
    mockBaseUrl: String,
): String {
    if (!settings.configured) return mockServerConfig(mockBaseUrl)
    val options = JSONObject()
        .put("baseURL", settings.baseUrl)
        .put("apiKey", apiKey.orEmpty())
    val model = JSONObject()
        .put("name", settings.modelId)
        .put("limit", JSONObject().put("context", 32768).put("output", 8192))
    val provider = JSONObject()
        .put("npm", "@ai-sdk/openai-compatible")
        .put("name", settings.name.ifBlank { LocalProviderSettings.DEFAULT_NAME })
        .put("options", options)
        .put("models", JSONObject().put(settings.modelId, model))
    return JSONObject()
        .put("model", "$CUSTOM_PROVIDER_ID/${settings.modelId}")
        .put("provider", JSONObject().put(CUSTOM_PROVIDER_ID, provider))
        .toString(2)
}

internal fun mockServerConfig(mockBaseUrl: String): String = JSONObject()
    .put("model", "$MOCK_PROVIDER_ID/mock")
    .put(
        "provider",
        JSONObject().put(
            MOCK_PROVIDER_ID,
            JSONObject()
                .put("npm", "@ai-sdk/openai-compatible")
                .put("name", "OpenCode Mobile on-device mock")
                .put("options", JSONObject().put("baseURL", mockBaseUrl).put("apiKey", "placeholder-not-a-secret"))
                .put(
                    "models",
                    JSONObject().put(
                        "mock",
                        JSONObject()
                            .put("name", "On-device mock model")
                            .put("limit", JSONObject().put("context", 8192).put("output", 1024)),
                    ),
                ),
        ),
    )
    .toString(2)

internal const val CUSTOM_PROVIDER_ID = "device"
internal const val MOCK_PROVIDER_ID = "opencode-mobile-mock"
internal const val PROVIDER_SETTINGS_NOTE =
    "Custom endpoints use an OpenAI-compatible /v1 base URL. The API key is stored in app-private storage."
