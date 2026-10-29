package io.github.spectordev9.opencode.spike

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

object HarnessProbeRunner {
    data class State(
        val running: Boolean,
        val label: String?,
        val statusMessage: String,
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "harness-probe").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var running = false
    private var label: String? = null
    private var statusMessage = "Ready"

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    fun start(
        label: String,
        probe: (Context, (String) -> Unit) -> List<ProbeRecord>,
        onFinished: ((List<ProbeRecord>) -> Unit)? = null,
    ): Boolean {
        val context = synchronized(this) {
            if (running) return false
            val initialized = appContext ?: return false
            running = true
            this.label = label
            statusMessage = "Running $label..."
            SpikeReport.setActiveProbe(label)
            initialized
        }
        notifyState()
        executor.execute {
            val outcome = try {
                probe(context, ::updateStatus)
            } catch (failure: Throwable) {
                listOf(
                    ProbeRecord(
                        id = "harness-error",
                        pass = false,
                        detail = "${failure.javaClass.simpleName}: ${failure.message}",
                    ),
                )
            }
            outcome.forEach { SpikeReport.add(it) }
            mainHandler.post {
                val finishedLabel = synchronized(this) {
                    running = false
                    val current = this.label
                    this.label = null
                    statusMessage = "${current ?: "probe"} finished"
                    current
                }
                SpikeReport.setActiveProbe(null)
                onFinished?.invoke(outcome)
                notifyState()
            }
        }
        return true
    }

    fun isRunning(): Boolean = synchronized(this) { running }

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
        mainHandler.post { listener(currentState()) }
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    private fun updateStatus(message: String) {
        synchronized(this) { statusMessage = message }
        notifyState()
    }

    private fun currentState(): State = synchronized(this) {
        State(running = running, label = label, statusMessage = statusMessage)
    }

    private fun notifyState() {
        mainHandler.post {
            val state = currentState()
            listeners.toList().forEach { listener -> listener(state) }
        }
    }
}
