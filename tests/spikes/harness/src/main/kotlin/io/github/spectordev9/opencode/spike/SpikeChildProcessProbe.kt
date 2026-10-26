package io.github.spectordev9.opencode.spike

import android.content.Context
import android.os.Process as AndroidProcess
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * S7 child-process survival probe (isolated harness only).
 *
 * LC-2/S7 must not be inferred from foreground-service survival: on modern
 * Android an app process and its child workloads can be killed or frozen
 * ("phantom process" handling) while the foreground service keeps
 * heartbeating. This probe starts a real PRoot/Alpine guest workload as a
 * child process of the app, has it append a sample line to
 * `/root/s7-child.log` every 10 s, supervises liveness from the service
 * heartbeat, and analyzes the samples for coverage, stalls, index resets and
 * early exit when the soak ends.
 *
 * Guest sample line: `<wall-clock-seconds> <kernel-uptime-seconds> <index>`.
 * The guest file is host-visible at
 * `<filesDir>/spike/rootfs/root/s7-child.log`.
 */
object SpikeChildProcessProbe {
    private const val TAG = "SpikeHarness"

    const val RECORD_ID = "S7-child-process-survival"
    const val START_RECORD_ID = "S7-child-process-start"
    const val SAMPLE_INTERVAL_MS = 10_000L
    const val STALL_THRESHOLD_MS = 25_000L

    private const val MINIMUM_COVERAGE = 0.85
    private const val STARTUP_SLACK_MS = 15_000L
    private const val MAX_STDERR_TAIL_CHARS = 2_000
    private const val LOG_RELATIVE_TO_ROOTFS = "root/s7-child.log"

    // The shell PID (`$$`) is deliberately not recorded: PRoot rewrites guest
    // PIDs, so it is not a stable identifier outside the guest. The sample
    // index is the single-writer continuity marker instead.
    private val GUEST_SCRIPT = """
        i=0
        while :; do
          i=${'$'}((i+1))
          now=${'$'}(date +%s 2>/dev/null || echo 0)
          up=${'$'}(cut -d. -f1 /proc/uptime 2>/dev/null || echo 0)
          echo "${'$'}now ${'$'}up ${'$'}i" >> /root/s7-child.log
          sleep 10 || exit 3
        done
    """.trimIndent()

    private class Session(
        val process: Process,
        val tracerPid: Long,
        val logFile: File,
        val stderrFile: File,
        val startedAtWallClockMs: Long,
        val startedAtUptimeMs: Long,
    ) {
        var hostChecks = 0
        var hostAliveChecks = 0
        var hostDeadChecks = 0
    }

    private data class ChildSample(
        val wallClockMs: Long,
        val uptimeMs: Long,
        val index: Int,
    )

    private var session: Session? = null
    private var childStartedThisProcess = false
    private var analysisCompleted = false

    fun hostUptimeMs(): Long = try {
        (File("/proc/uptime").readText().trim().substringBefore(' ').toDouble() * 1000.0).toLong()
    } catch (_: Throwable) {
        -1L
    }

    /** Starts the guest child workload and returns its start record. */
    fun start(context: Context): ProbeRecord {
        abort()
        analysisCompleted = false
        val root = SpikeProbe.runtimeDir(context)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDir, "libproot.so").takeIf { it.isFile }
            ?: File(root, "proot").takeIf { it.isFile }
        val loader = File(nativeDir, "libproot_loader.so").takeIf { it.isFile }
            ?: File(root, "loader").takeIf { it.isFile }
        val rootfs = File(root, "rootfs")
        if (proot == null || loader == null || !SpikeProbe.rootfsFileExists(rootfs, "bin/sh")) {
            childStartedThisProcess = false
            analysisCompleted = true
            return ProbeRecord(
                START_RECORD_ID,
                pass = false,
                detail = "child workload not started: PRoot/Alpine runtime is not staged " +
                    "(proot=${proot?.absolutePath ?: "missing"}; " +
                    "loader=${loader?.absolutePath ?: "missing"}); tap Download and verify " +
                    "pinned runtime first",
            )
        }

