package io.github.spectordev9.opencode.connection

data class SseFrame(
    val event: String? = null,
    val data: String = "",
    val id: String? = null,
    val retry: Long? = null,
)

class SseParser {
    private val line = StringBuilder()
    private val data = StringBuilder()
    private var event: String? = null
    private var id: String? = null
    private var retry: Long? = null
    private var skipNextLf = false
    private var firstLine = true

    fun feed(chunk: String): List<SseFrame> {
        val frames = mutableListOf<SseFrame>()
        for (character in chunk) {
            when (character) {
                '\r' -> {
                    processLine(frames)
                    skipNextLf = true
                }
                '\n' -> {
                    if (skipNextLf) {
                        skipNextLf = false
                    } else {
                        processLine(frames)
                    }
                }
                else -> {
                    skipNextLf = false
                    line.append(character)
                }
            }
        }
        return frames
    }

    fun finish(): List<SseFrame> {
        val frames = mutableListOf<SseFrame>()
        if (line.isNotEmpty()) processLine(frames)
        dispatch(frames)
        return frames
    }

    fun reset() {
        line.clear()
        data.clear()
        event = null
        id = null
        retry = null
        skipNextLf = false
        firstLine = true
    }

    private fun processLine(frames: MutableList<SseFrame>) {
        var value = line.toString()
        line.clear()
        if (firstLine) {
            firstLine = false
            if (value.startsWith("\uFEFF")) value = value.removePrefix("\uFEFF")
        }
        if (value.isEmpty()) {
            dispatch(frames)
            return
        }
        if (value.startsWith(":")) return
        val separator = value.indexOf(':')
        val field = if (separator < 0) value else value.substring(0, separator)
        var fieldValue = if (separator < 0) "" else value.substring(separator + 1)
        if (fieldValue.startsWith(" ")) fieldValue = fieldValue.removePrefix(" ")
        when (field) {
            "data" -> data.append(fieldValue).append('\n')
            "event" -> event = fieldValue
            "id" -> id = fieldValue
            "retry" -> retry = fieldValue.toLongOrNull()
        }
    }

    private fun dispatch(frames: MutableList<SseFrame>) {
        if (data.isEmpty() && event == null && id == null && retry == null) return
        val payload = data.toString().removeSuffix("\n")
        frames += SseFrame(event = event, data = payload, id = id, retry = retry)
        data.clear()
        event = null
        id = null
        retry = null
    }
}
