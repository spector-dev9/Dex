package io.github.spectordev9.opencode.terminal

/**
 * JNI bridge to the production native PTY backend (`libopencode_pty.so`,
 * built from app/src/main/cpp/opencode_pty.cpp).
 *
 * Semantics mirror the C side:
 * - [open] forks a pty child and returns a positive handle, or -1 on failure.
 * - [pollRead] returns bytes (>0), 0 timeout, -1 EOF, -2 handle error.
 * - [close] terminates the child group and releases the handle.
 */
internal object NativePty {
    init {
        System.loadLibrary("opencode_pty")
    }

    @JvmStatic
    external fun open(
        workdir: String,
        columns: Int,
        rows: Int,
        arguments: Array<String>,
        environmentNames: Array<String>,
        environmentValues: Array<String>,
    ): Int

    @JvmStatic
    external fun pollRead(handle: Int, buffer: ByteArray, timeoutMillis: Int): Int

    @JvmStatic
    external fun write(handle: Int, data: ByteArray, length: Int): Int

    @JvmStatic
    external fun resize(handle: Int, columns: Int, rows: Int): Int

    @JvmStatic
    external fun signal(handle: Int, number: Int): Int

    @JvmStatic
    external fun isAlive(handle: Int): Int

    @JvmStatic
    external fun exitCode(handle: Int): Int

    @JvmStatic
    external fun close(handle: Int)
}
