package io.github.spectordev9.opencode.spike

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.workspace.Chunk
import io.github.spectordev9.opencode.core.workspace.Entry
import io.github.spectordev9.opencode.core.workspace.Precondition
import io.github.spectordev9.opencode.core.workspace.impl.FileWorkspaceService
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

internal object G3WorkspaceChecks {
    fun runWs2(
        context: Context,
        runs: Int,
        progress: (String) -> Unit,
    ): ProbeRecord {
        val started = System.currentTimeMillis()
        val root = G3Support.g3Dir(context, "ws2")
        root.listFiles()?.forEach { it.deleteRecursively() }
        val target = File(root, "target.bin")
        val pidFile = File(root, Ws2WorkerService.PID_FILE)
        val statusFile = File(root, Ws2WorkerService.STATUS_FILE)
        val sizes = intArrayOf(1024, 64 * 1024, 1024 * 1024, 8 * 1024 * 1024, 50 * 1024 * 1024)
        if (root.usableSpace < 256L * 1024 * 1024) {
            return ProbeRecord(
                "WS-2",
                false,
                "insufficient free space: ${root.usableSpace / (1024 * 1024)} MiB",
                durationMs = elapsed(started),
            )
        }
        val service = FileWorkspaceService(root.toPath())
        val seeded = runCatching {
            runBlocking {
                service.write(
                    "target.bin",
                    Ws2WorkerService.generatedContent(0, sizes[0]),
                    Precondition.None,
                )
            }
        }.getOrNull()
        if (seeded !is ApiResult.Success<*>) {
            return ProbeRecord("WS-2", false, "could not seed target: $seeded", durationMs = elapsed(started))
        }
        var oldSha = G3Support.sha256(target)
        var committed = 0
        var killedOld = 0
        var corrupt = 0
        var missing = 0
        var failedBinds = 0
        var failedControls = 0
        var controls = 0
        val workerFailures = mutableListOf<String>()
        var controlEvery = max(1, runs / 10)
        if (controlEvery % sizes.size == 0) controlEvery++
        for (run in 1..runs) {
            if (run % max(1, runs / 20) == 0) {
                progress("WS-2 run $run/$runs killedOld=$killedOld committed=$committed corrupt=$corrupt")
            }
            val size = sizes[(run - 1) % sizes.size]
            val newSha = G3Support.sha256Generated(run, size)
            deleteStaleTemporaryFiles(root)
            val worker = startWorker(context, root, run, size, pidFile)
            if (worker == null) {
                failedBinds++
                continue
            }
            try {
                if (run % controlEvery == 0) {
                    if (waitForWrite(target, newSha, size, 20_000)) {
                        controls++
                    } else {
                        failedControls++
                        recordWorkerFailure(workerFailures, run, statusFile)
                        Process.killProcess(worker.pid)
                    }
                } else {
                    Thread.sleep(killDelay(size))
                    Process.killProcess(worker.pid)
                }
            } finally {
                runCatching { context.unbindService(worker.connection) }
            }
            Thread.sleep(50)
            val actual = runCatching { G3Support.sha256(target) }.getOrNull()
            when {
                actual == null -> missing++
                actual == newSha -> {
                    committed++
                    oldSha = actual
                }
                actual == oldSha -> {
                    killedOld++
                    if (workerFailures.size < 3) {
                        recordWorkerFailure(workerFailures, run, statusFile)
                    }
                }
                else -> corrupt++
            }
            deleteStaleTemporaryFiles(root)
        }
        val pass = failedBinds == 0 && failedControls == 0 && missing == 0 && corrupt == 0 &&
            controls == runs / controlEvery && (killedOld + committed) == runs
        val detail = buildString {
            append("runs=$runs committed=$committed killedBeforeCommit=$killedOld corrupt=$corrupt")
            append(" missing=$missing failedBinds=$failedBinds failedControls=$failedControls")
            append(" controls=$controls/${runs / controlEvery}")
            append(" sizes=1KB..50MB final=${target.length()}B")
            append(" sha=${oldSha.take(16)}")
            if (workerFailures.isNotEmpty()) {
                append(" workerFailures=[${workerFailures.joinToString("; ")}]")
            }
        }
        return ProbeRecord(
            id = "WS-2",
            pass = pass,
            detail = detail,
            durationMs = elapsed(started),
        )
    }

