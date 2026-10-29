package io.github.spectordev9.opencode.spike

import android.os.Environment
import java.io.File

object HarnessPaths {
    const val ROOT_DIR = "OpenCodeG0"
    const val REPORTS_DIR = "reports"
    const val DIAGNOSTICS_DIR = "diagnostics"
    const val STATE_FILE = "harness-state.json"

    fun reportRelativePath(): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$ROOT_DIR/$REPORTS_DIR"

    fun diagnosticsRelativePath(): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$ROOT_DIR/$DIAGNOSTICS_DIR"

    fun reportDisplayPath(filename: String): String =
        "Download/$ROOT_DIR/$REPORTS_DIR/$filename"

    fun diagnosticsDisplayPath(filename: String): String =
        "Download/$ROOT_DIR/$DIAGNOSTICS_DIR/$filename"

    fun legacyReportDirectory(downloads: File): File =
        File(downloads, "$ROOT_DIR/$REPORTS_DIR")

    fun legacyDiagnosticsDirectory(downloads: File): File =
        File(downloads, "$ROOT_DIR/$DIAGNOSTICS_DIR")
}
