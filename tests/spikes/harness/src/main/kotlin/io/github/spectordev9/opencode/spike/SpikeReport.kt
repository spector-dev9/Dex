package io.github.spectordev9.opencode.spike

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WrittenSpikeReport(
    val internalFile: File,
    val downloadLocation: String,
)

object SpikeReport {
    private const val TAG = "SpikeHarness"
    private val records = mutableListOf<ProbeRecord>()

    @Synchronized
    fun add(record: ProbeRecord) {
        records += record
    }

    @Synchronized
    fun upsert(record: ProbeRecord) {
        val index = records.indexOfFirst { it.id == record.id }
        if (index < 0) {
            records += record
        } else {
            records[index] = record
        }
    }

    @Synchronized
    fun clear() {
        records.clear()
    }

    @Synchronized
    fun write(context: Context): WrittenSpikeReport {
        val root = JSONObject()
        root.put("spikeVersion", "0.1")
        root.put("generatedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
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
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/OpenCodeSpike",
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val resolver = context.contentResolver
        val uri =
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create report in Downloads/OpenCodeSpike")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open report in Downloads/OpenCodeSpike")
            val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            check(resolver.update(uri, published, null, null) == 1) {
                "Could not publish report in Downloads/OpenCodeSpike"
            }
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return "Download/OpenCodeSpike/$filename"
    }

    @Suppress("DEPRECATION")
    private fun writeToLegacyDownloads(filename: String, text: String): String {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val reportDirectory = File(directory, "OpenCodeSpike")
        check(reportDirectory.exists() || reportDirectory.mkdirs()) {
            "Could not create Downloads/OpenCodeSpike"
        }
        val report = File(reportDirectory, filename)
        report.writeText(text, Charsets.UTF_8)
        return report.absolutePath
    }

    fun logEvent(id: String, detail: String) {
        add(ProbeRecord(id = id, pass = true, detail = detail))
        Log.i(TAG, "$id: $detail")
    }
}
