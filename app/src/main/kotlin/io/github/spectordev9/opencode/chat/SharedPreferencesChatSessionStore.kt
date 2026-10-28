package io.github.spectordev9.opencode.chat

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class SharedPreferencesChatSessionStore(context: Context) : ChatSessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun activeSessionId(directory: String): String? =
        preferences.getString(preferenceKey(directory), null)

    override fun setActiveSessionId(directory: String, sessionID: String?) {
        val editor = preferences.edit()
        if (sessionID == null) {
            editor.remove(preferenceKey(directory))
        } else {
            editor.putString(preferenceKey(directory), sessionID)
        }
        editor.apply()
    }

    override fun clearAll(): Int {
        val count = preferences.all.size
        preferences.edit().clear().apply()
        return count
    }

    private fun preferenceKey(directory: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(directory.toByteArray(StandardCharsets.UTF_8))
        return buildString(PREFERENCE_KEY_PREFIX.length + digest.size * 2) {
            append(PREFERENCE_KEY_PREFIX)
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "opencode_chat_sessions"
        const val PREFERENCE_KEY_PREFIX = "active_"
        const val HEX_DIGITS = "0123456789abcdef"
    }
}
