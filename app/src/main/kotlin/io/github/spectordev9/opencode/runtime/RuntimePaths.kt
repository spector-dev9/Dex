package io.github.spectordev9.opencode.runtime

import android.content.Context
import android.system.Os
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Layout of the pinned runtime staged inside the production app sandbox.
 *
 * The layout matches the layout proven by the spike harness on the reference
 * device (`docs/evidence/g3/spike-harness.md`): the musl loader, PRoot and the
 * packaged helper libraries live in the APK's native-library directory, while
 * OpenCode and the Alpine rootfs are downloaded and verified into app-private
 * storage. Executables in app-private storage are only ever *loaded* by the
 * APK-native loader; the launcher never execve()s a writable-storage file.
 */
object RuntimePaths {
    fun runtimeRoot(context: Context): Path =
        File(context.filesDir, "runtime-root").toPath()

    fun runtimeDirectory(root: Path): Path = root.resolve("runtime")

    fun opencodeBinary(root: Path): Path = runtimeDirectory(root).resolve("opencode")

    fun alpineRootfs(root: Path): Path = root.resolve("rootfs")

    /** APK-native Profile A agent-shell wrapper (see cpp/guest_shell.c). */
    fun guestShellWrapper(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libopencode_shell.so")

    fun downloads(root: Path): Path = root.resolve("downloads")

    fun serverDirectory(root: Path): Path = root.resolve("server")

    fun workspaceDirectory(context: Context): Path =
        File(context.filesDir, "workspace").toPath()

    /**
     * The supervised server runs with its working directory under
     * runtime-root/server (see ProductionServerLauncher); sessions, file
     * listings and prompts are all scoped to that directory.
     */
    fun serverWorkspaceDirectory(context: Context): Path =
        serverDirectory(runtimeRoot(context)).resolve("workspace")

    /**
     * Base environment for processes spawned from the staged runtime. Callers
     * append their own entries (server config, password, guest shell TERM…).
     */
    fun baseEnvironment(context: Context, home: Path): MutableMap<String, String> {
        val root = runtimeRoot(context)
        return mutableMapOf(
            "HOME" to home.toString(),
            "XDG_CONFIG_HOME" to home.resolve(".config").toString(),
            "XDG_DATA_HOME" to home.resolve(".local/share").toString(),
            "XDG_CACHE_HOME" to home.resolve(".cache").toString(),
            "TMPDIR" to root.resolve("tmp").toString(),
            "PROOT_TMP_DIR" to root.resolve("tmp").toString(),
            "PROOT_NO_SECCOMP" to "1",
            "LD_LIBRARY_PATH" to root.resolve("rootfs").resolve("usr").resolve("lib")
                .toString() + ":" + runtimeDirectory(root).toString(),
            "PATH" to "/system/bin:/system/xbin:/usr/local/sbin:/usr/local/bin:" +
                "/usr/sbin:/usr/bin:/sbin:/bin",
            "NO_COLOR" to "1",
            "OPENCODE_DISABLE_AUTOUPDATE" to "1",
        )
    }

    fun isRuntimeReady(context: Context): Boolean {
        val root = runtimeRoot(context)
        return Files.isRegularFile(opencodeBinary(root)) &&
            Files.isRegularFile(alpineRootfs(root).resolve("bin/sh")) &&
            File(context.applicationInfo.nativeLibraryDir, "libldmusl.so").isFile &&
            File(context.applicationInfo.nativeLibraryDir, "libproot.so").isFile &&
            File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so").isFile &&
            guestShellWrapper(context).isFile
    }

    internal fun markExecutable(path: Path) {
        if (!Files.exists(path)) return
        try {
            Os.chmod(path.toString(), 493) // 0755
        } catch (_: Throwable) {
        }
    }
}