        SpikeProbe.prepareRuntime(context)
        if (rootfs.isDirectory) logFileOf(rootfs).parentFile?.mkdirs()
        val logFile = logFileOf(rootfs)
        logFile.delete()
        val stderrFile = File(root, "tmp/s7-child-stderr.txt")
        val stdoutFile = File(root, "tmp/s7-child-stdout.txt")
        val command = listOf(
            proot.absolutePath,
            "--kill-on-exit",
            "-0",
            "-r",
            rootfs.absolutePath,
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-w",
            "/root",
            "/bin/sh",
            "-c",
            GUEST_SCRIPT,
        )
        val env = SpikeProbe.runtimeEnvironment(context).apply {
            put("PROOT_TMP_DIR", File(root, "tmp").absolutePath)
            put("PROOT_NO_SECCOMP", "1")
            put(
                "LD_LIBRARY_PATH",
                root.absolutePath + ":" + File(root, "runtime").absolutePath + ":" +
                    context.applicationInfo.nativeLibraryDir,
            )
            put("PROOT_LOADER", loader.absolutePath)
        }
        return try {
            val builder = ProcessBuilder(command)
            builder.directory(rootfs)
            builder.environment().apply {
                remove("LD_PRELOAD")
                putAll(env)
            }
            builder.redirectOutput(stdoutFile)
            builder.redirectError(stderrFile)
            val childrenBefore = appChildPids()
            val process = builder.start()
            val tracerPid = (appChildPids() - childrenBefore).firstOrNull() ?: -1L
            if (tracerPid <= 0) {
                Log.w(
                    TAG,
                    "child workload started but its pid could not be resolved from /proc; " +
                        "teardown will not be able to kill surviving PRoot descendants",
                )
            }
            session = Session(
                process = process,
                tracerPid = tracerPid,
                logFile = logFile,
                stderrFile = stderrFile,
                startedAtWallClockMs = System.currentTimeMillis(),
                startedAtUptimeMs = hostUptimeMs(),
            )
            childStartedThisProcess = true
            val detail = "PRoot/Alpine child workload started: " +
                "sampleIntervalMs=$SAMPLE_INTERVAL_MS; guestLog=/root/s7-child.log " +
                "(host: ${logFile.absolutePath}); supervised from the service heartbeat; " +
                "stopped and analyzed when the soak ends"
            Log.i(TAG, detail)
            ProbeRecord(START_RECORD_ID, pass = true, detail = detail, command = command)
        } catch (failure: Throwable) {
            childStartedThisProcess = false
            analysisCompleted = true
            ProbeRecord(
                START_RECORD_ID,
                pass = false,
                detail = "child workload failed to start: " +
                    "${failure.javaClass.simpleName}: ${failure.message}; " +
                    "stderrTail=${tail(stderrFile)}",
                command = command,
            )
        }
    }

    /** Called from the service heartbeat; cheap liveness sampling. */
    fun hostSample() {
        val active = session ?: return
        active.hostChecks += 1
        if (active.process.isAlive) {
            active.hostAliveChecks += 1
        } else {
            active.hostDeadChecks += 1
        }
    }

    /** Kills the guest child without analysis (service teardown). */
    fun abort() {
        val active = session ?: return
        session = null
        terminateProcessTree(active.process, active.tracerPid, label = "child abort")
    }

    /**
     * Stops the child workload and analyzes the guest sample file. Idempotent;
     * returns null when nothing needs to be recorded anymore.
     */
    fun stopAndAnalyze(context: Context, stopReason: String): ProbeRecord? {
        val active = session
        if (active == null) {
            if (!childStartedThisProcess || analysisCompleted) return null
            analysisCompleted = true
            val logFile = logFileOf(File(SpikeProbe.runtimeDir(context), "rootfs"))
            val samples = readSamples(logFile)
            return ProbeRecord(
                RECORD_ID,
                pass = false,
                detail = "child supervisor state was lost before analysis (the service or the " +
                    "app process was destroyed mid-run); stopReason=$stopReason; " +
                    "samples=${samples.size}${describeTail(samples.lastOrNull())}; " +
                    "child survival cannot be claimed for this run",
            )
        }
        session = null
        analysisCompleted = true

        val aliveAtStop = active.process.isAlive
        val preKillExitCode = try {
            active.process.exitValue()
        } catch (_: IllegalThreadStateException) {
            null
        }
        terminateProcessTree(active.process, active.tracerPid, label = "child stop")

        val stoppedAtWallClockMs = System.currentTimeMillis()
        val stoppedAtUptimeMs = hostUptimeMs()
        val durationMs = stoppedAtWallClockMs - active.startedAtWallClockMs
        val samples = readSamples(active.logFile)
        val expectedSamples = maxOf(1L, (durationMs - STARTUP_SLACK_MS) / SAMPLE_INTERVAL_MS)
        val coverage = samples.size.toFloat() / expectedSamples.toFloat()

        val withUptime = samples.filter { it.uptimeMs > 0 }
        val useUptime = samples.isNotEmpty() &&
            (withUptime.size * 10 >= samples.size * 9) &&
            active.startedAtUptimeMs > 0 &&
            stoppedAtUptimeMs > 0 &&
            withUptime.first().uptimeMs >= active.startedAtUptimeMs - 60_000
        val timeSeries = if (useUptime) {
            withUptime.map { it.uptimeMs }
        } else {
            samples.map { it.wallClockMs }
        }
        val endPoint = if (useUptime) stoppedAtUptimeMs else stoppedAtWallClockMs
        val timebase = when {
            samples.isEmpty() -> "none"
            useUptime -> "kernel-uptime"
            else -> "wall-clock"
        }

        var maxGapMs = 0L
        var gapsAboveThreshold = 0
        for (index in 1 until timeSeries.size) {
            val gap = timeSeries[index] - timeSeries[index - 1]
            if (gap > maxGapMs) maxGapMs = gap
            if (gap > STALL_THRESHOLD_MS) gapsAboveThreshold += 1
        }
        val tailGapMs = if (timeSeries.isNotEmpty() && endPoint > 0) {
            (endPoint - timeSeries.last()).coerceAtLeast(0L)
        } else {
            -1L
        }
        if (tailGapMs > maxGapMs) maxGapMs = tailGapMs
        if (tailGapMs > STALL_THRESHOLD_MS) gapsAboveThreshold += 1

        var indexAnomalies = 0
        for (index in 1 until samples.size) {
            if (samples[index].index != samples[index - 1].index + 1) indexAnomalies += 1
        }
        if (samples.isNotEmpty() && samples.first().index != 1) indexAnomalies += 1

        val sampleRateSane = samples.size <= expectedSamples * 3 + 30
        val started = samples.isNotEmpty()
        val aliveAtEnd = aliveAtStop && active.hostDeadChecks == 0
        val coverageOk = coverage >= MINIMUM_COVERAGE
        val stallsFree = gapsAboveThreshold == 0
        val pass = started && aliveAtEnd && coverageOk && stallsFree &&
            indexAnomalies == 0 && sampleRateSane

        val detail = buildString {
            append("stopReason=$stopReason; ")
            append("childAliveAtStop=$aliveAtStop; preKillExitCode=${preKillExitCode ?: "null"}; ")
            append("hostChecks=${active.hostChecks}; hostAliveChecks=${active.hostAliveChecks}; ")
            append("hostDeadChecks=${active.hostDeadChecks}; ")
            append("samples=${samples.size}; expectedSamples=$expectedSamples; ")
            append("coverage=${String.format(Locale.US, "%.2f", coverage)}; ")
            append("durationMs=$durationMs; timebase=$timebase; ")
            append("maxSampleGapMs=$maxGapMs; stallThresholdMs=$STALL_THRESHOLD_MS; ")
            append("gapsAboveThreshold=$gapsAboveThreshold; tailGapMs=$tailGapMs; ")
            append("indexAnomalies=$indexAnomalies; sampleRateSane=$sampleRateSane; ")
            if (samples.isNotEmpty()) {
                append("firstSampleWallClockMs=${samples.first().wallClockMs}; ")
                append("lastSampleWallClockMs=${samples.last().wallClockMs}; ")
            }
            if (!pass) {
                append("stderrTail=${tail(active.stderrFile)}; ")
            }
            append("this measures a PRoot/Alpine child workload supervised by the harness service " +
                "on one device; it is feasibility evidence for LC-2, not the 30-minute " +
                "agent-task acceptance test")
        }
        return ProbeRecord(
            id = RECORD_ID,
            pass = pass,
            detail = detail,
            exitCode = preKillExitCode,
            durationMs = durationMs,
        )
    }

    /**
     * Stops a probe child process and every descendant it spawned.
     *
     * destroy()/destroyForcibly() only signal the direct child, which is the
     * PRoot tracer; the guest workload runs as its traced descendants, and
     * PRoot's `--kill-on-exit` cleanup cannot run when the tracer is killed
     * uncatchably. The S7 smoke run showed guest samples continuing past
     * teardown, so descendants are enumerated before the tracer is signalled
     * (once the tracer dies its children are reparented and no longer
     * discoverable through it), then every survivor is killed and verified.
     * The tracer PID is captured from /proc at start() because the Android
     * SDK does not expose Process.pid()/ProcessHandle. Teardown failures are
     * logged instead of swallowed, so a surviving child is visible in logcat.
     */
    private fun terminateProcessTree(process: Process, tracerPid: Long, label: String) {
        val descendants = if (tracerPid > 0) {
            procDescendantPids(tracerPid).also { pids ->
                if (pids.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "$label teardown: tracer pid=$tracerPid has ${pids.size} live " +
                            "descendant(s) $pids; they are killed after the tracer",
                    )
                }
            }
        } else {
            Log.w(TAG, "$label teardown: tracer pid unavailable; no descendant capture")
            emptyList<Long>()
        }

        try {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                Log.w(
                    TAG,
                    "$label teardown: tracer pid=$tracerPid ignored destroy(); " +
                        "sending SIGKILL via destroyForcibly()",
                )
                process.destroyForcibly()
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    Log.e(TAG, "$label teardown: tracer pid=$tracerPid survived destroyForcibly()")
                }
            }
        } catch (failure: Throwable) {
            Log.w(
                TAG,
                "$label teardown: tracer pid=$tracerPid destroy failed: " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
        if (tracerPid > 0 && process.isAlive) {
            try {
                AndroidProcess.killProcess(tracerPid.toInt())
                if (!awaitProcessGone(tracerPid)) {
                    Log.e(TAG, "$label teardown: tracer pid=$tracerPid survived a SIGKILL")
                }
            } catch (failure: Throwable) {
                Log.e(
                    TAG,
                    "$label teardown: tracer pid=$tracerPid killProcess failed: " +
                        "${failure.javaClass.simpleName}: ${failure.message}",
                )
            }
        }

        killDescendants(descendants, label)
    }

    /**
     * Kills captured descendant PIDs with SIGKILL and verifies they are gone.
     * Only same-UID PIDs are captured (the guest runs under the app UID), so
     * the harness may signal them. Already-exited and zombie PIDs are skipped;
     * survivors are logged because they would keep sampling.
     */
    private fun killDescendants(pids: List<Long>, label: String) {
        if (pids.isEmpty()) return
        var killed = 0
        val survivors = mutableListOf<Long>()
        for (pid in pids) {
            val state = procState(pid) ?: continue
            if (state == 'Z') continue
            try {
                AndroidProcess.killProcess(pid.toInt())
                killed += 1
            } catch (failure: Throwable) {
                Log.w(
                    TAG,
                    "$label teardown: descendant pid=$pid kill failed: " +
                        "${failure.javaClass.simpleName}: ${failure.message}",
                )
                survivors.add(pid)
                continue
            }
            if (!awaitProcessGone(pid)) survivors.add(pid)
        }
        if (killed > 0 || survivors.isNotEmpty()) {
            Log.w(
                TAG,
                "$label teardown: descendants $pids; killed=$killed; survivors=$survivors " +
                    "(PRoot guest processes can outlive a SIGKILLed tracer)",
            )
        }
    }

    /** Direct child PIDs of the harness process, resolved from /proc parent links. */
    private fun appChildPids(): Set<Long> {
        val myPid = AndroidProcess.myPid().toLong()
        val dirs = File("/proc").listFiles() ?: return emptySet()
        val result = mutableSetOf<Long>()
        for (dir in dirs) {
            val pid = dir.name.toLongOrNull() ?: continue
            if (pid == myPid) continue
            if (procPpid(pid) == myPid) result.add(pid)
        }
        return result
    }

    private fun procDescendantPids(tracerPid: Long): List<Long> {
        val dirs = File("/proc").listFiles() ?: return emptyList()
        val childrenByParent = mutableMapOf<Long, MutableList<Long>>()
        for (dir in dirs) {
            val pid = dir.name.toLongOrNull() ?: continue
            val ppid = procPpid(pid) ?: continue
            childrenByParent.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val result = mutableListOf<Long>()
        val visited = mutableSetOf(tracerPid)
        val queue = ArrayDeque<Long>()
        childrenByParent[tracerPid]?.let { queue.addAll(it) }
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            if (!visited.add(pid)) continue
            result.add(pid)
            childrenByParent[pid]?.let { queue.addAll(it) }
        }
        return result
    }

    /** Parent PID (field 4) from /proc/<pid>/stat, or null when unreadable. */
    private fun procPpid(pid: Long): Long? {
        val stat = readProcStat(pid) ?: return null
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        return stat.substring(close + 2).split(' ').getOrNull(1)?.toLongOrNull()
    }

    /** Process state character (field 3) from /proc/<pid>/stat; null when gone. */
    private fun procState(pid: Long): Char? {
        val stat = readProcStat(pid) ?: return null
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        return stat[close + 2]
    }

    private fun readProcStat(pid: Long): String? = try {
        File("/proc/$pid/stat").readText()
    } catch (_: Throwable) {
        null
    }

    private fun awaitProcessGone(pid: Long, timeoutMs: Long = 2_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = procState(pid)
            if (state == null || state == 'Z') return true
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        val state = procState(pid)
        return state == null || state == 'Z'
    }

    private fun logFileOf(rootfs: File): File = File(rootfs, LOG_RELATIVE_TO_ROOTFS)

    private fun readSamples(file: File): List<ChildSample> {
        if (!file.isFile) return emptyList()
        return try {
            file.readLines().mapNotNull(::parseSample)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parseSample(line: String): ChildSample? {
        val parts = line.trim().split(' ').filter { it.isNotBlank() }
        if (parts.size < 3) return null
        val wallClockS = parts[0].toLongOrNull() ?: return null
        val uptimeS = parts[1].toLongOrNull() ?: return null
        val index = parts[2].toIntOrNull() ?: return null
        if (index <= 0) return null
        return ChildSample(
            wallClockMs = wallClockS * 1000L,
            uptimeMs = uptimeS * 1000L,
            index = index,
        )
    }

    private fun describeTail(last: ChildSample?): String {
        if (last == null) return "; no samples were written"
        val nowWallClockMs = System.currentTimeMillis()
        val nowUptimeMs = hostUptimeMs()
        val ageMs = when {
            last.uptimeMs > 0 && nowUptimeMs > 0 -> nowUptimeMs - last.uptimeMs
            else -> nowWallClockMs - last.wallClockMs
        }.coerceAtLeast(0L)
        return "; last sample was ${ageMs}ms before this analysis; lastIndex=${last.index}"
    }

    private fun tail(file: File, maxChars: Int = MAX_STDERR_TAIL_CHARS): String = try {
        file.readText().takeLast(maxChars).replace('\n', ' ')
    } catch (_: Throwable) {
        "unavailable"
    }
}
