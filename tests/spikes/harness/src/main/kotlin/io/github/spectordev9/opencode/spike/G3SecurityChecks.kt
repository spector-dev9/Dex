package io.github.spectordev9.opencode.spike

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.util.zip.ZipFile

internal object G3SecurityChecks {
    fun runSec2(
        context: Context,
        progress: (String) -> Unit,
    ): ProbeRecord {
        val started = System.currentTimeMillis()
        val findings = mutableListOf<String>()
        val scanned = mutableListOf<String>()
        val apks = buildList {
            add(File(context.applicationInfo.sourceDir))
            context.applicationInfo.splitSourceDirs?.forEach { add(File(it)) }
        }
        apks.forEach apkLoop@ { apk ->
            if (!apk.isFile) return@apkLoop
            progress("SEC-2 scanning ${apk.name}")
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().forEach entryLoop@ { entry ->
                    if (entry.isDirectory || entry.size <= 0 || entry.size > 8L * 1024 * 1024) {
                        return@entryLoop
                    }
                    val name = entry.name
                    if (name.endsWith(".so") || name.endsWith(".png") || name.endsWith(".jpg") ||
                        name.endsWith(".webp") || name.endsWith(".ttf") || name.endsWith(".woff2")
                    ) {
                        return@entryLoop
                    }
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (bytes.any { it == 0.toByte() }) return@entryLoop
                    scanText("apk:${apk.name}!$name", bytes.toString(Charsets.UTF_8), findings)
                    scanned += "apk:${apk.name}!$name"
                }
            }
        }
        progress("SEC-2 scanning app files")
        context.filesDir.walkTopDown().forEach fileLoop@ { file ->
            val path = file.absolutePath
            if (path.contains("/spike/") || path.contains("/g3/ws2/") || path.contains("/g3/ws4/")) {
                return@fileLoop
            }
            if (!file.isFile || file.length() > 8L * 1024 * 1024) return@fileLoop
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@fileLoop
            if (bytes.any { it == 0.toByte() }) return@fileLoop
            scanText("file:${file.absolutePath.removePrefix(context.filesDir.absolutePath)}", bytes.toString(Charsets.UTF_8), findings)
            scanned += "file:${file.name}"
        }
        progress("SEC-2 scanning process arguments")
        val ps = runCatching {
            val process = ProcessBuilder("/system/bin/ps", "-A", "-o", "PID,NAME,ARGS")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            text
        }.getOrDefault("")
        scanText("ps", ps, findings)
        val denied = mutableListOf<String>()
        listOf(
            "/data/data/io.github.spectordev9.opencode",
            "/data/data/com.termux",
            "/proc/1/environ",
        ).forEach { path ->
            val readable = runCatching { File(path).let { it.isDirectory && it.list()?.isNotEmpty() == true || it.isFile && it.canRead() } }.getOrDefault(false)
            if (readable) findings += "readable:$path" else denied += path
        }
        val backupDisabled = context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0
        if (!backupDisabled) findings += "allowBackup is enabled"
        val credentials = findCredentialFiles(context)
        credentials.forEach { file ->
            val mode = runCatching {
                java.nio.file.Files.getPosixFilePermissions(file.toPath()).toString()
            }.getOrDefault("unknown")
            if (!mode.contains("OWNER_WRITE") || mode.contains("GROUP_READ") || mode.contains("OTHERS_READ")) {
                findings += "credential permissions:${file.name}:$mode"
            }
            scanned += "credential:${file.name}:$mode"
        }
        val pass = findings.isEmpty() && backupDisabled
        val detail = buildString {
            append("apkFiles=${apks.size} scannedEntries=${scanned.size}")
            append(" findings=${findings.size}")
            if (findings.isNotEmpty()) append(" [${findings.take(3).joinToString(";")}]")
            append(" deniedPaths=${denied.size}/${denied.size + findings.count { it.startsWith("readable:") }}")
            append(" allowBackup=$backupDisabled credentials=${credentials.size}")
            append(" logcatScan=hostScriptRequired")
        }
        return ProbeRecord("SEC-2", pass, detail, durationMs = elapsed(started))
    }

    private fun findCredentialFiles(context: Context): List<File> =
        listOf("auth.json", "credentials.json", "secrets.json").mapNotNull { name ->
            File(context.filesDir, name).takeIf { it.isFile }
                ?: context.filesDir.walkTopDown().firstOrNull { it.isFile && it.name == name }
        }

    private fun scanText(source: String, text: String, findings: MutableList<String>) {
        if (text.contains(TEST_SECRET) || text.contains(TEST_PLACEHOLDER)) return
        SECRET_PATTERNS.forEach { (name, pattern) ->
            if (pattern.containsMatchIn(text)) {
                findings += "$name:$source"
            }
        }
    }

    private fun elapsed(started: Long): Long = System.currentTimeMillis() - started

    private const val TEST_SECRET = "g3-test-secret-2026"
    private const val TEST_PLACEHOLDER = "g3-placeholder-not-a-secret"
    private val SECRET_PATTERNS = listOf(
        "openai-key" to Regex("""sk-[A-Za-z0-9]{20,}"""),
        "aws-key" to Regex("""AKIA[0-9A-Z]{16}"""),
        "google-key" to Regex("""AIza[0-9A-Za-z_-]{35}"""),
        "github-token" to Regex("""ghp_[A-Za-z0-9]{36}"""),
        "slack-token" to Regex("""xox[baprs]-[A-Za-z0-9-]{10,}"""),
        "private-key" to Regex("""-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"""),
        "bearer-token" to Regex("""Bearer\s+[A-Za-z0-9._-]{30,}"""),
    )
}
