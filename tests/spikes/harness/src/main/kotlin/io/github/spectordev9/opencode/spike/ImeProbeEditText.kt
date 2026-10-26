package io.github.spectordev9.opencode.spike

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import android.widget.EditText

class ImeProbeEditText(context: Context) : EditText(context) {
    var eventListener: ((String) -> Unit)? = null
    private val eventTrace = mutableListOf<String>()
    private var droppedEventTraceEvents = 0
    private val eventTraceLock = Any()
    private var traceReady = false

    var hardwareKeyDownEvents = 0
        private set
    var hardwareKeyUpEvents = 0
        private set
    var imeSendKeyEvents = 0
        private set
    var commitTextEvents = 0
        private set
    var composingTextEvents = 0
        private set
    var deleteSurroundingEvents = 0
        private set
    var editorActions = 0
        private set
    var contentCommits = 0
        private set
    var selectionChanges = 0
        private set
    var contextMenuEvents = 0
        private set
    var lastEvent = "none"
        private set

    init {
        traceReady = true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(target, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                commitTextEvents++
                record("commitText length=${text?.length ?: 0}")
                return super.commitText(text, newCursorPosition)
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composingTextEvents++
                record("setComposingText length=${text?.length ?: 0}")
                return super.setComposingText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                deleteSurroundingEvents++
                record("deleteSurroundingText before=$beforeLength after=$afterLength")
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                imeSendKeyEvents++
                record(
                    "imeSendKeyEvent action=${actionName(event.action)} " +
                        "key=${KeyEvent.keyCodeToString(event.keyCode)} code=${event.keyCode} " +
                        "meta=${modifierNames(event.metaState)}",
                )
                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                editorActions++
                record("performEditorAction action=$editorAction")
                return super.performEditorAction(editorAction)
            }

            override fun commitContent(
                inputContentInfo: InputContentInfo,
                flags: Int,
                opts: Bundle?,
            ): Boolean {
                contentCommits++
                record("commitContent flags=$flags")
                return super.commitContent(inputContentInfo, flags, opts)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        hardwareKeyDownEvents++
        record(
            "hardwareKeyDown ${KeyEvent.keyCodeToString(keyCode)} code=$keyCode " +
                "meta=${modifierNames(event.metaState)}",
        )
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        hardwareKeyUpEvents++
        record(
            "hardwareKeyUp ${KeyEvent.keyCodeToString(keyCode)} code=$keyCode " +
                "meta=${modifierNames(event.metaState)}",
        )
        return super.onKeyUp(keyCode, event)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        selectionChanges++
        record("selection $selStart..$selEnd")
        super.onSelectionChanged(selStart, selEnd)
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        contextMenuEvents++
        record("contextMenu ${contextMenuName(id)}")
        return super.onTextContextMenuItem(id)
    }

    fun resetCounters() {
        hardwareKeyDownEvents = 0
        hardwareKeyUpEvents = 0
        imeSendKeyEvents = 0
        commitTextEvents = 0
        composingTextEvents = 0
        deleteSurroundingEvents = 0
        editorActions = 0
        contentCommits = 0
        selectionChanges = 0
        contextMenuEvents = 0
        lastEvent = "none"
        synchronized(eventTraceLock) {
            eventTrace.clear()
            droppedEventTraceEvents = 0
        }
    }

    fun recordTextChanged(length: Int) {
        record("textChanged length=$length")
    }

    fun eventTraceSnapshot(): List<String> = synchronized(eventTraceLock) {
        eventTrace.toList()
    }

    fun eventTraceDroppedCount(): Int = synchronized(eventTraceLock) {
        droppedEventTraceEvents
    }

    private fun record(event: String) {
        if (!traceReady) return
        lastEvent = event
        synchronized(eventTraceLock) {
            if (eventTrace.size < MAX_EVENT_TRACE_EVENTS) {
                eventTrace += event
            } else {
                droppedEventTraceEvents++
            }
        }
        post { eventListener?.invoke(event) }
    }

    private fun contextMenuName(id: Int): String = when (id) {
        android.R.id.paste -> "paste"
        android.R.id.pasteAsPlainText -> "pasteAsPlainText"
        android.R.id.cut -> "cut"
        android.R.id.copy -> "copy"
        android.R.id.selectAll -> "selectAll"
        android.R.id.undo -> "undo"
        android.R.id.redo -> "redo"
        else -> "id=$id"
    }

    private fun actionName(action: Int): String = when (action) {
        KeyEvent.ACTION_DOWN -> "down"
        KeyEvent.ACTION_UP -> "up"
        KeyEvent.ACTION_MULTIPLE -> "multiple"
        else -> "unknown($action)"
    }

    private fun modifierNames(metaState: Int): String = listOf(
        "SHIFT" to (metaState and (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON) != 0),
        "CTRL" to (metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_RIGHT_ON) != 0),
        "ALT" to (metaState and (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_RIGHT_ON) != 0),
        "FN" to (metaState and KeyEvent.META_FUNCTION_ON != 0),
    ).filter { it.second }
        .joinToString("+") { it.first }
        .ifEmpty { "none" }

    private companion object {
        const val MAX_EVENT_TRACE_EVENTS = 256
    }
}
