package io.github.spectordev9.opencode.spike

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SpikeReport {
    private const val TAG = "SpikeHarness"
    private val records = mutableListOf<ProbeRecord>()

    @Synchronized
    fun add(record: ProbeRecord) {
        records += record
    }

    @Synchronized
    fun clear() {
        records.clear()
    }

    @Synchronized
    fun write(context: Context): File {
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
        Log.i(TAG, text)
        return internal
    }

    fun logEvent(id: String, detail: String) {
        add(ProbeRecord(id = id, pass = true, detail = detail))
        Log.i(TAG, "$id: $detail")
    }
}
