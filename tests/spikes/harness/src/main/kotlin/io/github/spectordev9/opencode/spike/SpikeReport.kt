package io.github.spectordev9.opencode.spike

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WrittenSpikeReport(
    val internalFile: File,
    val downloadLocation: String,
)

data class G0GateResult(
    val status: String,
    val detail: String,
)

object SpikeReport {
    private const val TAG = "SpikeHarness"
    private const val STATE_SCHEMA = 1
    private val records = mutableListOf<ProbeRecord>()
    private val g0GateResults = linkedMapOf<String, G0GateResult>()
    private var appContext: Context? = null
    private var activeProbe: String? = null

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val loaded = loadState(context.applicationContext)
        if (loaded == null) {
            applyAcceptedDefaultsLocked()
            persistLocked()
            return
        }
        records.addAll(loaded.records.filterNot { isRemovedGateRecord(it.id) })
        val activeGateIds = G0GateCatalog.checks.mapTo(mutableSetOf()) { it.id }
        g0GateResults.putAll(loaded.gates.filterKeys { it in activeGateIds })
        activeProbe = loaded.activeProbe?.takeUnless { isRemovedGateRecord(it) }
        applyMissingAcceptedDefaultsLocked()
        val interrupted = activeProbe
        if (interrupted != null) {
            records += ProbeRecord(
                id = "harness-interrupted",
                pass = false,
                detail = "Probe '$interrupted' was interrupted by process death; rerun it.",
            )
            activeProbe = null
        }
        persistLocked()
    }

    @Synchronized
    fun add(record: ProbeRecord) {
        records += record
        persistLocked()
    }

    @Synchronized
    fun snapshot(): List<ProbeRecord> = records.toList()

    @Synchronized
    fun upsert(record: ProbeRecord) {
        val index = records.indexOfFirst { it.id == record.id }
        if (index < 0) {
            records += record
        } else {
            records[index] = record
        }
        persistLocked()
    }

    @Synchronized
    fun clear() {
        records.clear()
        g0GateResults.clear()
        activeProbe = null
        applyAcceptedDefaultsLocked()
        persistLocked()
    }

    @Synchronized
    fun setActiveProbe(label: String?) {
        activeProbe = label
        persistLocked()
    }

    @Synchronized
    fun setG0GateResult(id: String, status: String, detail: String) {
        require(status in setOf("pass", "fail", "partial", "blocked", "manual", "deferred", "accepted"))
        g0GateResults[id] = G0GateResult(status, detail)
        persistLocked()
    }

    @Synchronized
    fun g0GateResult(id: String): G0GateResult? = g0GateResults[id]

    @Synchronized
    fun g0GateSnapshot(): Map<String, G0GateResult> = g0GateResults.toMap()

    @Synchronized
    fun appendDiagnostic(context: Context, line: String) {
        HarnessCrashHandler.appendDiagnostic(context, line)
    }

    @Synchronized
    fun write(context: Context): WrittenSpikeReport {
        applyMissingAcceptedDefaultsLocked()
        val root = JSONObject()
        root.put("spikeVersion", "0.2")
        root.put("generatedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
        root.put(
            "scope",
            "Isolated pre-G0 device feasibility evidence; harness-passed S1-A/S3 checks are " +
                "accepted for G0, with production follow-ups tracked in the repository docs.",
        )
        root.put(
            "knownNotCovered",
            JSONArray(
                listOf(
                    "Production S1 Profile A/B comparison; harness S1-A is accepted for G0",
                    "S2 Profile A/B performance comparison; Profile B is not implemented",
                    "S4/S5 host-side SSE and endpoint contract checks",
                    "S6 OpenCode 2.x contract test; no distributable 2.x artifact is available",
                    "RT/STR/WS/TERM production-app and UI acceptance tests",
                    "SEC production security, second-UID, and release-manifest acceptance tests",
                    "S9 release, licensing, and distribution compliance",
                    "16 KB-page-device boot (out of scope for v1) and optional post-G0 memory/thermal checks",
                ),
            ),
        )
        val gateResults = JSONObject()
        G0GateCatalog.checks.forEach { check ->
            val result = g0GateResult(check.id)
            gateResults.put(
                check.id,
                JSONObject()
                    .put("title", check.title)
                    .put("status", result?.status ?: "pending")
                    .put("requirement", check.requirement)
                    .put("detail", result?.detail ?: "Not yet assessed"),
            )
        }
        val allGateChecksPassed = G0GateCatalog.checks.all {
            val status = g0GateResult(it.id)?.status
            status == "pass" || status == "accepted"
        }
        root.put("g0Status", if (allGateChecksPassed) "green" else "pending")
        root.put("g0AcceptedChecks", JSONArray(
            G0GateCatalog.checks
                .filter { g0GateResult(it.id)?.status == "accepted" }
                .map { it.id },
        ))
        root.put("g0GateChecks", gateResults)
        root.put("device", JSONObject(SpikeProbe.deviceInfo(context)))
        val results = JSONArray()
        records.forEach { record ->
            val item = JSONObject()
            item.put("id", record.id)
            item.put("pass", record.pass)
            item.put("detail", record.detail)
            item.put("command", JSONArray(record.command))
            item.put("exitCode", record.exitCode ?: JSONObject.NULL)
            item.put("durationMs", record.durationMs)
            item.put("stdout", record.stdout)
            item.put("stderr", record.stderr)
            results.put(item)
        }
        root.put("results", results)
        val text = root.toString(2)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val internalDir = File(context.filesDir, "reports").apply { mkdirs() }
        val internal = File(internalDir, "spike-$stamp.json")
        internal.writeText(text)
        context.getExternalFilesDir("reports")?.let { externalDir ->
            externalDir.mkdirs()
            File(externalDir, internal.name).writeText(text)
        }
        val downloadLocation =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToMediaStore(context, internal.name, text)
            } else {
                writeToLegacyDownloads(internal.name, text)
            }
        Log.i(TAG, text)
        return WrittenSpikeReport(internal, downloadLocation)
    }

    private fun writeToMediaStore(context: Context, filename: String, text: String): String {
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, HarnessPaths.reportRelativePath())
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val resolver = context.contentResolver
        val uri =
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create report in ${HarnessPaths.reportDisplayPath(filename)}")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open report in ${HarnessPaths.reportDisplayPath(filename)}")
            val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            check(resolver.update(uri, published, null, null) == 1) {
                "Could not publish report in ${HarnessPaths.reportDisplayPath(filename)}"
            }
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return HarnessPaths.reportDisplayPath(filename)
    }

    @Suppress("DEPRECATION")
    private fun writeToLegacyDownloads(filename: String, text: String): String {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val reportDirectory = HarnessPaths.legacyReportDirectory(directory)
        check(reportDirectory.exists() || reportDirectory.mkdirs()) {
            "Could not create ${HarnessPaths.reportDisplayPath(filename)}"
        }
        val report = File(reportDirectory, filename)
        report.writeText(text, Charsets.UTF_8)
        return report.absolutePath
    }

    private fun isRemovedGateRecord(id: String): Boolean =
        id.startsWith("S7-") || id.startsWith("S8-") ||
            id.contains("TERM-5", ignoreCase = true) ||
            id.contains("term5", ignoreCase = true)

    private fun applyAcceptedDefaultsLocked() {
        g0GateResults.clear()
        g0GateResults.putAll(G0GateCatalog.acceptedDefaults)
    }

    private fun applyMissingAcceptedDefaultsLocked() {
        G0GateCatalog.acceptedDefaults.forEach { (id, result) ->
            if (!g0GateResults.containsKey(id)) g0GateResults[id] = result
        }
    }

    private fun stateFile(context: Context): File =
        File(context.filesDir, "reports/${HarnessPaths.STATE_FILE}")

    private fun loadState(context: Context): LoadedState? {
        val file = stateFile(context)
        if (!file.isFile) return null
        return try {
            val root = JSONObject(file.readText())
            if (root.optInt("schema", 0) != STATE_SCHEMA) return null
            val loadedRecords = mutableListOf<ProbeRecord>()
            val recordsJson = root.optJSONArray("records") ?: JSONArray()
            for (index in 0 until recordsJson.length()) {
                val item = recordsJson.optJSONObject(index) ?: continue
                val command = mutableListOf<String>()
                item.optJSONArray("command")?.let { commands ->
                    for (commandIndex in 0 until commands.length()) {
                        command += commands.optString(commandIndex)
                    }
                }
                loadedRecords += ProbeRecord(
                    id = item.optString("id"),
                    pass = item.optBoolean("pass"),
                    detail = item.optString("detail"),
                    command = command,
                    exitCode = if (item.isNull("exitCode")) null else item.optInt("exitCode"),
                    durationMs = item.optLong("durationMs"),
                    stdout = item.optString("stdout"),
                    stderr = item.optString("stderr"),
                )
            }
            val loadedGates = linkedMapOf<String, G0GateResult>()
            val gatesJson = root.optJSONObject("gates") ?: JSONObject()
            gatesJson.keys().forEach { id ->
                val gate = gatesJson.optJSONObject(id) ?: return@forEach
                val status = gate.optString("status")
                if (status in setOf("pass", "fail", "partial", "blocked", "manual", "deferred", "accepted")) {
                    loadedGates[id] = G0GateResult(status, gate.optString("detail"))
                }
            }
            LoadedState(
                records = loadedRecords,
                gates = loadedGates,
                activeProbe = root.optString("activeProbe").takeIf { it.isNotBlank() },
            )
        } catch (failure: Throwable) {
            Log.e(TAG, "Could not load harness state; starting from accepted defaults", failure)
            null
        }
    }

    private fun persistLocked() {
        val context = appContext ?: return
        try {
            val root = JSONObject()
            root.put("schema", STATE_SCHEMA)
            root.put("savedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
            activeProbe?.let { root.put("activeProbe", it) }
            val recordsJson = JSONArray()
            records.forEach { record ->
                recordsJson.put(
                    JSONObject()
                        .put("id", record.id)
                        .put("pass", record.pass)
                        .put("detail", record.detail)
                        .put("command", JSONArray(record.command))
                        .put("exitCode", record.exitCode ?: JSONObject.NULL)
                        .put("durationMs", record.durationMs)
                        .put("stdout", record.stdout)
                        .put("stderr", record.stderr),
                )
            }
            root.put("records", recordsJson)
            val gatesJson = JSONObject()
            g0GateResults.forEach { (id, result) ->
                gatesJson.put(id, JSONObject().put("status", result.status).put("detail", result.detail))
            }
            root.put("gates", gatesJson)
            val file = stateFile(context)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "Could not persist harness state", failure)
        }
    }

    private data class LoadedState(
        val records: List<ProbeRecord>,
        val gates: Map<String, G0GateResult>,
        val activeProbe: String?,
    )

    private fun Cursor.firstUri(): android.net.Uri? =
        if (moveToFirst()) {
            ContentUris.withAppendedId(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                getLong(getColumnIndexOrThrow(MediaStore.Downloads._ID)),
            )
        } else {
            null
        }
}
