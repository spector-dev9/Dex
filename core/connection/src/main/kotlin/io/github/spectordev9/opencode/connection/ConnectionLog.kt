package io.github.spectordev9.opencode.connection

class ConnectionLog(
    private val capacity: Int = 256,
) {
    private val lines = ArrayDeque<String>()

    fun add(line: String) {
        val safe = ErrorMapping.redact(line)
        synchronized(lines) {
            lines.addLast(safe)
            while (lines.size > capacity) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun text(): String = snapshot().joinToString("\n")
}
