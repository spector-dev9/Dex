package io.github.spectordev9.opencode.spike

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        root.addView(button("Probe entrypoints (S1/S3 packaging)") {
            runProbes("entrypoints") { SpikeProbe.probeEntrypoints(this) }
        })
        root.addView(button("Run loader/opencode --version (S1)") {
            runProbes("loader") { listOf(SpikeProbe.runLoader(this)) }
        })
        root.addView(button("Run PRoot guest shell (S3 partial)") {
            runProbes("guest-shell") { listOf(SpikeProbe.runGuestShell(this)) }
        })
        root.addView(button("Compare guest shell with native forkpty (S3)") {
            runProbes("guest-forkpty") { SpikeProbe.runGuestPtyComparison(this) }
        })

        root.addView(label("S7 foreground service"))
        root.addView(button("Start specialUse foreground service") { startSpikeService() })
        root.addView(button("Stop foreground service") { stopSpikeService() })

        root.addView(label("S8 keyboard/IME probe"))
        imeView = monospace().apply { text = "No key events yet." }
        root.addView(imeView)
        val imeField = EditText(this).apply {
            hint = "Type, use extra keys, paste and select text here."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            minLines = 3
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(96),
            )
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    imeView.text = "key=${KeyEvent.keyCodeToString(keyCode)} code=$keyCode"
                }
                false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    imeView.text = "${imeView.text}\ntext length=${text.length} tail=${text.takeLast(24)}"
                }
            })
        }
        root.addView(imeField)

        root.addView(label("Results"))
        resultsView = monospace().apply { setTextIsSelectable(true) }
        root.addView(resultsView)

        root.addView(button("Write JSON report") { writeReport() })
        root.addView(button("Clear results") {
            SpikeReport.clear()
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
        deviceView.text = SpikeProbe.deviceInfo(this).entries.joinToString("\n") { "${it.key}: ${it.value}" }
        runtimeView.text = "Runtime directory: ${SpikeProbe.runtimeDir(this)}\n" + SpikeProbe.runtimeSummary(this)
    }

    private fun runProbes(label: String, probe: () -> List<ProbeRecord>) {
        statusView.text = "Running $label..."
        executor.execute {
            val outcome = try {
                probe()
            } catch (t: Throwable) {
                listOf(ProbeRecord("harness-error", false, "${t.javaClass.simpleName}: ${t.message}"))
            }
            outcome.forEach { SpikeReport.add(it) }
            runOnUiThread {
                outcome.forEach(::append)
                statusView.text = "$label finished"
                refresh()
            }
        }
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

    private fun startSpikeService() {
        val intent = Intent(this, SpikeForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        val record = ProbeRecord("S7-fgs-start", true, "startForegroundService requested")
        SpikeReport.add(record)
        append(record)
        statusView.text = "Foreground service start requested"
    }

    private fun stopSpikeService() {
        stopService(Intent(this, SpikeForegroundService::class.java))
        val record = ProbeRecord("S7-fgs-stop", true, "stopService requested")
        SpikeReport.add(record)
        append(record)
        statusView.text = "Foreground service stop requested"
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
