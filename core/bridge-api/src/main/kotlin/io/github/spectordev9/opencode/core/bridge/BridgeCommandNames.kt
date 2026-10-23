package io.github.spectordev9.opencode.core.bridge

object BridgeCommandNames {
    const val RUNTIME_STATUS = "runtime.status"
    const val RUNTIME_START = "runtime.start"
    const val RUNTIME_STOP = "runtime.stop"
    const val RUNTIME_RESTART = "runtime.restart"
    const val RUNTIME_EXPORT_DIAGNOSTICS = "runtime.exportDiagnostics"

    const val SESSION_LIST = "session.list"
    const val SESSION_GET = "session.get"
    const val SESSION_CREATE = "session.create"
    const val SESSION_UPDATE = "session.update"
    const val SESSION_DELETE = "session.delete"
    const val SESSION_STATUS = "session.status"
    const val SESSION_FORK = "session.fork"
    const val SESSION_ABORT = "session.abort"
    const val SESSION_REVERT = "session.revert"
    const val SESSION_UNREVERT = "session.unrevert"
    const val SESSION_SUMMARIZE = "session.summarize"
    const val SESSION_DIFF = "session.diff"
    const val SESSION_MESSAGES = "session.messages"
    const val SESSION_PROMPT_ASYNC = "session.promptAsync"
    const val SESSION_COMMAND = "session.command"
    const val SESSION_SHELL = "session.shell"
    const val SESSION_PERMISSION_REPLY = "session.permissionReply"

    const val WORKSPACE_LIST = "workspace.list"
    const val WORKSPACE_STAT = "workspace.stat"
    const val WORKSPACE_READ_RANGE = "workspace.readRange"
    const val WORKSPACE_WRITE = "workspace.write"
    const val WORKSPACE_MKDIR = "workspace.mkdir"
    const val WORKSPACE_RENAME = "workspace.rename"
    const val WORKSPACE_MOVE = "workspace.move"
    const val WORKSPACE_TRASH = "workspace.trash"
    const val WORKSPACE_RESTORE = "workspace.restore"
    const val WORKSPACE_SEARCH = "workspace.search"

    const val GIT_STATUS = "git.status"
    const val GIT_DIFF = "git.diff"
    const val GIT_INIT = "git.init"
    const val GIT_FETCH = "git.fetch"
    const val GIT_PULL = "git.pull"
    const val GIT_BRANCHES = "git.branches"
    const val GIT_CHECKOUT = "git.checkout"
    const val GIT_COMMIT = "git.commit"

    const val TERMINAL_OPEN = "terminal.open"
    const val TERMINAL_WRITE = "terminal.write"
    const val TERMINAL_RESIZE = "terminal.resize"
    const val TERMINAL_SIGNAL = "terminal.signal"
    const val TERMINAL_CLOSE = "terminal.close"

    const val SETTINGS_GET = "settings.get"
    const val SETTINGS_SET = "settings.set"

    val all: Set<String> = setOf(
        RUNTIME_STATUS,
        RUNTIME_START,
        RUNTIME_STOP,
        RUNTIME_RESTART,
        RUNTIME_EXPORT_DIAGNOSTICS,
        SESSION_LIST,
        SESSION_GET,
        SESSION_CREATE,
        SESSION_UPDATE,
        SESSION_DELETE,
        SESSION_STATUS,
        SESSION_FORK,
        SESSION_ABORT,
        SESSION_REVERT,
        SESSION_UNREVERT,
        SESSION_SUMMARIZE,
        SESSION_DIFF,
        SESSION_MESSAGES,
        SESSION_PROMPT_ASYNC,
        SESSION_COMMAND,
        SESSION_SHELL,
        SESSION_PERMISSION_REPLY,
        WORKSPACE_LIST,
        WORKSPACE_STAT,
        WORKSPACE_READ_RANGE,
        WORKSPACE_WRITE,
        WORKSPACE_MKDIR,
        WORKSPACE_RENAME,
        WORKSPACE_MOVE,
        WORKSPACE_TRASH,
        WORKSPACE_RESTORE,
        WORKSPACE_SEARCH,
        GIT_STATUS,
        GIT_DIFF,
        GIT_INIT,
        GIT_FETCH,
        GIT_PULL,
        GIT_BRANCHES,
        GIT_CHECKOUT,
        GIT_COMMIT,
        TERMINAL_OPEN,
        TERMINAL_WRITE,
        TERMINAL_RESIZE,
        TERMINAL_SIGNAL,
        TERMINAL_CLOSE,
        SETTINGS_GET,
        SETTINGS_SET,
    )
}
