package io.github.spectordev9.opencode.spike

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class G3ChecksActivity : Activity() {
    private lateinit var resultView: TextView
    private lateinit var progressView: TextView
    private lateinit var runtimeView: TextView
    private val buttons = mutableListOf<Button>()
    private val busy = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    private val poller = object : Runnable {
        override fun run() {
            refreshLongRunStatus()
            handler.postDelayed(this, 3_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val title = TextView(this).apply {
            text = "OpenCode G3 diagnostics"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)
        runtimeView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }
        root.addView(runtimeView)
        progressView = TextView(this).apply {
            textSize = 12f
            setPadding(0, 8, 0, 8)
            setTextIsSelectable(true)
        }
        root.addView(progressView)
        resultView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, 8, 0, 24)
            setTextIsSelectable(true)
        }
        root.addView(resultView)

        addButton(root, "Download and verify pinned runtime") {
            runBlockingTask("runtime-download") {
                val version = SpikeRuntimeInstaller.install(this) { message, done, total ->
                    ui { progressView.text = "$message $done/$total" }
                }
                ui {
                    progressView.text = "runtime $version installed and verified"
                    refreshRuntime()
                }
            }
        }
        addButton(root, "WS-2 quick: 100 atomic-write kills") {
            runCheck("WS-2 quick") { G3WorkspaceChecks.runWs2(this, 100, ::progress) }
        }
        addButton(root, "WS-2 full: 1000 atomic-write kills (background)") {
            startLongRun(G3LongRunService.ACTION_WS2, 1000, 0)
        }
        addButton(root, "WS-3: 50 conflict checks") {
            runCheck("WS-3") { G3WorkspaceChecks.runWs3(this, 50, ::progress) }
        }
        addButton(root, "WS-4: 100 MB text + 1 GB binary") {
            runCheck("WS-4") { G3WorkspaceChecks.runWs4(this, ::progress) }
        }
        addButton(root, "LC-3 quick: 5 SIGKILL restarts") {
            runCheck("LC-3 quick", keepScreenOn = true) {
                G3RecoveryChecks.runLc3(this, 5, ::progress)
            }
        }
        addButton(root, "LC-3 full: 50 SIGKILL restarts (background)") {
            startLongRun(G3LongRunService.ACTION_LC3, 50, 0)
        }
        addButton(root, "LC-4: 10-minute idle CPU (background)") {
            startLongRun(G3LongRunService.ACTION_LC4, 0, 10)
        }
        addButton(root, "SEC-2: APK, file, process and denial scan") {
            runCheck("SEC-2") { G3SecurityChecks.runSec2(this, ::progress) }
        }
        addButton(root, "Start SEC-1 server for second-UID client") {
            startSec1Server()
        }
        addButton(root, "Stop SEC-1 server") {
            runBlockingTask("sec1-stop") {
                G3ServerHolder.stop()
                ui { progressView.text = "SEC-1 server stopped" }
            }
        }
        addButton(root, "Write JSON report") {
            runBlockingTask("write-report") {
                val written = SpikeReport.write(this)
                ui {
                    progressView.text = "wrote ${written.internalFile.absolutePath}; ${written.downloadLocation}"
                }
            }
        }
        addButton(root, "Refresh long-run status") {
            refreshLongRunStatus()
        }

        scroll.addView(root)
        setContentView(scroll)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshRuntime()
        renderRecords()
    }

    override fun onResume() {
        super.onResume()
        if (G3LongRunService.isRunning()) {
            busy.set(true)
            setButtonsEnabled(false)
        }
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    override fun onPause() {
        handler.removeCallbacks(poller)
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) {
            G3ServerHolder.stop()
        }
        super.onDestroy()
    }

    private fun addButton(root: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            setOnClickListener { if (!busy.get()) action() }
        }
        buttons += button
        root.addView(button)
    }

    private fun runCheck(label: String, keepScreenOn: Boolean = false, block: () -> ProbeRecord) {
        if (!busy.compareAndSet(false, true)) return
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        setButtonsEnabled(false)
        progressView.text = "$label starting"
        Thread {
            val record = try {
                block()
            } catch (t: Throwable) {
                ProbeRecord(label, false, "failed: ${t.message}")
            }
            SpikeReport.upsert(record)
            ui {
                progressView.text = "$label ${if (record.pass) "PASS" else "FAIL"}"
                renderRecords()
                if (keepScreenOn) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                busy.set(false)
                setButtonsEnabled(true)
            }
        }.apply {
            name = "g3-check"
            isDaemon = true
            start()
        }
    }

    private fun runBlockingTask(label: String, block: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        setButtonsEnabled(false)
        progressView.text = "$label starting"
        Thread {
            try {
                block()
            } catch (t: Throwable) {
                ui { progressView.text = "$label failed: ${t.message}" }
            } finally {
                ui {
                    busy.set(false)
                    setButtonsEnabled(true)
                    renderRecords()
                }
            }
        }.apply {
            name = "g3-task"
            isDaemon = true
            start()
        }
    }

    private fun startLongRun(action: String, runs: Int, minutes: Int) {
        if (busy.get()) return
        val intent = Intent(this, G3LongRunService::class.java).setAction(action)
            .putExtra(G3LongRunService.EXTRA_RUNS, runs)
            .putExtra(G3LongRunService.EXTRA_MINUTES, minutes)
        startForegroundService(intent)
        progressView.text = "long run started; you may leave the app"
        busy.set(true)
        setButtonsEnabled(false)
        handler.removeCallbacks(poller)
        handler.postDelayed(poller, 1_000)
    }

    private fun refreshLongRunStatus() {
        val file = File(filesDir, "g3/long-run.json")
        if (!file.isFile) {
            if (!G3LongRunService.isRunning() && busy.get()) {
                busy.set(false)
                setButtonsEnabled(true)
            }
            return
        }
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        val running = json.optBoolean("running", false)
        val id = json.optString("id")
        val message = json.optString("message")
        val detail = json.optString("detail")
        val pass = json.optBoolean("pass", false)
        progressView.text = "$id ${if (detail.isNotBlank()) if (pass) "PASS" else "FAIL" else ""} $message"
        if (detail.isNotBlank()) {
            resultView.text = "$id pass=$pass durationMs=${json.optLong("durationMs")}\n$detail"
        }
        if (!running && !G3LongRunService.isRunning()) {
            busy.set(false)
            setButtonsEnabled(true)
        }
    }

    private fun startSec1Server() {
        if (busy.get()) return
        runBlockingTask("sec1-server") {
            val session = G3ServerHolder.start(this)
            ui {
                progressView.text = if (session == null) {
                    "SEC-1 server failed: ${G3ServerHolder.lastError}"
                } else {
                    "SEC-1 server listening\nport=${session.port} secret=${session.secret}\n" +
                        "workspace=${session.workspace.absolutePath}\n" +
                        "Open the companion g3-client APK and run the matrix."
                }
            }
        }
    }

    private fun refreshRuntime() {
        val binary = File(SpikeProbe.runtimeDir(this), "runtime/opencode")
        val loader = File(applicationInfo.nativeLibraryDir, "libldmusl.so")
        runtimeView.text = if (binary.isFile && loader.isFile) {
            "runtime ready: ${binary.length()} bytes"
        } else {
            "runtime missing: tap Download and verify pinned runtime"
        }
    }

    private fun renderRecords() {
        val records = SpikeReport.snapshot().filter {
            it.id.startsWith("WS-") || it.id.startsWith("LC-") || it.id == "SEC-2"
        }
        resultView.text = if (records.isEmpty()) {
            G3ServerHolder.describe()
        } else {
            records.joinToString("\n\n") {
                "${it.id} ${if (it.pass) "PASS" else "FAIL"} ${it.durationMs}ms\n${it.detail}"
            }
        }
    }

    private fun progress(message: String) {
        ui { progressView.text = message }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        buttons.forEach { it.isEnabled = enabled }
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread(block)
    }
}

object G3ServerHolder {
    private var session: G3ServerSession? = null
    var lastError: String? = null
        private set

    @Synchronized
    fun start(context: android.content.Context): G3ServerSession? {
        stop()
        val port = if (G3Support.canBind(G3ServerSession.DEFAULT_PORT)) {
            G3ServerSession.DEFAULT_PORT
        } else {
            G3Support.reservePort()
        }
        val candidate = G3ServerSession(context, port = port)
        val started = candidate.start() && candidate.waitForHealth(20_000)
        if (!started) {
            lastError = candidate.lastError ?: candidate.lastLog(500)
            candidate.stop()
            return null
        }
        session = candidate
        lastError = null
        return candidate
    }

    @Synchronized
    fun stop() {
        session?.stop()
        session = null
    }

    @Synchronized
    fun describe(): String {
        val current = session ?: return "no SEC-1 server running"
        return buildString {
            append("SEC-1 server port=${current.port} secret=${current.secret}\n")
            append("workspace=${current.workspace.absolutePath}\n")
            append("client defaults: 127.0.0.1 port 45123 secret g3-test-secret-2026")
        }
    }
}
