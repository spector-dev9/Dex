package io.github.spectordev9.opencode.spike

import android.content.Context
import android.content.pm.ApplicationInfo
import android.app.ActivityManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

data class ProbeRecord(
    val id: String,
    val pass: Boolean,
    val detail: String,
    val command: List<String> = emptyList(),
    val exitCode: Int? = null,
    val durationMs: Long = 0,
    val stdout: String = "",
    val stderr: String = "",
)

object SpikeProbe {
    private const val MAX_OUTPUT_CHARS = 64_000
    private const val REQUIRED_ALIGNMENT = 16_384L
    private val NODE_VERSION_PATTERN = Regex("^v\\d+\\.\\d+\\.\\d+(?:[-+].*)?$")

    fun runtimeDir(context: Context): File = File(context.filesDir, "spike")

    fun deviceInfo(context: Context): Map<String, String> {
        val info = context.applicationInfo
        val result = linkedMapOf<String, String>()
        result["manufacturer"] = Build.MANUFACTURER
        result["model"] = Build.MODEL
        result["device"] = Build.DEVICE
        result["buildFingerprint"] = Build.FINGERPRINT
        result["androidRelease"] = Build.VERSION.RELEASE
        result["sdkInt"] = Build.VERSION.SDK_INT.toString()
        result["supportedAbis"] = Build.SUPPORTED_ABIS.joinToString()
        result["supported64BitAbis"] = Build.SUPPORTED_64_BIT_ABIS.joinToString()
        result["buildTags"] = Build.TAGS.orEmpty()
        result["buildType"] = Build.TYPE.orEmpty()
        result["pageSize"] = pageSize()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        result["totalMemoryBytes"] = memoryInfo.totalMem.toString()
        result["memoryClassMiB"] = activityManager.memoryClass.toString()
        result["lowRamDevice"] = activityManager.isLowRamDevice.toString()
        val powerManager = context.getSystemService(PowerManager::class.java)
        result["ignoringBatteryOptimizations"] =
            powerManager.isIgnoringBatteryOptimizations(context.packageName).toString()
        result["thermalStatusAtReport"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus.toString()
        } else {
            "unavailable"
        }
        result["defaultInputMethod"] = defaultInputMethod(context)
        result["applicationId"] = context.packageName
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        result["appVersionName"] = packageInfo.versionName.orEmpty()
        result["appVersionCode"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toString()
        }
        result["apkSha256"] = sha256(File(info.sourceDir))
        result["dataDir"] = info.dataDir
        result["nativeLibraryDir"] = info.nativeLibraryDir
        result["extractNativeLibs"] =
            ((info.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0).toString()
        result["externalFilesDir"] = context.getExternalFilesDir(null)?.absolutePath ?: "unavailable"
        result["runtimeDir"] = runtimeDir(context).absolutePath
        return result
    }

    fun defaultInputMethod(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?: "unavailable"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun pageSize(): String = try {
        Os.sysconf(OsConstants._SC_PAGESIZE).toString()
    } catch (t: Throwable) {
        "unavailable (${t.javaClass.simpleName})"
    }

    fun runtimeSummary(context: Context): String {
        val root = runtimeDir(context)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val required = listOf(
            "runtime/opencode",
            "runtime/libc.musl-aarch64.so.1",
            "runtime/libgcc_s.so.1",
            "runtime/libstdc++.so.6",
            "libtalloc.so.2",
            "libandroid-shmem.so",
            "rootfs/bin/sh",
        )
        val appFiles = required.map { relative ->
            val file = File(root, relative)
            val exists = if (relative == "rootfs/bin/sh") {
                rootfsFileExists(File(root, "rootfs"), "bin/sh")
            } else {
                file.exists()
            }
            if (exists) "OK      $relative (${file.length()} B)" else "MISSING $relative"
        }
        val nativeFiles = listOf("libldmusl.so", "libproot.so", "libproot_loader.so").map { name ->
            val file = File(nativeDir, name)
            if (file.isFile) {
                "OK      $name in nativeLibraryDir (${file.length()} B)"
            } else {
                "MISSING $name in nativeLibraryDir"
            }
        }
        return (appFiles + nativeFiles).joinToString("\n")
    }

    fun probeEntrypoints(context: Context): List<ProbeRecord> {
        prepareRuntime(context)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val records = mutableListOf<ProbeRecord>()
        records += elfRecord("S1-native-loader", File(nativeDir, "libldmusl.so"))
        records += elfRecord("S3-native-proot", File(nativeDir, "libproot.so"))
        records += elfRecord("S3-native-proot-loader", File(nativeDir, "libproot_loader.so"))
        val root = runtimeDir(context)
        val required = listOf(
            "runtime/opencode",
            "runtime/libc.musl-aarch64.so.1",
            "runtime/libgcc_s.so.1",
            "runtime/libstdc++.so.6",
            "libtalloc.so.2",
            "libandroid-shmem.so",
            "rootfs/bin/sh",
        )
        val missing = required.filterNot { relative ->
            if (relative == "rootfs/bin/sh") {
                rootfsFileExists(File(root, "rootfs"), "bin/sh")
            } else {
                File(root, relative).isFile
            }
        }
        records += ProbeRecord(
            id = "runtime-layout",
            pass = missing.isEmpty(),
            detail = if (missing.isEmpty()) {
                "runtime layout complete at $root"
            } else {
                "missing: ${missing.joinToString()}"
            },
        )
        return records
    }

    fun runLoader(context: Context): ProbeRecord {
        val root = runtimeDir(context)
        val loader = File(context.applicationInfo.nativeLibraryDir, "libldmusl.so")
        val runtime = File(root, "runtime")
        val opencode = File(runtime, "opencode")
        val missing = listOf(loader, opencode).filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            return ProbeRecord(
                id = "S1-loader",
                pass = false,
                detail = "missing ${missing.joinToString { it.absolutePath }}",
            )
        }
        prepareRuntime(context)
        val command = listOf(
            loader.absolutePath,
            "--library-path",
            runtime.absolutePath,
            opencode.absolutePath,
            "--version",
        )
        return runProcess(
            id = "S1-loader",
            command = command,
            env = runtimeEnvironment(context),
            workDir = runtime,
            timeoutMs = 30_000,
        )
    }

    fun runGuestShell(context: Context): ProbeRecord {
        val root = runtimeDir(context)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDir, "libproot.so").takeIf { it.isFile }
            ?: File(root, "proot").takeIf { it.isFile }
        val loader = File(nativeDir, "libproot_loader.so").takeIf { it.isFile }
            ?: File(root, "loader").takeIf { it.isFile }
        val rootfs = File(root, "rootfs")
        if (proot == null) {
            return ProbeRecord("S3-guest-shell", false, "no PRoot entrypoint found")
        }
        if (loader == null) {
            return ProbeRecord("S3-guest-shell", false, "no PRoot loader found")
        }
        if (!rootfsFileExists(rootfs, "bin/sh")) {
            return ProbeRecord("S3-guest-shell", false, "rootfs/bin/sh missing under $rootfs")
        }
        prepareRuntime(context)
        val command = listOf(
            proot.absolutePath,
            "--kill-on-exit",
            "-0",
            "-r",
            rootfs.absolutePath,
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-w",
            "/root",
            "/bin/sh",
            "-c",
            "node_version=\$(node -v 2>&1); node_status=\$?; " +
                "printf 'D=%s\\nP=%s\\nN=%s\\n' \"\$PWD\" \"\$PATH\" \"\$node_version\"; " +
                "[ \"\$node_status\" -eq 0 ] || exit \"\$node_status\"; " +
                "/bin/cat /etc/alpine-release 2>/dev/null; echo GUEST-OK",
        )
        val env = runtimeEnvironment(context).apply {
            put("PROOT_TMP_DIR", File(root, "tmp").absolutePath)
            put("PROOT_NO_SECCOMP", "1")
            put(
                "LD_LIBRARY_PATH",
                root.absolutePath + ":" + File(root, "runtime").absolutePath + ":" +
                    context.applicationInfo.nativeLibraryDir,
            )
            put("PROOT_LOADER", loader.absolutePath)
        }
        val record = runProcess(
            id = "S3-guest-shell",
            command = command,
            env = env,
            workDir = rootfs,
            timeoutMs = 60_000,
        )
        val environment = probeEnvironment(record.stdout)
        val environmentValid = hasValidProbeEnvironment(environment)
        val detail = if (environmentValid) {
            record.detail
        } else {
            "${record.detail}; expected PWD, PATH and a successful node -v result"
        }
        return record.copy(
            pass = record.pass && record.exitCode == 0 && record.stdout.contains("GUEST-OK") && environmentValid,
            detail = detail,
        )
    }

