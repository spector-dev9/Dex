package io.github.spectordev9.opencode.spike

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HarnessCrashHandler {
    private const val TAG = "SpikeHarness"
    private const val DIAGNOSTIC_FILE = "s1-server-diagnostics.log"

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(appContext, thread, error)
            } catch (failure: Throwable) {
                Log.e(TAG, "Could not persist uncaught exception", failure)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized
    fun appendDiagnostic(context: Context, line: String) {
        val appContext = context.applicationContext
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appendToDownloads(appContext, line)
            } else {
                appendToLegacyDownloads(line)
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "Could not export diagnostics to ${HarnessPaths.diagnosticsDisplayPath(DIAGNOSTIC_FILE)}", failure)
            try {
                appendToAppFiles(appContext, line)
            } catch (fallbackFailure: Throwable) {
                Log.e(TAG, "Could not persist diagnostics in app storage", fallbackFailure)
            }
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val filename = "harness-crash-$stamp.txt"
        val text = buildString {
            appendLine("generatedAt=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())}")
            appendLine("thread=${thread.name}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("androidRelease=${Build.VERSION.RELEASE}")
            appendLine("sdkInt=${Build.VERSION.SDK_INT}")
            appendLine("applicationId=${context.packageName}")
            appendLine("process=${currentProcessName()}")
            appendLine()
            appendLine(error.stackTraceToString())
            var cause = error.cause
            while (cause != null) {
                appendLine("caused by:")
                appendLine(cause.stackTraceToString())
                cause = cause.cause
            }
        }
        Log.e(TAG, text)
        val internal = File(context.filesDir, "reports").apply { mkdirs() }
            .resolve(filename)
        internal.writeText(text, Charsets.UTF_8)
        context.getExternalFilesDir("reports")?.let { directory ->
            runCatching {
                directory.mkdirs()
                File(directory, filename).writeText(text, Charsets.UTF_8)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToDownloads(context, filename, text)
            } else {
                writeToLegacyDownloads(filename, text)
            }
        }.onFailure {
            Log.e(TAG, "Could not export crash log to ${HarnessPaths.diagnosticsDisplayPath(filename)}", it)
        }
    }

    private fun appendToAppFiles(context: Context, line: String) {
        val internal = File(context.filesDir, "reports").apply { mkdirs() }
        File(internal, DIAGNOSTIC_FILE).appendText("$line\n", Charsets.UTF_8)
        context.getExternalFilesDir("reports")?.let { directory ->
            directory.mkdirs()
            File(directory, DIAGNOSTIC_FILE).appendText("$line\n", Charsets.UTF_8)
        }
    }

    private fun appendToDownloads(context: Context, line: String) {
        val resolver = context.contentResolver
        val relativePath = HarnessPaths.diagnosticsRelativePath()
        val existing = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(DIAGNOSTIC_FILE, "$relativePath/"),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                android.content.ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)),
                )
            } else {
                null
            }
        }
        val uri = existing ?: resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, DIAGNOSTIC_FILE)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 0)
            },
        ) ?: error("Could not create diagnostics in ${HarnessPaths.diagnosticsDisplayPath(DIAGNOSTIC_FILE)}")
        resolver.openOutputStream(uri, "wa")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.appendLine(line)
        } ?: error("Could not append diagnostics in ${HarnessPaths.diagnosticsDisplayPath(DIAGNOSTIC_FILE)}")
    }

    @Suppress("DEPRECATION")
    private fun appendToLegacyDownloads(line: String) {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val reportDirectory = HarnessPaths.legacyDiagnosticsDirectory(directory)
        check(reportDirectory.exists() || reportDirectory.mkdirs()) {
            "Could not create ${HarnessPaths.diagnosticsDisplayPath(DIAGNOSTIC_FILE)}"
        }
        File(reportDirectory, DIAGNOSTIC_FILE).appendText("$line\n", Charsets.UTF_8)
    }

    private fun writeToDownloads(context: Context, filename: String, text: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, HarnessPaths.diagnosticsRelativePath())
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create crash log in ${HarnessPaths.diagnosticsDisplayPath(filename)}")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open crash log in ${HarnessPaths.diagnosticsDisplayPath(filename)}")
            val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            check(resolver.update(uri, published, null, null) == 1) {
                "Could not publish crash log in ${HarnessPaths.diagnosticsDisplayPath(filename)}"
            }
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    private fun writeToLegacyDownloads(filename: String, text: String) {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val reportDirectory = HarnessPaths.legacyDiagnosticsDirectory(directory)
        check(reportDirectory.exists() || reportDirectory.mkdirs()) {
            "Could not create ${HarnessPaths.diagnosticsDisplayPath(filename)}"
        }
        File(reportDirectory, filename).writeText(text, Charsets.UTF_8)
    }

    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.app.Application.getProcessName()
        } else {
            "unknown"
        }

    private var installed = false
}
