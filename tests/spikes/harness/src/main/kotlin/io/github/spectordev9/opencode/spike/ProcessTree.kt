package io.github.spectordev9.opencode.spike

import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

object ProcessTree {
    private const val TAG = "SpikeHarness"

    data class TrackedProcess(
        val process: Process,
        val rootPid: Long,
    )

    fun startTracked(builder: ProcessBuilder): TrackedProcess {
        val before = appChildPids()
        val process = builder.start()
        val rootPid = (appChildPids() - before).firstOrNull() ?: -1L
        return TrackedProcess(process, rootPid)
    }

    fun kill(tracked: TrackedProcess, label: String): List<Long> {
        val descendants = if (tracked.rootPid > 0) {
            descendantPids(tracked.rootPid)
        } else {
            emptyList()
        }
        try {
            tracked.process.destroy()
            if (!tracked.process.waitFor(2, TimeUnit.SECONDS)) {
                tracked.process.destroyForcibly()
                tracked.process.waitFor(2, TimeUnit.SECONDS)
            }
        } catch (failure: Throwable) {
            Log.w(
                TAG,
                "$label teardown: destroy failed: ${failure.javaClass.simpleName}: ${failure.message}",
            )
        }

        val survivors = mutableListOf<Long>()
        for (pid in descendants.asReversed()) {
            val state = procState(pid) ?: continue
            if (state == 'Z') continue
            try {
                AndroidProcess.killProcess(pid.toInt())
            } catch (failure: Throwable) {
                Log.w(
                    TAG,
                    "$label teardown: descendant pid=$pid kill failed: " +
                        "${failure.javaClass.simpleName}: ${failure.message}",
                )
                survivors += pid
                continue
            }
            if (!awaitProcessGone(pid)) survivors += pid
        }
        if (tracked.rootPid > 0 && tracked.process.isAlive) {
            try {
                AndroidProcess.killProcess(tracked.rootPid.toInt())
                if (!awaitProcessGone(tracked.rootPid)) survivors += tracked.rootPid
            } catch (failure: Throwable) {
                Log.w(
                    TAG,
                    "$label teardown: root pid=${tracked.rootPid} kill failed: " +
                        "${failure.javaClass.simpleName}: ${failure.message}",
                )
                survivors += tracked.rootPid
            }
        }
        if (descendants.isNotEmpty() || survivors.isNotEmpty()) {
            Log.i(
                TAG,
                "$label teardown: root=${tracked.rootPid}; descendants=$descendants; survivors=$survivors",
            )
        }
        return survivors
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

    private fun descendantPids(rootPid: Long): List<Long> {
        val dirs = File("/proc").listFiles() ?: return emptyList()
        val childrenByParent = mutableMapOf<Long, MutableList<Long>>()
        for (dir in dirs) {
            val pid = dir.name.toLongOrNull() ?: continue
            val ppid = procPpid(pid) ?: continue
            childrenByParent.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val result = mutableListOf<Long>()
        val visited = mutableSetOf(rootPid)
        val queue = ArrayDeque<Long>()
        childrenByParent[rootPid]?.let { queue.addAll(it) }
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            if (!visited.add(pid)) continue
            result += pid
            childrenByParent[pid]?.let { queue.addAll(it) }
        }
        return result
    }

    private fun procPpid(pid: Long): Long? {
        val stat = readProcStat(pid) ?: return null
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        return stat.substring(close + 2).split(' ').getOrNull(1)?.toLongOrNull()
    }

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
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
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
}
