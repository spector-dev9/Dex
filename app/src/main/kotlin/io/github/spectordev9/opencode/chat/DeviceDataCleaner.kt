package io.github.spectordev9.opencode.chat

import android.content.Context
import java.io.File

/**
 * User-controlled data cleanup (F78): individually clears cached session
 * pointers, temporary files, saved server profiles or stored profile tokens.
 * The UI confirms each action before invoking these methods.
 */
class DeviceDataCleaner(
    private val context: Context,
    private val sessionStore: ChatSessionStore?,
    private val profiles: ServerProfileStore?,
) {
    /** Clears cached session pointers; returns entries removed. */
    fun clearCachedMessages(): Int = sessionStore?.clearAll() ?: 0

    /** Deletes everything under the app cache directory; returns bytes freed. */
    fun clearTemporaryFiles(): Long = deleteTree(context.cacheDir)

    /** Removes every saved server profile; returns profiles removed. */
    suspend fun clearServerProfiles(): Int = profiles?.clearAll() ?: 0

    /** Removes stored profile tokens only; returns tokens removed. */
    suspend fun clearStoredCredentials(): Int = profiles?.clearTokens() ?: 0

    companion object {
        /** Recursively deletes files under [root]; returns bytes freed. */
        fun deleteTree(root: File?): Long {
            if (root == null || !root.exists()) return 0L
            var freed = 0L
            root.listFiles()?.forEach { child ->
                freed += if (child.isDirectory) deleteTree(child) else child.length()
                child.delete()
            }
            return freed
        }
    }
}