    private fun startWorker(
        context: Context,
        root: File,
        run: Int,
        size: Int,
        pidFile: File,
    ): BoundWorker? {
        val statusFile = File(root, Ws2WorkerService.STATUS_FILE)
        repeat(3) {
            pidFile.delete()
            statusFile.delete()
            deleteStaleTemporaryFiles(root)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {}

                override fun onServiceDisconnected(name: ComponentName?) {}
            }
            val intent = Intent(context, Ws2WorkerService::class.java)
                .putExtra(Ws2WorkerService.EXTRA_ROOT, root.absolutePath)
                .putExtra(Ws2WorkerService.EXTRA_RUN, run)
                .putExtra(Ws2WorkerService.EXTRA_SIZE, size)
            val bound = runCatching {
                context.bindService(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or Context.BIND_ABOVE_CLIENT,
                )
            }.getOrDefault(false)
            if (bound) {
                val pid = waitForPid(pidFile, 5_000)
                if (pid != null) return BoundWorker(connection, pid)
            }
            runCatching { context.unbindService(connection) }
            Thread.sleep(100)
        }
        return null
    }

    private fun waitForWrite(target: File, expectedSha: String, size: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (target.length() == size.toLong() &&
                runCatching { G3Support.sha256(target) }.getOrNull() == expectedSha
            ) {
                return true
            }
            Thread.sleep(20)
        }
        return false
    }

    private fun killDelay(size: Int): Long = when {
        size <= 64 * 1024 -> Random.nextLong(5, 100)
        size <= 1024 * 1024 -> Random.nextLong(10, 300)
        size <= 8 * 1024 * 1024 -> Random.nextLong(50, 1_000)
        else -> Random.nextLong(100, 3_000)
    }

    private fun recordWorkerFailure(failures: MutableList<String>, run: Int, statusFile: File) {
        val status = runCatching { statusFile.readText().trim().replace('\n', ' ') }
            .getOrDefault("missing")
            .take(240)
        failures += "run=$run $status"
    }

    private fun deleteStaleTemporaryFiles(root: File) {
        root.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(TEMP_PREFIX) && file.name.endsWith(TEMP_SUFFIX)) {
                file.delete()
            }
        }
    }

    private data class BoundWorker(
        val connection: ServiceConnection,
        val pid: Int,
    )

    fun runWs3(
        context: Context,
        runs: Int,
        progress: (String) -> Unit,
    ): ProbeRecord {
        val started = System.currentTimeMillis()
        val root = G3Support.g3Dir(context, "ws3")
        root.listFiles()?.forEach { it.deleteRecursively() }
        val file = File(root, "target.txt")
        val service = FileWorkspaceService(root.toPath())
        val seed = "seed-${System.nanoTime()}"
        val seeded = runCatching {
            runBlocking {
                service.write("target.txt", seed.toByteArray(), Precondition.None)
            }
        }.getOrNull()
        if (seeded !is ApiResult.Success<*>) {
            return ProbeRecord("WS-3", false, "could not seed file: $seeded", durationMs = elapsed(started))
        }
        var conflicts = 0
        var silentOverwrites = 0
        var writeBackOk = 0
        for (run in 1..runs) {
            if (run % max(1, runs / 10) == 0) {
                progress("WS-3 run $run/$runs conflicts=$conflicts silent=$silentOverwrites")
            }
            val before = runCatching {
                runBlocking { service.stat("target.txt") }
            }.getOrNull()
            val entry = (before as? ApiResult.Success<Entry>)?.value
            if (entry == null) {
                silentOverwrites++
                continue
            }
            val observedSha = G3Support.sha256(file)
            val expected = Precondition.Match(entry.sizeBytes, entry.modifiedAtEpochMillis, observedSha)
            val external = "external-$run-${System.nanoTime()}"
            file.writeText(external)
            file.setLastModified(entry.modifiedAtEpochMillis + 2_000)
            val write = runCatching {
                runBlocking { service.write("target.txt", "agent-$run".toByteArray(), expected) }
            }.getOrNull()
            val failure = write as? ApiResult.Failure
            val onDisk = file.readText()
            if (failure?.error?.code == ErrorCode.E_CONFLICT) {
                conflicts++
                if (onDisk != external) silentOverwrites++
            } else if (write is ApiResult.Success<*>) {
                if (onDisk == external) {
                    silentOverwrites++
                }
            } else {
                silentOverwrites++
            }
            val now = runCatching {
                runBlocking { service.stat("target.txt") }
            }.getOrNull()
            val current = (now as? ApiResult.Success<Entry>)?.value
            if (current != null) {
                val currentSha = G3Support.sha256(file)
                val result = runCatching {
                    runBlocking {
                        service.write(
                            "target.txt",
                            "agent2-$run".toByteArray(),
                            Precondition.Match(current.sizeBytes, current.modifiedAtEpochMillis, currentSha),
                        )
                    }
                }.getOrNull()
                if (result is ApiResult.Success<*> && file.readText() == "agent2-$run") {
                    writeBackOk++
                }
            }
        }
        val pass = conflicts == runs && silentOverwrites == 0 && writeBackOk == runs
        val detail = "runs=$runs conflicts=$conflicts silentOverwrites=$silentOverwrites " +
            "recoveryWrites=$writeBackOk"
        return ProbeRecord("WS-3", pass, detail, durationMs = elapsed(started))
    }

    fun runWs4(
        context: Context,
        progress: (String) -> Unit,
    ): ProbeRecord {
        val started = System.currentTimeMillis()
        val root = G3Support.g3Dir(context, "ws4")
        root.listFiles()?.forEach { it.deleteRecursively() }
        val free = root.usableSpace
        if (free < 1_500L * 1024 * 1024) {
            return ProbeRecord(
                "WS-4",
                false,
                "insufficient free space: ${free / (1024 * 1024)} MiB",
                durationMs = elapsed(started),
            )
        }
        val text = File(root, "text-100mb.txt")
        val binary = File(root, "binary-1gb.bin")
        progress("WS-4 creating 100 MB text file")
        val line = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n".toByteArray()
        FileOutputStream(text).use { output ->
            var written = 0L
            val target = 100L * 1024 * 1024
            while (written < target) {
                val count = minOf(line.size.toLong(), target - written).toInt()
                output.write(line, 0, count)
                written += count
            }
        }
        progress("WS-4 creating sparse 1 GB binary")
        RandomAccessFile(binary, "rw").use {
            it.setLength(1024L * 1024 * 1024)
            it.seek(it.length() - 1)
            it.write(0x5a)
        }
        val service = FileWorkspaceService(root.toPath())
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun pssKb(): Long = runCatching {
            activityManager.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss.toLong()
        }.getOrDefault(0)
        runCatching { Runtime.getRuntime().gc() }
        Thread.sleep(200)
        val baselinePss = pssKb()
        var peakPss = baselinePss
        progress("WS-4 first-screen reads")
        val firstText = timed {
            runBlocking { service.readRange("text-100mb.txt", 0, 64 * 1024) }
        }
        val firstBinary = timed {
            runBlocking { service.readRange("binary-1gb.bin", 0, 64 * 1024) }
        }
        var reads = 0
        var binaryBytes = 0L
        var failed = 0
        val textSize = text.length()
        val binarySize = binary.length()
        repeat(20) { index ->
            val textOffset = (textSize / 21) * (index + 1)
            val textChunk = runCatching {
                runBlocking { service.readRange("text-100mb.txt", textOffset, 256 * 1024) }
            }.getOrNull()
            if ((textChunk as? ApiResult.Success<Chunk>)?.value?.bytes?.isNotEmpty() == true) reads++
            else failed++
            val binaryOffset = ((binarySize / 21) * (index + 1)).coerceAtMost(binarySize - 1024 * 1024)
            val binaryChunk = runCatching {
                runBlocking { service.readRange("binary-1gb.bin", binaryOffset, 1024 * 1024) }
            }.getOrNull()
            val chunk = (binaryChunk as? ApiResult.Success<Chunk>)?.value
            if (chunk != null && chunk.bytes.isNotEmpty()) {
                reads++
                binaryBytes += chunk.bytes.size
            } else {
                failed++
            }
            peakPss = max(peakPss, pssKb())
        }
        val extraPss = max(0, peakPss - baselinePss) / 1024
        val textMs = firstText.millis
        val binaryMs = firstBinary.millis
        val textResult = firstText.result
        val binaryResult = firstBinary.result
        val textOk = (textResult as? ApiResult.Success<Chunk>)?.value?.bytes?.isNotEmpty() == true
        val binaryOk = (binaryResult as? ApiResult.Success<Chunk>)?.value?.bytes?.isNotEmpty() == true
        val pass = textOk && binaryOk && textMs <= 1_000 && binaryMs <= 1_000 &&
            extraPss <= 64 && failed == 0 && reads == 40
        val detail = buildString {
            append("text100MB=${text.length() / (1024 * 1024)}MB")
            append(" firstText=${textMs}ms firstBinary=${binaryMs}ms")
            append(" peakExtraPss=${extraPss}MB")
            append(" reads=$reads failed=$failed binaryRead=${binaryBytes / (1024 * 1024)}MB")
            append(" imageDecode=notExercised")
        }
        runCatching {
            text.delete()
            binary.delete()
        }
        progress("WS-4 done")
        return ProbeRecord("WS-4", pass, detail, durationMs = elapsed(started))
    }

    private fun waitForPid(file: File, timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val value = runCatching { file.readText().trim().toInt() }.getOrNull()
            if (value != null && value > 0 && value != Process.myPid()) return value
            Thread.sleep(10)
        }
        return null
    }

    private fun elapsed(started: Long): Long = System.currentTimeMillis() - started

    private const val TEMP_PREFIX = ".workspace-write-"
    private const val TEMP_SUFFIX = ".tmp"

    private fun <T> timed(block: () -> ApiResult<T>): TimedRead<T> {
        val start = System.currentTimeMillis()
        val result = runCatching(block).getOrNull()
        return TimedRead(result, System.currentTimeMillis() - start)
    }

    private data class TimedRead<T>(val result: ApiResult<T>?, val millis: Long)
}
