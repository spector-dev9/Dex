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
        val firstMinute = if (samples.isEmpty()) 100.0 else samples.take(12).average()
        val lastFiveMinutes = if (samples.isEmpty()) 100.0 else samples.takeLast(60).average()
        val p95 = percentile95(samples)
        val processUsage = processDeltas(pid)
        val threadUsage = threadDeltas(pid)
        val topThreads = topThreads(pid)
        val serverLog = session.lastLog(1_200).replace('\n', ' ').replace(Regex("\\s+"), " ")
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
            append(" firstMinuteAvg=${"%.3f".format(firstMinute)}% lastFiveMinAvg=${"%.3f".format(lastFiveMinutes)}%")
            append(" p95Cpu=${"%.2f".format(p95)}%")
            append(" stoppedAfterIdle=$stopped postStopTicks=$cpuAfterStop")
            append(" idleTimeout=10min default")
            append(" processDeltas=[${processUsage.joinToString("; ")}]")
            append(" threadDeltas=[${threadUsage.joinToString("; ")}]")
            append(" topSnapshot=[${topThreads.joinToString("; ")}]")
            append(" serverLogTail=[${serverLog.take(1_200)}]")
            append(" samplesCsv=[${samples.joinToString(",") { "%.2f".format(it) }}]")
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
            .filter {
                it.trim().firstOrNull()?.isDigit() == true &&
                    !it.contains("%cpu", ignoreCase = true)
            }
            .take(5)
            .map { it.replace(Regex("\\s+"), " ").trim().take(140) }
            .toList()
    }.getOrDefault(emptyList())

    private fun processDeltas(rootPid: Int): List<String> {
        val before = processTree(rootPid)
        Thread.sleep(2_000)
        val after = processTree(rootPid)
        return after.values.mapNotNull { current ->
            val previous = before[current.pid] ?: return@mapNotNull null
            val delta = max(0L, current.ticks - previous.ticks)
            if (delta == 0L) return@mapNotNull null
            ProcessUsage(
                pid = current.pid,
                parent = current.parent,
                comm = processComm(current.pid),
                percent = delta * 100.0 / (2.0 * CLOCK_TICKS),
                rssMiB = current.rssPages * PAGE_SIZE_BYTES / (1024 * 1024),
            )
        }.sortedByDescending { it.percent }
            .take(8)
            .map {
                "pid=${it.pid} ppid=${it.parent} comm=${it.comm} " +
                    "cpu=${"%.2f".format(it.percent)}% rssMiB=${it.rssMiB}"
            }
    }

    private fun threadDeltas(pid: Int): List<String> {
        val before = threadTicks(pid)
        Thread.sleep(2_000)
        val after = threadTicks(pid)
        return after.mapNotNull { (tid, ticks) ->
            val previous = before[tid] ?: return@mapNotNull null
            val delta = max(0L, ticks - previous)
            if (delta == 0L) return@mapNotNull null
            ThreadUsage(
                tid = tid,
                comm = threadComm(pid, tid),
                percent = delta * 100.0 / (2.0 * CLOCK_TICKS),
            )
        }.sortedByDescending { it.percent }
            .take(10)
            .map { "tid=${it.tid} comm=${it.comm} cpu=${"%.2f".format(it.percent)}%" }
    }

    private fun percentile95(values: List<Long>): Long {
        if (values.isEmpty()) return Long.MAX_VALUE
        val sorted = values.sorted()
        val index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun percentile95(values: List<Double>): Double {
        if (values.isEmpty()) return 100.0
        val sorted = values.sorted()
        val index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun processTreeTicks(rootPid: Int): Long =
        processTree(rootPid).values.sumOf { it.ticks }

    private fun processTree(rootPid: Int): Map<Int, ProcStat> {
        val all = mutableMapOf<Int, ProcStat>()
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            readProcStat(pid)?.let { all[pid] = it }
        }
        val tree = mutableSetOf(rootPid)
        var changed = true
        while (changed) {
            changed = false
            all.values.forEach { process ->
                if (process.parent in tree && tree.add(process.pid)) changed = true
            }
        }
        return tree.mapNotNull { pid -> all[pid]?.let { pid to it } }.toMap()
    }

    private fun readProcStat(pid: Int): ProcStat? = runCatching {
        val text = File("/proc/$pid/stat").readText()
        val close = text.lastIndexOf(')')
        if (close < 0) return null
        val fields = text.substring(close + 2).split(' ')
        if (fields.size < 22) return null
        val parent = fields[1].toIntOrNull() ?: return null
        val ticks = (fields[11].toLongOrNull() ?: 0L) + (fields[12].toLongOrNull() ?: 0L)
        val rssPages = fields[21].toLongOrNull() ?: 0L
        ProcStat(pid, parent, ticks, rssPages)
    }.getOrNull()

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

    private fun threadTicks(pid: Int): Map<Int, Long> = runCatching {
        File("/proc/$pid/task").listFiles()?.mapNotNull { entry ->
            val tid = entry.name.toIntOrNull() ?: return@mapNotNull null
            val ticks = readStat(tid)?.second ?: return@mapNotNull null
            tid to ticks
        }?.toMap() ?: emptyMap()
    }.getOrDefault(emptyMap())

    private fun processComm(pid: Int): String = runCatching {
        File("/proc/$pid/comm").readText().trim().replace(Regex("\\s+"), "_")
    }.getOrDefault("?")

    private fun threadComm(pid: Int, tid: Int): String = runCatching {
        File("/proc/$pid/task/$tid/comm").readText().trim().replace(Regex("\\s+"), "_")
    }.getOrDefault("?")

    private data class ProcStat(
        val pid: Int,
        val parent: Int,
        val ticks: Long,
        val rssPages: Long,
    )

    private data class ProcessUsage(
        val pid: Int,
        val parent: Int,
        val comm: String,
        val percent: Double,
        val rssMiB: Long,
    )

    private data class ThreadUsage(
        val tid: Int,
        val comm: String,
        val percent: Double,
    )

    private const val CLOCK_TICKS = 100.0
    private const val PAGE_SIZE_BYTES = 4096L
    private fun elapsed(started: Long): Long = System.currentTimeMillis() - started
}
