package io.github.spectordev9.opencode.spike

class NativePtyProbe private constructor() {
    external fun runProotShell(
        proot: String,
        loader: String,
        rootfs: String,
        runtimeRoot: String,
        timeoutMillis: Long,
    ): String

    companion object {
        val instance = NativePtyProbe()

        init {
            System.loadLibrary("ptyprobe")
        }
    }
}
