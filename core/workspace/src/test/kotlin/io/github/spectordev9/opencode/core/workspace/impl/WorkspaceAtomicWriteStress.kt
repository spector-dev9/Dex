package io.github.spectordev9.opencode.core.workspace.impl

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.workspace.Precondition
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.math.pow
import kotlinx.coroutines.runBlocking

object WorkspaceAtomicWriteStress {
    private const val DEFAULT_RUNS = 1_000
    private const val MIN_BYTES = 1_024
    private const val MAX_BYTES = 50 * 1_024 * 1_024

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "worker") {
            runWorker(args)
        } else {
            val runs = args.firstOrNull()?.toIntOrNull() ?: if (args.isEmpty()) {
                DEFAULT_RUNS
            } else {
                error("run count must be a positive integer")
            }
            val workerClasspath = args.getOrNull(1) ?: System.getProperty("java.class.path")
            runStress(runs, workerClasspath)
        }
    }

    private fun runStress(runs: Int, workerClasspath: String) {
        require(runs > 0)
        val root = createTempDirectory("workspace-atomic-crash")
        val target = root.resolve("target.bin")
        val marker = root.resolve("write-progress")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        var interruptedWrites = 0

        try {
            repeat(runs) { index ->
                val size = payloadSize(index, runs)
                val old = "old-$index".toByteArray()
                Files.write(target, old)
                Files.deleteIfExists(marker)
                val workerLog = root.resolve("worker-$index.log")

                val process = ProcessBuilder(
                    java,
                    "-cp",
                    workerClasspath,
                    WorkspaceAtomicWriteStress::class.java.name,
                    "worker",
                    root.toString(),
                    size.toString(),
                    index.toString(),
                    marker.toString(),
                )
                    .redirectErrorStream(true)
                    .redirectOutput(workerLog.toFile())
                    .start()

                try {
                    check(awaitMarker(marker)) {
                        val output = Files.readString(workerLog)
                        "worker $index did not reach its partial-write checkpoint: $output"
                    }
                    val bytesWritten = Files.readString(marker).toLong()
                    check(bytesWritten in 1 until size.toLong()) {
                        "worker $index did not stop mid-write: wrote $bytesWritten of $size bytes"
                    }
                } finally {
                    if (process.isAlive) {
                        process.destroyForcibly()
                        check(process.waitFor(10, TimeUnit.SECONDS)) {
                            "worker $index did not terminate after SIGKILL"
                        }
                    }
                }
                interruptedWrites++

                val contents = Files.readAllBytes(target)
                val expectedNewHash = repeatedByteSha256(size, payloadByte(index))
                val isOld = contents.contentEquals(old)
                val isNew = contents.size == size && sha256(contents).contentEquals(expectedNewHash)
                check(isOld || isNew) {
                    "run $index exposed a partial target (${contents.size} bytes; expected old or $size-byte new file)"
                }
                deleteTemporaryFiles(root)
                Files.deleteIfExists(marker)
            }
        } finally {
            root.toFile().deleteRecursively()
        }

        check(interruptedWrites == runs)
        println("PASS: $interruptedWrites SIGKILL interruptions; payload sizes $MIN_BYTES..$MAX_BYTES bytes")
    }

    private fun runWorker(args: Array<String>) {
        require(args.size == 5)
        val root = Path.of(args[1])
        val size = args[2].toInt()
        val index = args[3].toInt()
        val marker = Path.of(args[4])
        val payload = ByteArray(size) { payloadByte(index) }
        val service = FileWorkspaceService(
            root,
            AtomicWriteObserver { bytesWritten ->
                val temporaryMarker = marker.resolveSibling("${marker.fileName}.tmp")
                Files.writeString(temporaryMarker, bytesWritten.toString())
                Files.move(temporaryMarker, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                CountDownLatch(1).await()
            },
        )
        val existing = runBlocking { service.stat("/target.bin") }
        val entry = (existing as? ApiResult.Success)?.value
            ?: error("existing target could not be read: $existing")
        val existingBytes = Files.readAllBytes(root.resolve("target.bin"))
        val expect = Precondition.Match(
            sizeBytes = entry.sizeBytes,
            modifiedAtEpochMillis = entry.modifiedAtEpochMillis,
            sha256 = MessageDigest.getInstance("SHA-256").digest(existingBytes)
                .joinToString("") { "%02x".format(it) },
        )

        val result = runBlocking { service.write("/target.bin", payload, expect) }
        check(result is ApiResult.Success<*>) { "workspace write failed: $result" }
    }

    private fun awaitMarker(marker: Path): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(marker)) return true
            Thread.sleep(1)
        }
        return false
    }

    private fun payloadSize(index: Int, runs: Int): Int {
        if (runs == 1 || index == runs - 1) return MAX_BYTES
        val fraction = index.toDouble() / (runs - 1)
        return (MIN_BYTES * (MAX_BYTES.toDouble() / MIN_BYTES).pow(fraction))
            .toInt()
            .coerceIn(MIN_BYTES, MAX_BYTES)
    }

    private fun payloadByte(index: Int): Byte = (index % 251 + 1).toByte()

    private fun repeatedByteSha256(size: Int, byte: Byte): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(64 * 1024) { byte }
        var remaining = size
        while (remaining > 0) {
            val count = minOf(remaining, chunk.size)
            digest.update(chunk, 0, count)
            remaining -= count
        }
        return digest.digest()
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun deleteTemporaryFiles(root: Path) {
        Files.newDirectoryStream(root, ".workspace-write-*.tmp").use { entries ->
            entries.forEach(Files::deleteIfExists)
        }
    }
}
