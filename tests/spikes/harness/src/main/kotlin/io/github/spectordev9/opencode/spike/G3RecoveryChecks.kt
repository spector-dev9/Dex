package io.github.spectordev9.opencode.spike

import android.content.Context
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

internal object G3RecoveryChecks {
    fun runLc3(
        context: Context,
        runs: Int,
        progress: (String) -> Unit,
    ): ProbeRecord {
        val started = System.currentTimeMillis()
        val port = G3Support.reservePort()
        val session = G3ServerSession(context, port = port)
        if (!session.start() || !session.waitForHealth(20_000)) {
            val detail = "server did not become healthy: ${session.lastError ?: "timeout"}"
            session.stop()
            return ProbeRecord("LC-3", false, detail, durationMs = elapsed(started))
        }
        val sessionId = session.createSession("g3-lc3")
        if (sessionId == null) {
            session.stop()
            return ProbeRecord("LC-3", false, "server did not create a session", durationMs = elapsed(started))
        }
        val durations = mutableListOf<Long>()
        var unhealthy = 0
        var sessionsLost = 0
        var portStuck = 0
        var failed = false
        for (run in 1..runs) {
            progress("LC-3 kill $run/$runs")
            session.kill()
            if (!G3Support.waitForPortFree(port, 5_000)) {
                portStuck++
                failed = true
                break
            }
            val restartStarted = System.currentTimeMillis()
            if (!session.start()) {
                unhealthy++
                failed = true
                break
            }
            val healthy = session.waitForHealth(10_000)
            if (healthy) durations += System.currentTimeMillis() - restartStarted
            if (!healthy) {
                unhealthy++
                failed = true
                break
            }
            if (!session.sessionExists(sessionId)) {
                sessionsLost++
                failed = true
                break
            }
        }
        val p95 = percentile95(durations)
        session.stop()
        G3Support.waitForPortFree(port, 5_000)
        val pass = !failed && runs > 0 && unhealthy == 0 && sessionsLost == 0 &&
            portStuck == 0 && p95 in 0..10_000
        val detail = buildString {
            append("runs=$runs restarts=${durations.size}")
            append(" p95=${p95}ms max=${durations.maxOrNull() ?: 0}ms")
            append(" unhealthy=$unhealthy sessionsLost=$sessionsLost")
            append(" portStuck=$portStuck")
            append(" listenerInventory=hostRequired")
            append(" crashLoopBreaker=notExercised")
        }
        return ProbeRecord("LC-3", pass, detail, durationMs = elapsed(started))
    }

    fun runLc4(
        context: Context,
        minutes: Int,
        progress: (String) -> Unit,
    ): ProbeRecord {
        val started = System.currentTimeMillis()
        val port = G3Support.reservePort()
        val session = G3ServerSession(context, port = port)
        if (!session.start() || !session.waitForHealth(20_000)) {
            val detail = "server did not become healthy: ${session.lastError ?: "timeout"}"
            session.stop()
            return ProbeRecord("LC-4", false, detail, durationMs = elapsed(started))
        }
        val pid = session.pid()
        if (pid == null) {
            session.stop()
            return ProbeRecord("LC-4", false, "server pid unavailable", durationMs = elapsed(started))
        }
        val samples = mutableListOf<Double>()
        var previousTicks = processTreeTicks(pid)
        var previousAt = System.currentTimeMillis()
        val endAt = previousAt + minutes * 60_000L
        Thread.sleep(5_000)
        while (System.currentTimeMillis() < endAt) {
            val now = System.currentTimeMillis()
            val ticks = processTreeTicks(pid)
            val elapsedSeconds = (now - previousAt) / 1000.0
            if (elapsedSeconds > 0 && ticks >= 0) {
                val delta = max(0L, ticks - previousTicks)
                samples += (delta * 100.0) / (elapsedSeconds * CLOCK_TICKS)
            }
            previousTicks = ticks
            previousAt = now
            val remaining = max(0L, (endAt - now) / 1000)
            progress("LC-4 idle ${samples.size} samples, ${remaining}s left, last=${samples.lastOrNull()?.let { "%.2f".format(it) } ?: "0"}%")
            Thread.sleep(5_000)
        }
        val average = if (samples.isEmpty()) 100.0 else samples.average()
        val maximum = samples.maxOrNull() ?: 100.0
        val topThreads = topThreads(pid)
        val aliveBeforeStop = session.isAlive()
        val stopped = runCatching {
            session.stop()
            G3Support.waitForPortFree(port, 5_000)
        }.getOrDefault(false)
        Thread.sleep(2_000)
        val cpuAfterStop = processTreeTicks(pid)
        val pass = aliveBeforeStop && stopped && average <= 1.0 && cpuAfterStop == 0L
        val detail = buildString {
            append("idleMinutes=$minutes samples=${samples.size}")
            append(" avgCpu=${"%.3f".format(average)}% maxCpu=${"%.2f".format(maximum)}%")
            append(" stoppedAfterIdle=$stopped postStopTicks=$cpuAfterStop")
            append(" idleTimeout=10min default")
            append(" topThreads=[${topThreads.joinToString("; ")}]")
        }
        return ProbeRecord("LC-4", pass, detail, durationMs = elapsed(started))
    }

    private fun topThreads(pid: Int): List<String> = runCatching {
        val process = ProcessBuilder("/system/bin/top", "-H", "-b", "-n", "1", "-p", pid.toString())
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text.lineSequence()
            .filter { it.trim().firstOrNull()?.isDigit() == true }
            .take(5)
            .map { it.replace(Regex("\\s+"), " ").trim().take(140) }
            .toList()
    }.getOrDefault(emptyList())

    private fun percentile95(values: List<Long>): Long {
        if (values.isEmpty()) return Long.MAX_VALUE
        val sorted = values.sorted()
        val index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun processTreeTicks(rootPid: Int): Long {
        val parents = mutableMapOf<Int, Int>()
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            readStat(pid)?.let { parents[pid] = it.first }
        }
        val tree = mutableSetOf(rootPid)
        var changed = true
        while (changed) {
            changed = false
            parents.forEach { (pid, parent) ->
                if (parent in tree && tree.add(pid)) changed = true
            }
        }
        return tree.sumOf { readStat(it)?.second ?: 0L }
    }

    private fun readStat(pid: Int): Pair<Int, Long>? = runCatching {
        val text = File("/proc/$pid/stat").readText()
        val close = text.lastIndexOf(')')
        if (close < 0) return null
        val fields = text.substring(close + 2).split(' ')
        if (fields.size < 13) return null
        val parent = fields[1].toIntOrNull() ?: return null
        val ticks = (fields[11].toLongOrNull() ?: 0L) + (fields[12].toLongOrNull() ?: 0L)
        parent to ticks
    }.getOrNull()

    private const val CLOCK_TICKS = 100.0
    private fun elapsed(started: Long): Long = System.currentTimeMillis() - started
}
