package io.github.spectordev9.opencode.spike

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var deviceView: TextView
    private lateinit var runtimeView: TextView
    private lateinit var resultsView: TextView
    private lateinit var statusView: TextView
    private lateinit var gateSummaryView: TextView
    private val gateStatusViews = mutableMapOf<String, TextView>()

    private val probeListener: (HarnessProbeRunner.State) -> Unit = { state ->
        if (::statusView.isInitialized) {
            statusView.text = state.statusMessage
            if (state.running) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                renderResults()
                refreshGateStatuses()
                refreshGateSummary()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HarnessCrashHandler.install(this)
        buildUi()
        refresh()
        requestNotificationPermission()
        HarnessProbeRunner.addListener(probeListener)
        renderResults()
        refreshGateStatuses()
        refreshGateSummary()
    }

    override fun onDestroy() {
        HarnessProbeRunner.removeListener(probeListener)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        root.addView(button("G3 diagnostics (WS-2/3/4, LC-3/4, SEC-2)") {
            startActivity(Intent(this, G3ChecksActivity::class.java))
        })

        root.addView(label("Device"))
        deviceView = monospace()
        root.addView(deviceView)

        root.addView(label("Runtime layout"))
        runtimeView = monospace()
        root.addView(runtimeView)

        root.addView(label("G0 acceptance checks"))
        root.addView(label("Tap a gate to run it. Gates that need production wiring, external artifacts, or owner decisions record blocked/manual status instead of a false pass."))
        gateSummaryView = monospace().apply { text = "G0: NOT GREEN" }
        root.addView(gateSummaryView)
        G0GateCatalog.checks.forEach { check ->
            root.addView(button("${check.id}: ${check.title}") { runGateCheck(check) })
            gateStatusViews[check.id] = monospace().apply {
                val existing = SpikeReport.g0GateResult(check.id)
                text = existing?.let { "${it.status.uppercase()} · ${it.detail}" }
                    ?: "PENDING · ${check.requirement}"
            }
            root.addView(gateStatusViews.getValue(check.id))
        }
        refreshGateSummary()

        root.addView(label("Runtime and probe actions"))
        root.addView(button("Download and verify pinned runtime (large download)") {
            runProbes(
                label = "runtime-download",
                probe = { context, status ->
                    val started = SystemClock.elapsedRealtime()
                    try {
                        val installed = SpikeRuntimeInstaller.install(context) { item, bytes, total ->
                            val amount = if (total > 0) {
                                "${bytes / 1_048_576} / ${total / 1_048_576} MiB"
                            } else {
                                "${bytes / 1_048_576} MiB"
                            }
                            status("Downloading $item: $amount")
                        }
                        listOf(
                            ProbeRecord(
                                id = "runtime-download",
                                pass = true,
                                detail = installed,
                                durationMs = SystemClock.elapsedRealtime() - started,
                            ),
                        )
                    } catch (failure: Exception) {
                        listOf(
                            ProbeRecord(
                                id = "runtime-download",
                                pass = false,
                                detail = "${failure.javaClass.simpleName}: ${failure.message}",
                                durationMs = SystemClock.elapsedRealtime() - started,
                            ),
                        )
                    }
                },
            )
        })
        root.addView(button("Probe entrypoints (S1/S3 packaging)") {
            runProbes("entrypoints", probe = { context, _ -> SpikeProbe.probeEntrypoints(context) })
        })
        root.addView(button("Run loader/opencode --version (S1)") {
            runProbes("loader", probe = { context, _ -> listOf(SpikeProbe.runLoader(context)) })
        })
        root.addView(button("Start authenticated server + mock prompt (S1)") {
            runProbes("S1-server-mock", probe = { context, _ -> SpikeServerProbe.run(context) })
        })
        root.addView(button("Run all available device probes (S1/S3)") {
            runProbes("device-suite", probe = { context, _ -> runAvailableDeviceSuite(context) })
        })
        root.addView(button("Run PRoot guest shell (S3 partial)") {
            runProbes("guest-shell", probe = { context, _ -> listOf(SpikeProbe.runGuestShell(context)) })
        })
        root.addView(label("Results"))
        resultsView = monospace().apply { setTextIsSelectable(true) }
        root.addView(resultsView)

        root.addView(button("Write JSON report") { writeReport() })
        root.addView(button("Clear results") {
            if (HarnessProbeRunner.isRunning()) {
                statusView.text = "Wait for the running probe to finish before clearing results"
            } else {
                SpikeReport.clear()
                renderResults()
                refreshGateStatuses()
                refreshGateSummary()
                statusView.text = "Cleared"
            }
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

    private fun runProbes(
        label: String,
        probe: (Context, (String) -> Unit) -> List<ProbeRecord>,
        onComplete: ((List<ProbeRecord>) -> Unit)? = null,
    ) {
        if (!HarnessProbeRunner.start(label, probe, onComplete)) {
            statusView.text = "A probe is already running; wait for it to finish"
        }
    }

    private fun runGateCheck(check: G0GateCheck) {
        when (check.id) {
            "S1-A" -> runProbes(
                "S1-A ${check.title}",
                { context, _ ->
                    listOf(SpikeProbe.runLoader(context)) + SpikeServerProbe.run(context)
                },
            ) { records ->
                val passed = records.isNotEmpty() && records.all { it.pass }
                SpikeReport.setG0GateResult(
                    check.id,
                    if (passed) "accepted" else "fail",
                    if (passed) {
                        "Owner-accepted harness evidence: loader, authenticated health, and mock prompt passed; " +
                            "production Profile A wiring and Profile B remain post-G0 follow-ups."
                    } else {
                        "Harness Profile A probe failed; see result records."
                    },
                )
            }
            "S1-B" -> setGateResult(
                check.id,
                "blocked",
                "Profile B is not implemented in this harness or the production app.",
            )
            "S2-cold", "S2-rss", "S2-git", "S2-npm" -> setGateResult(
                check.id,
                "blocked",
                "Profile A/B production measurements are unavailable; Profile B is not implemented.",
            )
            "S3" -> runProbes(
                "S3 ${check.title}",
                { context, _ -> SpikeProbe.runGuestPtyComparison(context) },
            ) { records ->
                val passed = records.isNotEmpty() && records.all { it.pass }
                SpikeReport.setG0GateResult(
                    check.id,
                    if (passed) "accepted" else "fail",
                    if (passed) {
                        "Owner-accepted harness evidence: PRoot guest shell and native-PTY environment matched; " +
                            "the production agent-shell comparison remains a post-G0 follow-up."
                    } else {
                        "Guest shell/native-PTY probe failed; see result records."
                    },
                )
            }
            "S4" -> setGateResult(
                check.id,
                "partial",
                "Host SSE/resync checks passed; app/UI device acceptance remains open.",
            )
            "S5" -> setGateResult(
                check.id,
                "partial",
                "Host endpoint/auth inventory passed; app/UI device reconciliation remains open.",
            )
            "S6" -> setGateResult(
                check.id,
                "deferred",
                "No distributable OpenCode 2.x artifact is available; retain the documented 1.19.3 pin.",
            )
            "S9" -> setGateResult(
                check.id,
                "manual",
                "Licensing, source-offer, signing, and distribution compliance require owner/release work.",
            )
            "BASELINES" -> setGateResult(
                check.id,
                "manual",
                "Reference-device baselines and numeric thresholds still need to be recorded and ratified.",
            )
            "D1" -> setGateResult(
                check.id,
                "partial",
                "Distribution decision is accepted; compliance evidence remains open.",
            )
            "D4" -> setGateResult(
                check.id,
                "manual",
                "Runtime profile cannot be frozen until admissible S1/S2/S3 evidence exists.",
            )
            "D5" -> setGateResult(
                check.id,
                "partial",
                "OpenCode 1.19.3 pin is documented; the 2.x disposition remains open with S6.",
            )
            else -> setGateResult(check.id, "manual", check.requirement)
        }
    }

    private fun setGateResult(id: String, status: String, detail: String) {
        SpikeReport.setG0GateResult(id, status, detail)
        gateStatusViews[id]?.text = "${status.uppercase()} · $detail"
        refreshGateSummary()
    }

    private fun refreshGateStatuses() {
        G0GateCatalog.checks.forEach { check ->
            val existing = SpikeReport.g0GateResult(check.id)
            gateStatusViews[check.id]?.text = existing?.let { "${it.status.uppercase()} · ${it.detail}" }
                ?: "PENDING · ${check.requirement}"
        }
    }

    private fun refreshGateSummary() {
        val total = G0GateCatalog.checks.size
        val results = G0GateCatalog.checks.mapNotNull { SpikeReport.g0GateResult(it.id) }
        val passed = results.count { it.status == "pass" }
        val accepted = results.count { it.status == "accepted" }
        val open = results.count { it.status !in setOf("pass", "accepted") }
        val pending = total - results.size
        val green = results.size == total && open == 0
        gateSummaryView.text = if (green) {
            "G0: GREEN · $passed passed, $accepted accepted · $total/$total checks closed"
        } else {
            "G0: NOT GREEN · $passed passed, $accepted accepted, $open open, $pending pending"
        }
    }

    private fun runAvailableDeviceSuite(context: Context): List<ProbeRecord> {
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

        val startedAt = SystemClock.elapsedRealtime()
        capture("entrypoints") { SpikeProbe.probeEntrypoints(context) }
        capture("loader") { listOf(SpikeProbe.runLoader(context)) }
        capture("server-mock") { SpikeServerProbe.run(context) }
        capture("native-pty") { SpikeProbe.runGuestPtyComparison(context) }

        val passed = records.count { it.pass }
        records += ProbeRecord(
            id = "device-suite-summary",
            pass = records.isNotEmpty() && passed == records.size,
            detail = "available S1/S3 probes: $passed/${records.size} passed; " +
                "this isolated harness run is not G0 acceptance",
            durationMs = SystemClock.elapsedRealtime() - startedAt,
        )
        return records
    }

    private fun renderResults() {
        if (!::resultsView.isInitialized) return
        resultsView.text = ""
        SpikeReport.snapshot().forEach(::append)
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

    private fun writeReport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            statusView.text = "Allow storage access, then tap Write JSON report again"
            return
        }
        statusView.text = "Writing report..."
        val context = applicationContext
        Thread {
            val report = try {
                SpikeReport.write(context)
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        statusView.text = "Report failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
                null
            }
            if (report != null) {
                val record = ProbeRecord("report", true, "wrote ${report.downloadLocation}")
                SpikeReport.add(record)
                runOnUiThread {
                    if (!isDestroyed) {
                        statusView.text = "Saved report to ${report.downloadLocation}"
                        append(record)
                    }
                }
            }
        }.apply { name = "harness-report"; isDaemon = true }.start()
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

}