    fun runGuestPtyComparison(context: Context): List<ProbeRecord> {
        val guest = runGuestShell(context)
        val root = runtimeDir(context)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDir, "libproot.so").takeIf { it.isFile }
            ?: File(root, "proot").takeIf { it.isFile }
        val loader = File(nativeDir, "libproot_loader.so").takeIf { it.isFile }
            ?: File(root, "loader").takeIf { it.isFile }
        val rootfs = File(root, "rootfs")
        if (proot == null || loader == null || !rootfsFileExists(rootfs, "bin/sh")) {
            return listOf(
                guest,
                ProbeRecord("S3-native-pty", false, "missing PRoot, loader or Alpine shell"),
            )
        }

        prepareRuntime(context)
        val started = System.currentTimeMillis()
        val raw = try {
            NativePtyProbeClient.runProotShell(
                context = context,
                proot = proot.absolutePath,
                loader = loader.absolutePath,
                rootfs = rootfs.absolutePath,
                runtimeRoot = root.absolutePath,
                timeoutMillis = 60_000,
                testInputBytes = false,
            )
        } catch (error: LinkageError) {
            return listOf(
                guest,
                ProbeRecord(
                    id = "S3-native-pty",
                    pass = false,
                    detail = "${error.javaClass.simpleName}: ${error.message}",
                    durationMs = System.currentTimeMillis() - started,
                ),
            )
        }

