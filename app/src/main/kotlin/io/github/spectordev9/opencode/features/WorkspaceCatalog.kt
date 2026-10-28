package io.github.spectordev9.opencode.features

import android.content.Context
import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale

data class ProjectWorkspace(
    val id: String,
    val name: String,
    val directory: String,
    val active: Boolean,
)

/**
 * Local project folders inside the server workspace (F10/F44).
 *
 * A selected folder is persisted by identifier and revalidated on every
 * startup; a missing or unsafe folder falls back to the server root rather
 * than silently exposing an arbitrary path. Remote server paths are never
 * treated as device-local paths.
 */
class WorkspaceCatalog(
    context: Context,
    private val serverWorkspace: Path,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun rootDirectory(): Path = serverWorkspace.toAbsolutePath().normalize()

    fun list(): List<ProjectWorkspace> {
        val root = rootDirectory()
        val activeId = selectedId()
        val default = ProjectWorkspace(
            id = DEFAULT_ID,
            name = "Workspace root",
            directory = root.toString(),
            active = activeId == null || activeId == DEFAULT_ID,
        )
        val children = runCatching {
            Files.list(root).use { stream ->
                stream
                    .filter { path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) }
                    .filter { path -> !Files.isSymbolicLink(path) }
                    .filter { path -> !path.fileName.toString().startsWith(".") }
                    .limit(MAX_PROJECTS.toLong())
                    .map { path ->
                        ProjectWorkspace(
                            id = path.fileName.toString(),
                            name = path.fileName.toString(),
                            directory = path.toAbsolutePath().normalize().toString(),
                            active = path.fileName.toString() == activeId,
                        )
                    }
                    .collect(java.util.stream.Collectors.toList())
            }
        }.getOrDefault(emptyList())
        return listOf(default) + children.sortedBy { it.name.lowercase(Locale.US) }
    }

    fun activeDirectory(): String {
        val root = rootDirectory()
        val id = selectedId() ?: return root.toString()
        if (id == DEFAULT_ID) return root.toString()
        val candidate = runCatching { root.resolve(id).normalize() }.getOrNull()
        if (candidate == null || candidate == root || !candidate.startsWith(root) ||
            Files.isSymbolicLink(candidate) ||
            !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
        ) {
            prefs.edit().remove(KEY_SELECTED).apply()
            return root.toString()
        }
        return candidate.toString()
    }

    fun create(name: String): ApiResult<ProjectWorkspace> {
        val cleanName = validateProjectName(name)
            ?: return ApiResult.Failure(
                ApiError(ErrorCode.E_PATH_DENIED, "Project name must be a simple folder name"),
            )
        val root = rootDirectory()
        val target = root.resolve(cleanName).normalize()
        if (!target.startsWith(root) || target == root) {
            return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "Project path escapes the workspace"))
        }
        return runCatching {
            Files.createDirectories(target)
            if (Files.isSymbolicLink(target)) {
                throw IllegalArgumentException("Project folder must not be a symlink")
            }
            prefs.edit().putString(KEY_SELECTED, cleanName).apply()
            ProjectWorkspace(
                id = cleanName,
                name = cleanName,
                directory = target.toString(),
                active = true,
            )
        }.fold(
            onSuccess = { ApiResult.Success(it) },
            onFailure = { failure ->
                ApiResult.Failure(
                    ApiError(
                        ErrorCode.E_PATH_DENIED,
                        failure.message ?: "Could not create the project folder",
                    ),
                )
            },
        )
    }

    fun select(id: String): ApiResult<ProjectWorkspace> {
        if (id != DEFAULT_ID) {
            val root = rootDirectory()
            val target = runCatching { root.resolve(id).normalize() }.getOrNull()
                ?: return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "Invalid project identifier"))
            if (!target.startsWith(root) || target == root ||
                Files.isSymbolicLink(target) ||
                !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
            ) {
                return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "Project folder is unavailable"))
            }
        }
        prefs.edit().putString(KEY_SELECTED, id).apply()
        val selected = list().firstOrNull { it.id == id }
            ?: return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "Project folder is unavailable"))
        return ApiResult.Success(selected)
    }

    fun selectedId(): String? = prefs.getString(KEY_SELECTED, null)

    companion object {
        const val PREFS_NAME = "opencode_projects"
        const val KEY_SELECTED = "selected_project"
        const val DEFAULT_ID = "."
        const val MAX_PROJECTS = 64
        const val MAX_NAME_LENGTH = 64

        private val NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}")

        fun validateProjectName(raw: String?): String? {
            val name = raw?.trim() ?: return null
            if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return null
            if (name == "." || name == "..") return null
            if (name.any { it == '/' || it == '\\' || it.isISOControl() }) return null
            return name.takeIf { NAME_PATTERN.matches(it) }
        }
    }
}
