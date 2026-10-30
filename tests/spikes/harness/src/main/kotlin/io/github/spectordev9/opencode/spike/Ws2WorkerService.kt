package io.github.spectordev9.opencode.spike

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.workspace.Precondition
import io.github.spectordev9.opencode.core.workspace.impl.FileWorkspaceService
import java.io.File
import kotlinx.coroutines.runBlocking

class Ws2WorkerService : Service() {
    override fun onBind(intent: Intent?): IBinder {
        if (intent == null) return Binder()
        val root = intent.getStringExtra(EXTRA_ROOT)
        val run = intent.getIntExtra(EXTRA_RUN, -1)
        val size = intent.getIntExtra(EXTRA_SIZE, -1)
        if (root == null || run < 0 || size <= 0) return Binder()
        Thread {
            val directory = File(root)
            try {
                directory.mkdirs()
                writeStage(directory, run, size, "thread-start")
                File(directory, PID_FILE).writeText(Process.myPid().toString())
                writeStage(directory, run, size, "write-start")
                val service = FileWorkspaceService(directory.toPath())
                val result = runBlocking {
                    service.write("target.bin", generatedContent(run, size), Precondition.None)
                }
                when (result) {
                    is ApiResult.Success -> writeStage(directory, run, size, "write-done")
                    is ApiResult.Failure -> writeStage(
                        directory,
                        run,
                        size,
                        "write-failure:${result.error.code}:${result.error.message}",
                    )
                }
            } catch (failure: Throwable) {
                writeStage(directory, run, size, "error:${failure.javaClass.simpleName}:${failure.message}")
                runCatching {
                    File(directory, ERROR_FILE).appendText(
                        "run=$run size=$size\n${failure.stackTraceToString()}\n",
                    )
                }
            }
        }.apply {
            name = "ws2-atomic-write"
            isDaemon = true
            start()
        }
        return Binder()
    }

    companion object {
        const val EXTRA_ROOT = "root"
        const val EXTRA_RUN = "run"
        const val EXTRA_SIZE = "size"
        const val PID_FILE = "ws2.pid"
        const val STATUS_FILE = "ws2.status"
        const val ERROR_FILE = "ws2.error.log"

        private fun writeStage(directory: File, run: Int, size: Int, stage: String) {
            runCatching {
                File(directory, STATUS_FILE).writeText(
                    "pid=${Process.myPid()} run=$run size=$size stage=$stage",
                )
            }
        }

        fun generatedContent(run: Int, size: Int): ByteArray {
            val header = "G3WS2:$run:$size:".toByteArray(Charsets.US_ASCII)
            val data = ByteArray(size)
            header.copyInto(data, 0, 0, minOf(header.size, size))
            data.fill((0x41 + (run % 26)).toByte(), header.size, size)
            return data
        }
    }
}
