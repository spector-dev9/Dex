package io.github.spectordev9.opencode.spike

import android.Manifest
import android.app.AlertDialog
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var deviceView: TextView
    private lateinit var runtimeView: TextView
    private lateinit var resultsView: TextView
    private lateinit var statusView: TextView
    private lateinit var imeView: TextView
    private lateinit var imeField: ImeProbeEditText
    private lateinit var soakView: TextView
    private var imeTextChanges = 0
    private var imeObservationNumber = 0
    private var probesRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HarnessCrashHandler.install(this)
        buildUi()
        refresh()
        requestNotificationPermission()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(label("OpenCode Phase 2 spike harness"))
        root.addView(label("Isolated pre-G0 harness. Not production wiring."))
        root.addView(button("Refresh device and runtime") { refresh() })

        root.addView(label("Device"))
        deviceView = monospace()
        root.addView(deviceView)

        root.addView(label("Runtime layout"))
        runtimeView = monospace()
        root.addView(runtimeView)

        root.addView(button("Download and verify pinned runtime (large download)") {
            runProbes("runtime-download") {
                val started = System.currentTimeMillis()
                try {
                    val installed = SpikeRuntimeInstaller.install(applicationContext) { item, bytes, total ->
                        runOnUiThread {
                            val amount = if (total > 0) {
                                "${bytes / 1_048_576} / ${total / 1_048_576} MiB"
                            } else {
                                "${bytes / 1_048_576} MiB"
                            }
                            statusView.text = "Downloading $item: $amount"
                        }
                    }
                    listOf(
                        ProbeRecord(
                            id = "runtime-download",
                            pass = true,
                            detail = installed,
                            durationMs = System.currentTimeMillis() - started,
                        ),
                    )
                } catch (failure: Exception) {
                    listOf(
                        ProbeRecord(
                            id = "runtime-download",
                            pass = false,
                            detail = "${failure.javaClass.simpleName}: ${failure.message}",
                            durationMs = System.currentTimeMillis() - started,
                        ),
                    )
                }
            }
        })
        root.addView(button("Probe entrypoints (S1/S3 packaging)") {
            runProbes("entrypoints") { SpikeProbe.probeEntrypoints(this) }
        })
        root.addView(button("Run loader/opencode --version (S1)") {
            runProbes("loader") { listOf(SpikeProbe.runLoader(this)) }
        })
        root.addView(button("Start authenticated server + mock prompt (S1)") {
            runProbes("S1-server-mock") { SpikeServerProbe.run(this) }
        })
        root.addView(button("Run all available device probes (S1/S3)") {
            runProbes("device-suite") { runAvailableDeviceSuite() }
        })
        root.addView(button("Run PRoot guest shell (S3 partial)") {
            runProbes("guest-shell") { listOf(SpikeProbe.runGuestShell(this)) }
        })
        root.addView(button("Compare guest shell + PTY byte smoke (S3 / TERM-5 partial)") {
            runProbes("guest-forkpty") { SpikeProbe.runGuestPtyComparison(this) }
        })

        root.addView(label("S7 lifecycle + child-workload diagnostics (harness service only)"))
        soakView = monospace()
        root.addView(soakView)
        root.addView(button("Start S7 10-minute soak with child workload") {
            confirmBatteryOptimizationForSoak(10)
        })
        root.addView(button("Start S7 30-minute soak with child workload") {
            confirmBatteryOptimizationForSoak(30)
        })
        root.addView(button("Check S7 result") { finishSpikeService() })

        root.addView(label("S8 keyboard/IME probe"))
        root.addView(label("Select the keyboard to test; use its keys, paste and selection. Key events are recorded; typed text is not."))
        imeView = monospace().apply { text = "No input captured yet." }
        root.addView(imeView)
        imeField = ImeProbeEditText(this).apply {
            hint = "Type, use extra keys, paste and select text here."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            minLines = 3
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(96),
            )
            eventListener = { event ->
                imeView.text = "${imeView.text}\n$event"
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    imeTextChanges++
                    imeField.recordTextChanged(s?.length ?: 0)
                }
            })
        }
        root.addView(imeField)
        root.addView(button("Record this keyboard's observation") { recordImeObservation() })

        root.addView(label("Results"))
        resultsView = monospace().apply { setTextIsSelectable(true) }
        root.addView(resultsView)

        root.addView(button("Write JSON report") { writeReport() })
        root.addView(button("Clear results") {
            SpikeReport.clear()
            imeField.setText("")
            imeField.resetCounters()
            imeTextChanges = 0
            imeObservationNumber = 0
            imeView.text = "No input captured yet."
            resultsView.text = ""
            statusView.text = "Cleared"
        })
        statusView = label("Ready")
        root.addView(statusView)
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 8, 0, 4)
    }

    private fun monospace(): TextView = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextIsSelectable(true)
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        try {
            deviceView.text = SpikeProbe.deviceInfo(this).entries.joinToString("\n") { "${it.key}: ${it.value}" }
            runtimeView.text = "Runtime directory: ${SpikeProbe.runtimeDir(this)}\n" + SpikeProbe.runtimeSummary(this)
        } catch (failure: Throwable) {
            val detail = "Refresh failed: ${failure.javaClass.simpleName}: ${failure.message}"
            Log.e(TAG, detail, failure)
            statusView.text = detail
        }
    }

    private fun runProbes(label: String, probe: () -> List<ProbeRecord>) {
        if (probesRunning) {
            statusView.text = "A probe is already running; wait for it to finish"
            return
        }
        probesRunning = true
        statusView.text = "Running $label..."
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        executor.execute {
            val outcome = try {
                probe()
            } catch (t: Throwable) {
                listOf(ProbeRecord("harness-error", false, "${t.javaClass.simpleName}: ${t.message}"))
            }
            outcome.forEach { SpikeReport.add(it) }
            runOnUiThread {
                outcome.forEach(::append)
                probesRunning = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                statusView.text = "$label finished"
                refresh()
            }
        }
    }

    private fun runAvailableDeviceSuite(): List<ProbeRecord> {
        val records = mutableListOf<ProbeRecord>()
        fun capture(id: String, probe: () -> List<ProbeRecord>) {
            try {
                records += probe()
            } catch (failure: Exception) {
                records += ProbeRecord(
                    id = "device-suite-$id",
                    pass = false,
                    detail = "${failure.javaClass.simpleName}: ${failure.message}",
                )
            }
        }

        val startedAt = System.currentTimeMillis()
        capture("entrypoints") { SpikeProbe.probeEntrypoints(this) }
        capture("loader") { listOf(SpikeProbe.runLoader(this)) }
        capture("server-mock") { SpikeServerProbe.run(this) }
        capture("guest-shell") { listOf(SpikeProbe.runGuestShell(this)) }
        capture("native-pty") { SpikeProbe.runGuestPtyComparison(this) }

        val passed = records.count { it.pass }
        records += ProbeRecord(
            id = "device-suite-summary",
            pass = records.isNotEmpty() && passed == records.size,
            detail = "available S1/S3 and scripted TERM-5 partial probes: $passed/${records.size} passed; " +
                "S7 10-minute quick diagnostic and S8 keyboard observations are separate manual tests; " +
                "this isolated harness run is not G0 acceptance",
            durationMs = System.currentTimeMillis() - startedAt,
        )
        return records
    }

    private fun append(record: ProbeRecord) {
        val verdict = if (record.pass) "PASS" else "FAIL"
        resultsView.append("[$verdict] ${record.id} (${record.durationMs} ms)\n${record.detail}\n")
        if (record.command.isNotEmpty()) {
            resultsView.append("$ ${record.command.joinToString(" ")}\n")
        }
        if (record.stdout.isNotBlank()) {
            resultsView.append("stdout:\n${record.stdout.takeLast(4_000)}\n")
        }
        if (record.stderr.isNotBlank()) {
            resultsView.append("stderr:\n${record.stderr.takeLast(4_000)}\n")
        }
        resultsView.append("\n")
    }

    private fun confirmBatteryOptimizationForSoak(minutes: Int) {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            startSpikeService(minutes)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Battery optimization for S7")
            .setMessage(
                "S7 holds a partial wake lock only during this ${minutes}-minute harness diagnostic " +
                    "and supervises a PRoot/Alpine child workload (child-process survival). " +
                    "A temporary battery-optimization exemption can reduce OEM background restrictions. " +
                    "It increases battery use; restore the setting after testing. Infinix and Nothing may " +
                    "also require OEM autostart/background settings. You can run without the exemption, " +
                    "but the report will record that state.",
            )
            .setPositiveButton("Open exemption settings") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName")),
                )
            }
            .setNegativeButton("Run without exemption") { _, _ -> startSpikeService(minutes) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun startSpikeService(minutes: Int) {
        val intent = Intent(this, SpikeForegroundService::class.java)
            .setAction(SpikeForegroundService.ACTION_START_SOAK)
            .putExtra(SpikeForegroundService.EXTRA_SOAK_MINUTES, minutes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        val record = ProbeRecord(
            "S7-soak-start-requested",
            true,
            "${minutes}-minute soak with child workload requested. Keep a real agent task active for " +
                "product acceptance; this isolated harness run does not satisfy the 30-minute LC-2 gate.",
        )
        SpikeReport.add(record)
        append(record)
        statusView.text = "S7 ${minutes}-minute soak started"
        refreshSoakStatus()
    }

    private fun finishSpikeService() {
        val result = SpikeForegroundService.completeSoak(this)
        if (result == null) {
            statusView.text = "S7 result unavailable: the configured " +
                "${SpikeForegroundService.minimumSoakMinutes(this)}-minute soak has not completed; " +
                "the service remains active"
            refreshSoakStatus()
            return
        }
        SpikeReport.upsert(result)
        append(result)
        SpikeChildProcessProbe.stopAndAnalyze(this, "manual-stop")?.let {
            SpikeReport.upsert(it)
            append(it)
        }
        stopService(Intent(this, SpikeForegroundService::class.java))
        statusView.text = "S7 completed result recorded"
        refreshSoakStatus()
    }

    private fun writeReport() {
        if (SpikeForegroundService.isEarlyRunActive(this)) {
            statusView.text = "Report export blocked until the S7 soak completes"
            return
        }
        recordImeObservation()
        SpikeForegroundService.completeSoak(this)?.let {
            SpikeReport.upsert(it)
            SpikeChildProcessProbe.stopAndAnalyze(this, "report-export")?.let { child ->
                SpikeReport.upsert(child)
            }
            stopService(Intent(this, SpikeForegroundService::class.java))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            statusView.text = "Allow storage access, then tap Write JSON report again"
            return
        }
        statusView.text = "Writing report..."
        executor.execute {
            val report = try {
                SpikeReport.write(this)
            } catch (error: Exception) {
                runOnUiThread { statusView.text = "Report failed: ${error.message ?: error.javaClass.simpleName}" }
                null
            }
            if (report != null) {
                runOnUiThread {
                    statusView.text = "Saved report to ${report.downloadLocation}"
                    append(ProbeRecord("report", true, "wrote ${report.downloadLocation}"))
                }
            }
        }
    }

    private companion object {
        private const val TAG = "SpikeHarness"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::soakView.isInitialized) refreshSoakStatus()
    }

    private fun refreshSoakStatus() {
        if (!::soakView.isInitialized) return
        soakView.text = SpikeForegroundService.soakStatus(this)
    }

    private fun recordImeObservation() {
        val hasInput = imeTextChanges > 0 ||
            imeField.commitTextEvents > 0 ||
            imeField.composingTextEvents > 0 ||
            imeField.imeSendKeyEvents > 0 ||
            imeField.hardwareKeyDownEvents > 0 ||
            imeField.hardwareKeyUpEvents > 0
        if (!hasInput) return
        val ime = SpikeProbe.defaultInputMethod(this)
        val record = ProbeRecord(
            id = "S8-ime-observation-${++imeObservationNumber}",
            pass = hasInput,
            detail = "textChanges=$imeTextChanges; finalTextLength=${imeField.text.length}; " +
                "hardwareKeyDownEvents=${imeField.hardwareKeyDownEvents}; " +
                "hardwareKeyUpEvents=${imeField.hardwareKeyUpEvents}; " +
                "imeSendKeyEvents=${imeField.imeSendKeyEvents}; " +
                "commitTextEvents=${imeField.commitTextEvents}; " +
                "composingTextEvents=${imeField.composingTextEvents}; " +
                "deleteSurroundingEvents=${imeField.deleteSurroundingEvents}; " +
                "editorActions=${imeField.editorActions}; " +
                "contentCommits=${imeField.contentCommits}; " +
                "selectionChanges=${imeField.selectionChanges}; " +
                "contextMenuEvents=${imeField.contextMenuEvents}; " +
                "lastEvent=${imeField.lastEvent}; defaultIme=$ime; " +
                "eventTraceDropped=${imeField.eventTraceDroppedCount()}; " +
                "eventTrace=${imeField.eventTraceSnapshot().joinToString(" | ")}; " +
                "captureModel=InputConnection+TextWatcher+hardwareKey; " +
                "keyboardAcceptance=not-assessed; input text omitted",
        )
        SpikeReport.add(record)
        append(record)
        imeField.setText("")
        imeField.resetCounters()
        imeTextChanges = 0
        imeView.text = "Recorded $ime; start the next keyboard's observation."
    }
}