        val output = raw.substringAfter('\n', "")
        val exitCode = raw.lineSequence()
            .firstOrNull()
            ?.removePrefix("exitCode=")
            ?.toIntOrNull()
        val normalized = output.replace("\r\n", "\n").replace('\r', '\n')
        val guestValues = probeEnvironment(guest.stdout)
        val ptyValues = probeEnvironment(normalized)
        val matches = hasValidProbeEnvironment(guestValues) &&
            hasValidProbeEnvironment(ptyValues) &&
            guestValues == ptyValues
        val hasTerminalSize = normalized.lineSequence().any { it.trim() == "24 80" }
        val passed = exitCode == 0 &&
            normalized.contains("GUEST-PTY-OK") &&
            hasTerminalSize &&
            matches
        val detail = buildString {
            append("exit=$exitCode terminalSize=${if (hasTerminalSize) "80x24" else "missing"} ")
            append("environmentMatch=$matches")
            if (!matches) append(" guest=$guestValues pty=$ptyValues")
        }
        return listOf(
            guest,
            ProbeRecord(
                id = "S3-native-pty",
                pass = passed,
                detail = detail,
                command = listOf("forkpty", proot.absolutePath, "/bin/sh", "-i"),
                exitCode = exitCode,
                durationMs = System.currentTimeMillis() - started,
                stdout = normalized,
            ),
            runTerm5PtyInput(context, proot, loader, rootfs, root),
        )
    }

    fun runTerm5NativePty(context: Context): List<ProbeRecord> {
        val root = runtimeDir(context)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDir, "libproot.so").takeIf { it.isFile }
            ?: File(root, "proot").takeIf { it.isFile }
        val loader = File(nativeDir, "libproot_loader.so").takeIf { it.isFile }
            ?: File(root, "loader").takeIf { it.isFile }
        val rootfs = File(root, "rootfs")
        if (proot == null || loader == null || !rootfsFileExists(rootfs, "bin/sh")) {
            return listOf(
                ProbeRecord("TERM-5-pty-byte-roundtrip", false, "missing PRoot, loader or Alpine shell"),
            )
        }
        prepareRuntime(context)
        return listOf(runTerm5PtyInput(context, proot, loader, rootfs, root))
    }

    private fun runTerm5PtyInput(
        context: Context,
        proot: File,
        loader: File,
        rootfs: File,
        root: File,
    ): ProbeRecord {
        val inputStarted = System.currentTimeMillis()
        val inputRaw = try {
            NativePtyProbeClient.runProotShell(
                context = context,
                proot = proot.absolutePath,
                loader = loader.absolutePath,
                rootfs = rootfs.absolutePath,
                runtimeRoot = root.absolutePath,
                timeoutMillis = 60_000,
                testInputBytes = true,
            )
        } catch (error: LinkageError) {
            "exitCode=-1\n${error.javaClass.simpleName}: ${error.message}\n"
        }
        val inputOutput = inputRaw.substringAfter('\n', "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val inputExitCode = inputRaw.lineSequence()
            .firstOrNull()
            ?.removePrefix("exitCode=")
            ?.toIntOrNull()
        val expectedInputBytes = "61 62 63 0a 7f 09 1b 1b 5b 41 03 1b 78"
        val inputBytesMatch = inputOutput.replace(Regex("\\s+"), " ").contains(expectedInputBytes)
        return ProbeRecord(
            id = "TERM-5-pty-byte-roundtrip",
            pass = inputExitCode == 0 && inputBytesMatch && inputOutput.contains("GUEST-PTY-OK"),
            detail = "exit=$inputExitCode; Enter CR normalized to LF by ICRNL; " +
                "expectedBytes=$expectedInputBytes; byteRoundTrip=$inputBytesMatch; " +
                "scripted PTY injection only, not physical IME/key mapping",
            command = listOf("forkpty", "PRoot", "scripted terminal byte sequence"),
            exitCode = inputExitCode,
            durationMs = System.currentTimeMillis() - inputStarted,
            stdout = inputOutput,
        )
    }

    private fun probeEnvironment(output: String): Map<String, String> =
        output.replace("\r\n", "\n").replace('\r', '\n')
            .lineSequence()
            .filter { it.startsWith("D=") || it.startsWith("P=") || it.startsWith("N=") }
            .associate { line -> line.substringBefore('=') to line.substringAfter('=') }

    private fun hasValidProbeEnvironment(environment: Map<String, String>): Boolean =
        environment.keys == setOf("D", "P", "N") &&
            environment["D"].orEmpty().isNotBlank() &&
            environment["P"].orEmpty().isNotBlank() &&
            NODE_VERSION_PATTERN.matches(environment["N"].orEmpty())

    internal fun rootfsFileExists(rootfs: File, relativePath: String): Boolean {
        val root = rootfs.toPath().toAbsolutePath().normalize()
        var candidate = root.resolve(relativePath).normalize()
        repeat(16) {
            candidate = candidate.toAbsolutePath().normalize()
            if (!candidate.startsWith(root)) return false
            if (Files.isSymbolicLink(candidate)) {
                val target = Files.readSymbolicLink(candidate)
                candidate = if (target.isAbsolute) {
                    root.resolve(target.toString().removePrefix("/"))
                } else {
                    candidate.parent.resolve(target)
                }
            } else {
                return Files.isRegularFile(candidate)
            }
        }
        return false
    }

    private fun elfRecord(id: String, file: File): ProbeRecord {
        if (!file.isFile) {
            return ProbeRecord(id, false, "missing ${file.absolutePath}")
        }
        val alignment = readElfMinLoadAlignment(file)
        val executable = file.canExecute()
        val detail = buildString {
            append("path=${file.absolutePath} size=${file.length()} executable=$executable")
            if (alignment == null) {
                append(" elf=unreadable-or-not-elf64")
            } else {
                append(" minLoadAlign=$alignment")
                append(if (alignment >= REQUIRED_ALIGNMENT) " 16KB-ok" else " below-16KB")
            }
        }
        val pass = executable && alignment != null && alignment >= REQUIRED_ALIGNMENT
        return ProbeRecord(id, pass, detail)
    }

    private fun readElfMinLoadAlignment(file: File): Long? {
        if (file.length() < 64) {
            return null
        }
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(64)
                raf.readFully(header)
                if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
                ) {
                    return null
                }
                if (header[4].toInt() != 2) {
                    return null
                }
                val order = when (header[5].toInt()) {
                    1 -> ByteOrder.LITTLE_ENDIAN
                    2 -> ByteOrder.BIG_ENDIAN
                    else -> return null
                }
                val buffer = ByteBuffer.wrap(header).order(order)
                val programHeaderOffset = buffer.getLong(32)
                val programHeaderSize = buffer.getShort(54).toInt() and 0xffff
                val programHeaderCount = buffer.getShort(56).toInt() and 0xffff
                if (programHeaderSize < 56 || programHeaderCount <= 0) {
                    return null
                }
                var minimum: Long? = null
                val programHeader = ByteArray(programHeaderSize)
                for (index in 0 until programHeaderCount) {
                    raf.seek(programHeaderOffset + index.toLong() * programHeaderSize)
                    raf.readFully(programHeader)
                    val entry = ByteBuffer.wrap(programHeader).order(order)
                    if (entry.getInt(0) == 1) {
                        val alignment = entry.getLong(48)
                        if (alignment > 0) {
                            minimum = minimum?.let { minOf(it, alignment) } ?: alignment
                        }
                    }
                }
                minimum
            }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun prepareRuntime(context: Context) {
        val root = runtimeDir(context)
        listOf("home/.config", "home/.local/share", "home/.cache", "tmp").forEach {
            File(root, it).mkdirs()
        }
        listOf(
            "rootfs/bin/sh",
        ).forEach { markExecutable(File(root, it)) }
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        listOf("libldmusl.so", "libproot.so", "libproot_loader.so").forEach {
            markExecutable(File(nativeDir, it))
        }
    }

    private fun markExecutable(file: File) {
        if (!file.exists()) {
            return
        }
        try {
            Os.chmod(file.absolutePath, 493)
        } catch (_: Throwable) {
        }
    }

    internal fun runtimeEnvironment(context: Context): MutableMap<String, String> {
        val root = runtimeDir(context)
        val home = File(root, "home")
        val runtime = File(root, "runtime")
        return mutableMapOf(
            "HOME" to home.absolutePath,
            "XDG_CONFIG_HOME" to File(home, ".config").absolutePath,
            "XDG_DATA_HOME" to File(home, ".local/share").absolutePath,
            "XDG_CACHE_HOME" to File(home, ".cache").absolutePath,
            "TMPDIR" to File(root, "tmp").absolutePath,
            "LD_LIBRARY_PATH" to runtime.absolutePath,
            "PATH" to "/system/bin:/system/xbin:/usr/local/sbin:/usr/local/bin:" +
                "/usr/sbin:/usr/bin:/sbin:/bin",
            "NO_COLOR" to "1",
            "OPENCODE_DISABLE_AUTOUPDATE" to "1",
        )
    }

    private fun runProcess(
        id: String,
        command: List<String>,
        env: Map<String, String>,
        workDir: File,
        timeoutMs: Long,
    ): ProbeRecord {
        val started = System.currentTimeMillis()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        return try {
            val processBuilder = ProcessBuilder(command)
            processBuilder.directory(workDir)
            processBuilder.environment().apply {
                remove("LD_PRELOAD")
                putAll(env)
            }
            val process = processBuilder.start()
            val stdoutThread = drain(process.inputStream, stdout)
            val stderrThread = drain(process.errorStream, stderr)
            var exitCode: Int? = null
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    exitCode = process.exitValue()
                    break
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(50)
                }
            }
            if (exitCode == null) {
                process.destroyForcibly()
                try {
                    process.waitFor()
                } catch (_: Throwable) {
                }
            }
            stdoutThread.join(1_000)
            stderrThread.join(1_000)
            val duration = System.currentTimeMillis() - started
            ProbeRecord(
                id = id,
                pass = exitCode == 0,
                detail = if (exitCode == null) "timed out after ${timeoutMs}ms" else "exit=$exitCode",
                command = command,
                exitCode = exitCode,
                durationMs = duration,
                stdout = snapshot(stdout),
                stderr = snapshot(stderr),
            )
        } catch (t: Throwable) {
            ProbeRecord(
                id = id,
                pass = false,
                detail = "${t.javaClass.simpleName}: ${t.message}",
                command = command,
                durationMs = System.currentTimeMillis() - started,
                stdout = snapshot(stdout),
                stderr = snapshot(stderr),
            )
        }
    }

    private fun drain(input: InputStream, sink: StringBuilder): Thread = Thread {
        try {
            input.reader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) {
                        break
                    }
                    synchronized(sink) {
                        if (sink.length < MAX_OUTPUT_CHARS) {
                            sink.append(buffer, 0, minOf(count, MAX_OUTPUT_CHARS - sink.length))
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }.apply {
        isDaemon = true
        start()
    }

    private fun snapshot(sink: StringBuilder): String = synchronized(sink) { sink.toString() }
}
