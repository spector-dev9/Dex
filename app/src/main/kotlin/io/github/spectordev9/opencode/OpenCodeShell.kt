package io.github.spectordev9.opencode

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.spectordev9.opencode.chat.ChatController
import io.github.spectordev9.opencode.chat.ChatUiState
import io.github.spectordev9.opencode.chat.FilesController
import io.github.spectordev9.opencode.chat.flattenFileTree
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.model.FileEntryType
import io.github.spectordev9.opencode.core.model.Message
import io.github.spectordev9.opencode.core.model.Part
import io.github.spectordev9.opencode.core.model.PermissionRequest
import io.github.spectordev9.opencode.core.opencode.client.PermissionResponse
import io.github.spectordev9.opencode.core.opencode.client.PromptModel
import io.github.spectordev9.opencode.core.terminal.PtyEvent
import io.github.spectordev9.opencode.core.terminal.PtyService
import io.github.spectordev9.opencode.core.terminal.PtySession
import io.github.spectordev9.opencode.core.terminal.PtySize
import io.github.spectordev9.opencode.core.terminal.PtySpec
import io.github.spectordev9.opencode.ui.CodeBlock
import io.github.spectordev9.opencode.ui.LocalOpencodePalette
import io.github.spectordev9.opencode.ui.MdBlock
import io.github.spectordev9.opencode.ui.MarkdownText
import io.github.spectordev9.opencode.ui.OpencodePalette
import io.github.spectordev9.opencode.ui.UiPrefs
import io.github.spectordev9.opencode.ui.copyToClipboard
import io.github.spectordev9.opencode.ui.materialScheme
import io.github.spectordev9.opencode.ui.rememberOpencodePalette
import io.github.spectordev9.opencode.ui.shareText
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch


private enum class AppTab(val label: String, val glyph: String) {
    Chat("Chat", "◈"),
    Files("Files", "◫"),
    Terminal("Terminal", "⌨"),
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun OpenCodeShell(
    ptyService: PtyService? = null,
    chat: ChatController? = null,
    files: FilesController? = null,
    uiPrefs: UiPrefs? = null,
    runtimeStateFlow: kotlinx.coroutines.flow.StateFlow<io.github.spectordev9.opencode.core.runtime.RuntimeState>? = null,
    serverStateFlow: kotlinx.coroutines.flow.StateFlow<io.github.spectordev9.opencode.core.supervisor.ServerState>? = null,
    onOpenLicenses: () -> Unit,
    onRetryRuntime: () -> Unit = {},
) {
    val runtimeState = runtimeStateFlow?.collectAsState()?.value
    val serverState = serverStateFlow?.collectAsState()?.value
    val chatState = chat?.state?.collectAsState()?.value
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Chat) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val palette = rememberOpencodePalette(uiPrefs)
    val darkTheme = when (
        uiPrefs?.themeMode?.collectAsState()?.value ?: UiPrefs.ThemeMode.SYSTEM
    ) {
        UiPrefs.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        UiPrefs.ThemeMode.LIGHT -> false
        UiPrefs.ThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = materialScheme(palette, darkTheme),
    ) {
        CompositionLocalProvider(LocalOpencodePalette provides palette) {
        Scaffold(
            containerColor = palette.DarkBg,
            topBar = {
                TopAppBar(
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                        containerColor = palette.TopbarBg,
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("◈", color = palette.Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Column {
                                Text("OPENCODE", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "MOBILE WORKSPACE",
                                    fontSize = 9.sp,
                                    letterSpacing = 1.4.sp,
                                    color = palette.Muted2,
                                )
                            }
                            val online = serverState?.status ==
                                io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.HEALTHY
                            Box(
                                Modifier
                                    .padding(start = 4.dp)
                                    .size(7.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        when {
                                            online -> palette.SuccessCol
                                            runtimeState?.status ==
                                                io.github.spectordev9.opencode.core.runtime.RuntimeStateStatus.FAILED
                                                -> palette.DangerCol
                                            else -> palette.WarningCol
                                        },
                                    ),
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = { showSettings = true }) {
                            Text("Settings", color = palette.Muted, fontSize = 12.sp)
                        }
                        TextButton(onClick = onOpenLicenses) {
                            Text("Licenses", color = palette.Muted, fontSize = 12.sp)
                        }
                    },
                )
            },
            bottomBar = {},
        ) { padding ->
            BoxWithConstraints(Modifier.padding(padding)) {
                val wide = maxWidth >= ResponsiveBreakpoint
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        NavigationRail(containerColor = palette.Panel) {
                            AppTab.entries.forEach { tab ->
                                NavigationRailItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    icon = {
                                        Text(
                                            tab.glyph,
                                            fontSize = 16.sp,
                                            color = if (selectedTab == tab) palette.Accent else palette.Muted2,
                                        )
                                    },
                                    label = {
                                        Text(
                                            tab.label,
                                            fontSize = 10.sp,
                                            color = if (selectedTab == tab) palette.TextCol else palette.Muted2,
                                        )
                                    },
                                )
                            }
                        }
                        // Wide screens keep the chat visible alongside the
                        // selected secondary pane (F58).
                        if (selectedTab == AppTab.Chat) {
                            ChatHome(
                                modifier = Modifier.weight(1f),
                                chat = chat,
                                runtimeState = runtimeState,
                                serverState = serverState,
                                uiPrefs = uiPrefs,
                                onRetryRuntime = onRetryRuntime,
                            )
                        } else {
                            Row(Modifier.weight(1f)) {
                                ChatHome(
                                    modifier = Modifier.weight(1f),
                                    chat = chat,
                                    runtimeState = runtimeState,
                                    serverState = serverState,
                                    uiPrefs = uiPrefs,
                                    onRetryRuntime = onRetryRuntime,
                                )
                                VerticalDivider(color = palette.BorderCol)
                                when (selectedTab) {
                                    AppTab.Files -> FilesScreen(
                                        modifier = Modifier.weight(1f),
                                        files = files,
                                        uiPrefs = uiPrefs,
                                    )
                                    else -> TerminalScreen(
                                        modifier = Modifier.weight(1f),
                                        ptyService = ptyService,
                                        uiPrefs = uiPrefs,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            when (selectedTab) {
                                AppTab.Chat -> ChatHome(
                                    modifier = Modifier.fillMaxSize(),
                                    chat = chat,
                                    runtimeState = runtimeState,
                                    serverState = serverState,
                                    uiPrefs = uiPrefs,
                                    onRetryRuntime = onRetryRuntime,
                                )
                                AppTab.Files -> FilesScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    files = files,
                                    uiPrefs = uiPrefs,
                                )
                                AppTab.Terminal -> TerminalScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    ptyService = ptyService,
                                    uiPrefs = uiPrefs,
                                )
                            }
                        }
                        NavigationBar(containerColor = palette.Panel) {
                            AppTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    icon = {
                                        Text(
                                            tab.glyph,
                                            fontSize = 15.sp,
                                            color = if (selectedTab == tab) palette.Accent else palette.Muted2,
                                        )
                                    },
                                    label = {
                                        Text(
                                            tab.label,
                                            fontSize = 10.sp,
                                            color = if (selectedTab == tab) palette.TextCol else palette.Muted2,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        if (showSettings) {
            SettingsSheet(
                chat = chat,
                state = chatState ?: ChatUiState(),
                uiPrefs = uiPrefs,
                isConnected = chatState?.connected == true,
                onDismiss = { showSettings = false },
            )
        }
    }
}

/** Width threshold (F58): below → bottom bar, at/above → rail + two panes. */
private val ResponsiveBreakpoint = 840.dp

@Composable
private fun ChatHome(
    modifier: Modifier = Modifier,
    chat: ChatController?,
    runtimeState: io.github.spectordev9.opencode.core.runtime.RuntimeState?,
    serverState: io.github.spectordev9.opencode.core.supervisor.ServerState?,
    uiPrefs: UiPrefs? = null,
    onRetryRuntime: () -> Unit = {},
) {
    val palette = LocalOpencodePalette.current
    val banner = runtimeBanner(runtimeState, serverState, palette)
    val chatState = chat?.state?.collectAsState()?.value

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = palette.Panel,
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.BorderCol),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(banner.dotColor),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    Text(banner.label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = palette.TextCol)
                    Text(banner.detail, fontSize = 12.sp, color = palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (banner.version != null) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = palette.Panel3,
                        border = androidx.compose.foundation.BorderStroke(1.dp, palette.BorderStrong),
                    ) {
                        Text(
                            "v${banner.version}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontSize = 10.sp,
                            color = palette.Muted,
                        )
                    }
                }
            }
        }

        if (chat != null && chatState != null && chatState.connected) {
            ChatScreen(
                chat = chat,
                state = chatState,
                uiPrefs = uiPrefs,
                modifier = Modifier.weight(1f),
            )
        } else {
            RuntimeOfflinePanel(
                modifier = Modifier.weight(1f),
                banner = banner,
                showRetry = runtimeState?.status ==
                    io.github.spectordev9.opencode.core.runtime.RuntimeStateStatus.FAILED,
                onRetry = onRetryRuntime,
            )
        }
    }
}

@Composable
private fun ChatScreen(
    chat: ChatController,
    state: ChatUiState,
    uiPrefs: UiPrefs?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOpencodePalette.current
    // Font sizes (F59): fall back to defaults when prefs are unavailable.
    val chatTextSize = uiPrefs?.chatTextSize?.collectAsState()?.value ?: 14
    val codeTextSize = uiPrefs?.codeTextSize?.collectAsState()?.value ?: 12
    val scope = rememberCoroutineScope()
    var showSessions by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val activeSession = state.sessions.firstOrNull { it.id == state.activeSessionId }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.activeSessionId) {
        val count = state.messages.size
        if (count > 0) {
            listState.animateScrollToItem(count - 1)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = palette.Panel2,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.BorderCol),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { showSessions = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        activeSession?.title?.ifBlank { "Untitled session" } ?: "New session",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.TextCol,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text("▾", color = palette.Muted2, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = palette.Panel2,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.AccentStrong.copy(alpha = 0.45f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { showModelPicker = true },
            ) {
                val model = state.selectedModel ?: state.defaultModel
                Text(
                    model?.let { "${it.providerID}/${it.modelID}" } ?: "Model",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    fontSize = 11.sp,
                    color = palette.Muted,
                    maxLines = 1,
                )
            }
            TextButton(
                onClick = { scope.launch { chat.newSession() } },
                enabled = !state.sending,
            ) {
                Text("＋", color = palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (state.error != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = palette.ErrorSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.ErrorBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.error,
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        color = palette.ErrorText,
                    )
                    TextButton(
                        onClick = { scope.launch { chat.selectSession(state.activeSessionId ?: return@launch) } },
                        enabled = state.activeSessionId != null,
                    ) {
                        Text("Retry", color = palette.Accent, fontSize = 12.sp)
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (state.messages.isEmpty() && !state.sending) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(50))
                                .background(palette.AccentSoft)
                                .border(1.dp, palette.AccentStrong.copy(alpha = 0.4f), RoundedCornerShape(50)),
                        ) {
                            Text(
                                "✳",
                                modifier = Modifier.align(Alignment.Center),
                                color = palette.Accent,
                                fontSize = 20.sp,
                            )
                        }
                        Text("What should we work on?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = palette.TextCol)
                        Text(
                            "Send the first prompt to start this session.",
                            fontSize = 12.sp,
                            color = palette.Muted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("◈ Server online", fontSize = 10.sp, color = palette.Muted2)
                            Text("⌁ ${modelLabel(state)}", fontSize = 10.sp, color = palette.Muted2, maxLines = 1)
                            Text("◫ workspace", fontSize = 10.sp, color = palette.Muted2)
                        }
                    }
                }
                items(SUGGESTIONS) { suggestion ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = palette.Panel2,
                        border = androidx.compose.foundation.BorderStroke(1.dp, palette.BorderCol),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                draft = TextFieldValue()
                                scope.launch { chat.sendOrStart(suggestion) }
                            },
                    ) {
                        Text(
                            suggestion,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            fontSize = 13.sp,
                            color = palette.Muted,
                        )
                    }
                }
            }
            items(state.messages, key = { it.info.id }) { message ->
                MessageRow(message, chatTextSize, codeTextSize)
            }
            if (state.sending) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        TypingDots()
                        Text("OpenCode is working…", fontSize = 11.sp, color = palette.Muted2)
                    }
                }
            }
        }

        state.permissions.forEach { permission ->
            PermissionCard(
                permission = permission,
                onAnswer = { response ->
                    scope.launch { chat.answerPermission(permission.id, response) }
                },
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(15.dp),
            color = palette.Panel2,
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.ToolChipBorder),
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                BasicTextField(
                    value = draft,
                    onValueChange = { next -> draft = next },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp),
                    enabled = true,
                    textStyle = TextStyle(color = palette.TextCol, fontSize = 14.sp, lineHeight = 20.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    decorationBox = { innerTextField ->
                        Box(Modifier.heightIn(min = 40.dp)) {
                            if (draft.text.isEmpty()) {
                                Text(
                                    "Ask OpenCode to inspect, explain, or change this project…",
                                    color = palette.Muted2,
                                    fontSize = 13.sp,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("build", "plan").forEach { mode ->
                        val selected = state.mode == mode
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = if (selected) palette.AccentSoft else Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (selected) palette.AccentStrong.copy(alpha = 0.5f) else palette.BorderCol,
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .clickable { scope.launch { chat.setMode(mode) } },
                        ) {
                            Text(
                                mode.replaceFirstChar { it.uppercase() },
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) palette.TextCol else palette.Muted2,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    val canSend = !state.sending && draft.text.isNotBlank()
                    // STR-4: while the agent is streaming the send control
                    // becomes the cancel affordance (abort() → session.idle).
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            state.sending -> palette.ErrorSurface
                            canSend -> palette.Accent
                            else -> palette.Panel3
                        },
                        border = if (state.sending) {
                            androidx.compose.foundation.BorderStroke(1.dp, palette.ErrorBorder)
                        } else {
                            null
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = state.sending || canSend) {
                                if (state.sending) {
                                    scope.launch { chat.abort() }
                                } else {
                                    val text = draft.text.take(MAX_COMPOSER_CHARS)
                                    draft = TextFieldValue()
                                    scope.launch { chat.sendOrStart(text) }
                                }
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (state.sending) "■" else "➤",
                                color = when {
                                    state.sending -> palette.ErrorText
                                    canSend -> Color.White
                                    else -> palette.Muted2
                                },
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSessions) {
        SessionSheet(
            state = state,
            onSelect = { sessionID ->
                showSessions = false
                scope.launch { chat.selectSession(sessionID) }
            },
            onNew = {
                showSessions = false
                scope.launch { chat.newSession() }
            },
            onDismiss = { showSessions = false },
        )
    }

    if (showModelPicker) {
        ModelPickerDialog(
            state = state,
            onPick = { model ->
                showModelPicker = false
                scope.launch { chat.setModel(model) }
            },
            onDismiss = { showModelPicker = false },
        )
    }
}

private fun modelLabel(state: ChatUiState): String =
    (state.selectedModel ?: state.defaultModel)?.let { "${it.providerID}/${it.modelID}" } ?: "no model"

@Composable
private fun TypingDots() {
    val palette = LocalOpencodePalette.current
    val transition = rememberInfiniteTransition(label = "typing")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "typing-dot",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(palette.Accent.copy(alpha = alpha)),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: Message,
    chatTextSize: Int,
    codeTextSize: Int,
) {
    val palette = LocalOpencodePalette.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember(message.info.id) { mutableStateOf(false) }
    val isUser = message.info.role == "user"
    val text = message.parts
        .filter { it.type == "text" && it.ignored != true && it.synthetic != true }
        .joinToString("\n\n") { it.text.orEmpty() }
        .trim()
    val tools = message.parts.filter { it.type == "tool" }
    val errors = message.parts.filter { it.type == "error" }
    val copyLabel = if (isUser) "Copy prompt" else "Copy message"

    fun openMenu() {
        menuOpen = true
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isUser) "You" else "OpenCode",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.Muted,
            )
            if (!isUser && message.info.modelID != null) {
                Text(message.info.modelID.orEmpty(), fontSize = 10.sp, color = palette.Muted2)
            }
        }
        if (isUser) {
            Box {
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp,
                    ),
                    color = palette.UserBubbleBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.UserBubbleBorder),
                ) {
                    Text(
                        text = text,
                        modifier = Modifier
                            .combinedClickable(onClick = {}, onLongClick = ::openMenu)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        fontSize = chatTextSize.sp,
                        color = palette.TextCol,
                        lineHeight = (chatTextSize + 6).sp,
                    )
                }
                MessageActionsMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    text = text,
                    clipboard = clipboard,
                    copyLabel = copyLabel,
                    onCopied = { menuOpen = false },
                    onShare = { shareText(context, text) },
                )
            }
        } else {
            if (text.isNotEmpty()) {
                Box {
                    Column(
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = ::openMenu),
                    ) {
                        MarkdownText(
                            markdown = text,
                            palette = palette,
                            textSize = chatTextSize,
                            codeSize = codeTextSize,
                        )
                    }
                    MessageActionsMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        text = text,
                        clipboard = clipboard,
                        copyLabel = copyLabel,
                        onCopied = { menuOpen = false },
                        onShare = { shareText(context, text) },
                    )
                }
            }
            tools.forEach { part -> ToolCard(part) }
            errors.forEach { part ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = palette.ErrorSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.ErrorBorder),
                ) {
                    Text(
                        text = part.text.orEmpty().ifBlank { "Task failed" },
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        fontSize = 11.sp,
                        color = palette.ErrorText,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    text: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    copyLabel: String,
    onCopied: () -> Unit,
    onShare: () -> Unit,
) {
    val palette = LocalOpencodePalette.current
    androidx.compose.material3.DropdownMenu(
        expanded = expanded && text.isNotEmpty(),
        onDismissRequest = onDismiss,
        containerColor = palette.Panel2,
    ) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(copyLabel, fontSize = 13.sp, color = palette.TextCol) },
            onClick = {
                clipboard.setText(AnnotatedString(text))
                onCopied()
            },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Share", fontSize = 13.sp, color = palette.TextCol) },
            onClick = {
                onDismiss()
                onShare()
            },
        )
    }
}

@Composable
private fun ToolCard(part: Part) {
    val palette = LocalOpencodePalette.current
    var expanded by remember(part.id) { mutableStateOf(false) }
    val status = part.state.stringField("status") ?: "done"
    val title = part.state.stringField("title")
        ?: part.tool?.replaceFirstChar { it.uppercase() }
        ?: "Tool"
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = palette.ToolChipBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.ToolChipBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = palette.Muted)
                Text(status, fontSize = 10.sp, color = palette.Muted2)
            }
            if (expanded) {
                val output = part.state.stringField("output")
                if (!output.isNullOrBlank()) {
                    Text(
                        text = output.take(4000),
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth(),
                        color = palette.CodeText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    permission: PermissionRequest,
    onAnswer: (PermissionResponse) -> Unit,
) {
    val palette = LocalOpencodePalette.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = palette.WarningCol.copy(alpha = 0.25f),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.WarningCol.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                "Permission · ${permission.permission}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = palette.WarningCol,
            )
            val command = permission.metadata.stringField("command")
            if (!command.isNullOrBlank()) {
                Text(
                    command,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = palette.InlineCodeText,
                )
            }
            if (permission.patterns.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    permission.patterns.take(4).forEach { pattern ->
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = palette.ToolChipBg,
                            border = androidx.compose.foundation.BorderStroke(1.dp, palette.WarningCol.copy(alpha = 0.35f)),
                        ) {
                            Text(
                                pattern,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                fontSize = 9.sp,
                                color = palette.WarningCol,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = { onAnswer(PermissionResponse.ONCE) },
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.TextCol),
                ) {
                    Text("Allow once", fontSize = 11.sp)
                }
                TextButton(
                    onClick = { onAnswer(PermissionResponse.ALWAYS) },
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.SuccessCol),
                ) {
                    Text("Always allow", fontSize = 11.sp)
                }
                TextButton(
                    onClick = { onAnswer(PermissionResponse.REJECT) },
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.DangerCol),
                ) {
                    Text("Reject", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun SessionSheet(
    state: ChatUiState,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalOpencodePalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.Panel2,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sessions", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = palette.TextCol)
                TextButton(onClick = onNew) { Text("＋ New session", color = palette.Accent) }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(state.sessions, key = { it.id }) { session ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(session.id) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            session.title.ifBlank { session.slug },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.TextCol,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatRelativeTime(session.time.updated),
                            fontSize = 11.sp,
                            color = palette.Muted2,
                        )
                    }
                    HorizontalDivider(color = palette.BorderCol)
                }
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(
    state: ChatUiState,
    onPick: (PromptModel) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalOpencodePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.Panel2,
        titleContentColor = palette.TextCol,
        textContentColor = palette.Muted,
        title = { Text("Model", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = palette.Muted) }
        },
        text = {
            Box(Modifier.sizeIn(maxHeight = 380.dp)) {
                if (state.providers.isEmpty()) {
                    Text(
                        "No providers configured yet.\nThe on-device mock handles prompts until a real provider profile is added.",
                        fontSize = 13.sp,
                        color = palette.Muted,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.providers.forEach { provider ->
                            item {
                                Text(
                                    provider.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.Accent,
                                )
                            }
                            items(provider.models, key = { "${provider.id}/${it.id}" }) { model ->
                                val selected = state.selectedModel?.providerID == provider.id &&
                                    state.selectedModel?.modelID == model.id
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) palette.AccentSoft else palette.Panel3,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (selected) palette.AccentStrong else palette.BorderCol,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onPick(PromptModel(provider.id, model.id)) },
                                ) {
                                    Text(
                                        model.name,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                        fontSize = 13.sp,
                                        color = if (selected) palette.TextCol else palette.TextCol,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

private data class QuickProvider(
    val id: String,
    val name: String,
)

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun SettingsSheet(
    chat: ChatController?,
    state: ChatUiState,
    uiPrefs: UiPrefs?,
    isConnected: Boolean,
    onDismiss: () -> Unit,
) {
    val palette = LocalOpencodePalette.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.Panel2,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Settings", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = palette.TextCol)
            if (uiPrefs != null) {
                AppearanceSection(uiPrefs)
                HorizontalDivider(color = palette.BorderCol)
            }
            Text(
                "Provider keys are stored on the device server (PUT /auth) and picked up by real provider profiles when they ship.",
                fontSize = 11.sp,
                color = palette.Muted2,
            )
            if (!isConnected) {
                Text(
                    "The server is not connected. API keys can be saved when the server is ready.",
                    fontSize = 11.sp,
                    color = palette.WarningCol,
                )
            }
            if (state.authMessage != null) {
                Text(state.authMessage, fontSize = 11.sp, color = palette.SuccessCol)
            }
            if (state.error != null) {
                Text(state.error, fontSize = 11.sp, color = palette.DangerCol)
            }
            QUICK_PROVIDERS.forEach { provider ->
                var key by remember(provider.id) { mutableStateOf("") }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(provider.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.TextCol)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = key,
                            onValueChange = { next -> key = next },
                            modifier = Modifier
                                .weight(1f)
                                .border(
                                    1.dp,
                                    palette.BorderStrong,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            singleLine = true,
                            textStyle = TextStyle(
                                color = palette.TextCol,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (key.isEmpty()) {
                                        Text(
                                            "${provider.id} API key",
                                            color = palette.Muted2,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                        TextButton(
                            onClick = { scope.launch { chat?.saveApiKey(provider.id, key) } },
                            enabled = key.isNotBlank() && isConnected && chat != null,
                        ) {
                            Text("Save", color = palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                HorizontalDivider(color = palette.BorderCol)
            }
        }
    }
}

/** Appearance controls (F59): theme mode, dynamic colors, font sizes. */
@Composable
private fun AppearanceSection(uiPrefs: UiPrefs) {
    val palette = LocalOpencodePalette.current
    val mode by uiPrefs.themeMode.collectAsState()
    val dynamic by uiPrefs.dynamicColors.collectAsState()
    val chatSize by uiPrefs.chatTextSize.collectAsState()
    val codeSize by uiPrefs.codeTextSize.collectAsState()
    val terminalSize by uiPrefs.terminalTextSize.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Appearance", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.TextCol)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UiPrefs.ThemeMode.entries.forEach { candidate ->
                val selected = mode == candidate
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) palette.AccentSoft else palette.Panel3,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (selected) palette.AccentStrong else palette.BorderCol,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { uiPrefs.setThemeMode(candidate) },
                ) {
                    Text(
                        candidate.label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        fontSize = 12.sp,
                        color = if (selected) palette.TextCol else palette.Muted2,
                    )
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dynamic colors", fontSize = 12.sp, color = palette.TextCol)
                    Text(
                        "Follow the system wallpaper palette (Android 12+).",
                        fontSize = 10.sp,
                        color = palette.Muted2,
                    )
                }
                Switch(checked = dynamic, onCheckedChange = { uiPrefs.setDynamicColors(it) })
            }
        }
        FontSizeSlider(
            label = "Chat text size",
            value = chatSize,
            range = UiPrefsChatRange,
            onChange = { uiPrefs.setChatTextSize(it) },
        )
        FontSizeSlider(
            label = "Code font size",
            value = codeSize,
            range = UiPrefsCodeRange,
            onChange = { uiPrefs.setCodeTextSize(it) },
        )
        FontSizeSlider(
            label = "Terminal font size",
            value = terminalSize,
            range = UiPrefsTerminalRange,
            onChange = { uiPrefs.setTerminalTextSize(it) },
        )
    }
}

@Composable
private fun FontSizeSlider(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    val palette = LocalOpencodePalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = palette.TextCol, modifier = Modifier.weight(1f))
            Text("$value sp", fontSize = 11.sp, color = palette.Muted2)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
        )
    }
}

private val UiPrefsChatRange = UiPrefsChatMin..UiPrefsChatMax
private const val UiPrefsChatMin = 12
private const val UiPrefsChatMax = 18
private val UiPrefsCodeRange = UiPrefsCodeMin..UiPrefsCodeMax
private const val UiPrefsCodeMin = 10
private const val UiPrefsCodeMax = 16
private val UiPrefsTerminalRange = UiPrefsTerminalMin..UiPrefsTerminalMax
private const val UiPrefsTerminalMin = 10
private const val UiPrefsTerminalMax = 18

private val QUICK_PROVIDERS = listOf(
    QuickProvider("anthropic", "Anthropic"),
    QuickProvider("openai", "OpenAI"),
    QuickProvider("google", "Google"),
    QuickProvider("openrouter", "OpenRouter"),
    QuickProvider("groq", "Groq"),
    QuickProvider("github-copilot", "GitHub Copilot"),
)

@Composable
private fun RuntimeOfflinePanel(
    modifier: Modifier = Modifier,
    banner: RuntimeBanner,
    showRetry: Boolean,
    onRetry: () -> Unit,
) {
    val palette = LocalOpencodePalette.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .padding(top = 24.dp)
                .size(46.dp)
                .clip(RoundedCornerShape(50))
                .background(palette.AccentSoft)
                .border(1.dp, palette.AccentStrong.copy(alpha = 0.4f), RoundedCornerShape(50)),
        ) {
            Text(
                "✳",
                modifier = Modifier.align(Alignment.Center),
                color = palette.Accent,
                fontSize = 20.sp,
            )
        }
        Text(banner.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = palette.TextCol)
        Text(
            banner.detail,
            fontSize = 12.sp,
            color = palette.Muted,
            textAlign = TextAlign.Center,
        )
        if (showRetry) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.Accent,
                    contentColor = Color.White,
                ),
            ) {
                Text("Retry install")
            }
        }
    }
}

private const val MAX_COMPOSER_CHARS = 20_000
private val SUGGESTIONS = listOf(
    "Explain this project and its main entry points.",
    "Find one likely bug and explain how to fix it.",
    "Suggest a small feature that would improve this app.",
)

@Composable
private fun TerminalScreen(
    modifier: Modifier = Modifier,
    ptyService: PtyService?,
    uiPrefs: UiPrefs? = null,
) {
    val palette = LocalOpencodePalette.current
    val clipboard = LocalClipboardManager.current
    var session by remember { mutableStateOf<PtySession?>(null) }
    var status by remember { mutableStateOf(if (ptyService == null) "PTY service not connected" else "Starting PTY") }
    var output by remember { mutableStateOf("OpenCode Mobile terminal\n") }
    var input by remember { mutableStateOf(TextFieldValue()) }
    var forwardedInput by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val terminalTextSize = uiPrefs?.terminalTextSize?.collectAsState()?.value ?: 13
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(ptyService) {
        if (ptyService == null) {
            status = "PTY service not connected"
            return@LaunchedEffect
        }
        when (
            val result = ptyService.open(
                PtySpec(
                    executable = "/bin/sh",
                    arguments = listOf("-i"),
                    environment = mapOf("TERM" to "xterm-256color"),
                    workingDirectory = "/workspace",
                    initialSize = PtySize(columns = 80, rows = 24),
                    name = "OpenCode terminal",
                ),
            )
        ) {
            is ApiResult.Success -> {
                session = result.value
                status = "Connected · 80 × 24"
                output += "PTY connected.\n"
            }
            is ApiResult.Failure -> status = result.error.message ?: result.error.code.name
        }
    }

    LaunchedEffect(session) {
        session?.events?.collect { event ->
            when (event) {
                is PtyEvent.Data -> {
                    output = (output + event.bytes.toString(Charsets.UTF_8)).takeLast(MAX_TERMINAL_CHARS)
                }
                is PtyEvent.Exit -> {
                    status = "Process exited · ${event.status.exitCode ?: "signal ${event.status.signal}"}"
                }
            }
        }
    }

    DisposableEffect(session) {
        onDispose { session?.close() }
    }

    fun write(bytes: ByteArray) {
        val activeSession = session ?: return
        when (val result = activeSession.write(bytes)) {
            is ApiResult.Success -> Unit
            is ApiResult.Failure -> status = result.error.message ?: result.error.code.name
        }
    }

    fun sendControl(sequence: String) {
        write(sequence.toByteArray(Charsets.UTF_8))
    }

    fun submitInput() {
        sendControl("\r")
        input = TextFieldValue()
        forwardedInput = ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("TERMINAL", fontSize = 20.sp, fontWeight = FontWeight.Black, color = palette.TextCol)
                Text(status, fontSize = 12.sp, color = palette.Muted2)
            }
            TextButton(onClick = { focusRequester.requestFocus() }, enabled = session != null) {
                Text("Focus", color = palette.Accent)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Selection + clipboard (F22): copy the scrollback buffer or
            // paste from the Android clipboard into the PTY.
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(output)) },
                enabled = session != null && output.isNotBlank(),
            ) {
                Text("Copy", color = palette.Accent)
            }
            TextButton(
                onClick = {
                    val pasted = clipboard.getText()?.toString().orEmpty()
                    if (pasted.isNotEmpty()) write(pasted.toByteArray(Charsets.UTF_8))
                },
                enabled = session != null,
            ) {
                Text("Paste", color = palette.Accent)
            }
            TextButton(
                onClick = { output = "" },
                enabled = session != null,
            ) {
                Text("Clear", color = palette.Accent)
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxSize(),
                shape = RoundedCornerShape(12.dp),
                color = palette.TerminalBlack,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.BorderCol),
            ) {
                // Text selection handles for manual copy of a range (F22).
                SelectionContainer {
                    Text(
                        text = if (session == null) {
                            "PTY service is not wired in this build.\n\nThe terminal UI is ready for the native PtyService."
                        } else {
                            output
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(14.dp),
                        color = palette.TerminalGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = terminalTextSize.sp,
                        lineHeight = (terminalTextSize + 6).sp,
                    )
                }
            }
            val atBottom by remember {
                androidx.compose.runtime.derivedStateOf {
                    scrollState.value >= scrollState.maxValue - 48
                }
            }
            if (!atBottom && session != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = palette.Panel3,
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.BorderStrong),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                        },
                ) {
                    Text(
                        "↓ latest",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 11.sp,
                        color = palette.Accent,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("ESC" to "\u001b", "TAB" to "\t", "CTRL-C" to "\u0003", "ALT" to "\u001b", "↑" to "\u001b[A", "↓" to "\u001b[B").forEach { (label, sequence) ->
                KeyButton(label, enabled = session != null) { sendControl(sequence) }
            }
        }

        // Second extra-key row (F20): navigation and symbols the soft
        // keyboard tends to hide.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "←" to "\u001b[D",
                "→" to "\u001b[C",
                "/" to "/",
                "-" to "-",
                "|" to "|",
                "~" to "~",
            ).forEach { (label, sequence) ->
                KeyButton(label, enabled = session != null) { sendControl(sequence) }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, palette.ToolChipBorder, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = palette.Panel2,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("$", color = palette.Accent, fontWeight = FontWeight.Bold)
                BasicTextField(
                    value = input,
                    onValueChange = { next ->
                        input = next
                        if (next.composition == null) {
                            val prefix = forwardedInput.commonPrefixWith(next.text).length
                            val removed = forwardedInput.length - prefix
                            val inserted = next.text.drop(prefix)
                            if (removed > 0) sendControl("\u007f".repeat(removed))
                            if (inserted.isNotEmpty()) write(inserted.toByteArray(Charsets.UTF_8))
                            forwardedInput = next.text
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .focusable(enabled = session != null)
                        .onPreviewKeyEvent { event ->
                            if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN || session == null) {
                                return@onPreviewKeyEvent false
                            }
                            val sequence = when {
                                event.isCtrlPressed && event.key == Key.C -> "\u0003"
                                event.isAltPressed && event.nativeKeyEvent.unicodeChar != 0 ->
                                    "\u001b" + event.nativeKeyEvent.unicodeChar.toChar()
                                event.key == Key.Enter -> "\r"
                                event.key == Key.Tab -> "\t"
                                event.key == Key.Escape -> "\u001b"
                                event.key == Key.DirectionUp -> "\u001b[A"
                                event.key == Key.DirectionDown -> "\u001b[B"
                                event.key == Key.DirectionLeft -> "\u001b[D"
                                event.key == Key.DirectionRight -> "\u001b[C"
                                else -> null
                            }
                            if (sequence == "\r") {
                                submitInput()
                                true
                            } else if (sequence != null) {
                                sendControl(sequence)
                                true
                            } else {
                                false
                            }
                        },
                    enabled = session != null,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = palette.TextCol,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submitInput() }),
                    decorationBox = { innerTextField ->
                        Box {
                            if (input.text.isEmpty()) {
                                Text(
                                    if (session == null) "Terminal unavailable" else "Type a command",
                                    color = palette.Muted2,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                TextButton(onClick = ::submitInput, enabled = session != null) {
                    Text("Send", color = palette.Accent)
                }
            }
        }
    }
}

@Composable
private fun RowScope.KeyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalOpencodePalette.current
    Surface(
        modifier = Modifier
            .weight(1f)
            .sizeIn(minHeight = 40.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, if (enabled) palette.BorderStrong else palette.BorderCol, RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) palette.Panel3 else palette.Panel2,
    ) {
        Box(Modifier.padding(horizontal = 4.dp, vertical = 10.dp)) {
            Text(
                label,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) palette.TextCol else palette.Muted2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val MAX_TERMINAL_CHARS = 24_000

@Composable
private fun FilesScreen(
    modifier: Modifier = Modifier,
    files: FilesController?,
    uiPrefs: UiPrefs? = null,
) {
    val palette = LocalOpencodePalette.current
    val filesState = files?.state?.collectAsState()?.value
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("FILES", fontSize = 20.sp, fontWeight = FontWeight.Black, color = palette.TextCol)
                Text(
                    filesState?.let { state ->
                        if (state.currentPath.isBlank()) "workspace/" else state.currentPath
                    } ?: "workspace",
                    fontSize = 12.sp,
                    color = palette.Muted2,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = { scope.launch { files?.goUp() } },
                enabled = filesState?.let { it.connected && it.currentPath.isNotBlank() } == true,
            ) {
                Text("Up", color = palette.Accent)
            }
        }

        if (filesState == null || !filesState.connected) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = palette.Panel,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.BorderCol),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Files are available once the supervised server is online.",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp,
                    color = palette.Muted,
                )
            }
        } else {
            if (filesState.error != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = palette.ErrorSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.ErrorBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        filesState.error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 12.sp,
                        color = palette.ErrorText,
                    )
                }
            }
            // Hierarchical tree rows (F11): expanded directories render
            // their children inline; the chevron toggles expansion.
            val treeRows = remember(filesState) {
                flattenFileTree(filesState.entries, filesState)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (filesState.loading && filesState.entries.isEmpty()) {
                    item {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = palette.Accent,
                        )
                    }
                }
                // Hierarchical tree (F11): expanded directories render their
                // children inline; the chevron toggles expansion.
                items(treeRows, key = { it.entry.path }) { row ->
                    val entry = row.entry
                    val isDirectory = entry.type == FileEntryType.DIRECTORY
                    val expanded = entry.path in filesState.expanded
                    val loadingDir = entry.path in filesState.loadingDirs
                    val (glyph, glyphColor) = fileIcon(entry, palette)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = palette.Panel,
                        border = androidx.compose.foundation.BorderStroke(1.dp, palette.BorderCol),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { scope.launch { files?.open(entry) } },
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                start = 10.dp + (row.depth * 16).dp,
                                end = 10.dp,
                                top = 10.dp,
                                bottom = 10.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isDirectory) {
                                Text(
                                    if (expanded) "▾" else "▸",
                                    fontSize = 13.sp,
                                    color = palette.Accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            scope.launch { files?.toggleExpand(entry) }
                                        },
                                )
                            } else {
                                Spacer(Modifier.width(13.dp))
                            }
                            Text(
                                glyph,
                                fontSize = 13.sp,
                                color = glyphColor,
                            )
                            Text(
                                entry.name,
                                fontSize = 13.sp,
                                color = palette.TextCol,
                                fontWeight = if (isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (loadingDir) {
                                Text("…", fontSize = 12.sp, color = palette.Muted2)
                            }
                        }
                    }
                }
            }
        }
    }

    if (filesState != null && filesState.previewPath != null) {
        FilePreviewDialog(
            path = filesState.previewPath.orEmpty(),
            content = filesState.previewText,
            loading = filesState.loading,
            fontSize = uiPrefs?.codeTextSize?.collectAsState()?.value ?: 12,
            onDismiss = { scope.launch { files?.closePreview() } },
        )
    }
}

/** File-type icon mapping (F11): glyph + stable color per extension group. */
private val DocExtensions = setOf("md", "txt", "rst", "adoc", "log")
private val CodeExtensions = setOf(
    "kt", "kts", "java", "py", "js", "jsx", "mjs", "ts", "tsx", "c", "cpp", "h", "hpp",
    "cc", "cs", "go", "rs", "rb", "php", "swift", "dart", "lua", "groovy", "scala",
)
private val ConfigExtensions = setOf("json", "yaml", "yml", "toml", "ini", "cfg", "properties", "xml", "gradle")
private val ScriptExtensions = setOf("sh", "bash", "zsh", "ps1", "bat")
private val ImageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico")
private val ArchiveExtensions = setOf("zip", "tar", "gz", "tgz", "7z", "apk", "jar")

private fun fileIcon(
    entry: io.github.spectordev9.opencode.core.model.FileEntry,
    palette: OpencodePalette,
): Pair<String, Color> {
    if (entry.type == FileEntryType.DIRECTORY) return "◫" to palette.Accent
    val extension = entry.name.substringAfterLast('.', "").lowercase()
    return when {
        extension in DocExtensions -> "≡" to palette.WarningCol
        extension in CodeExtensions -> "⌗" to palette.Highlight
        extension in ConfigExtensions -> "⚙" to palette.SuccessCol
        extension in ScriptExtensions -> "$" to palette.WarningCol
        extension in ImageExtensions -> "◼" to palette.DangerCol
        extension in ArchiveExtensions -> "▣" to palette.Muted2
        else -> "▤" to palette.Muted2
    }
}

@Composable
private fun FilePreviewDialog(
    path: String,
    content: String?,
    loading: Boolean,
    fontSize: Int,
    onDismiss: () -> Unit,
) {
    val palette = LocalOpencodePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.Panel2,
        titleContentColor = palette.TextCol,
        textContentColor = palette.CodeText,
        title = {
            Text(
                path.substringAfterLast('/'),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = palette.Muted) } },
        text = {
            Box(
                Modifier
                    .sizeIn(maxHeight = 420.dp)
                    .fillMaxWidth(),
            ) {
                if (loading && content == null) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = palette.Accent,
                    )
                } else {
                    Text(
                        text = content?.ifBlank { "(empty file)" } ?: "(unavailable)",
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize + 5).sp,
                        color = palette.CodeText,
                    )
                }
            }
        },
    )
}

private fun formatRelativeTime(millis: Long): String {
    if (millis <= 0L) return "—"
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}

private fun kotlinx.serialization.json.JsonObject?.stringField(name: String): String? =
    this?.let { (it[name] as? kotlinx.serialization.json.JsonPrimitive)?.content }

/** Chat-home banner state derived from the runtime and supervisor flows. */
private data class RuntimeBanner(
    val label: String,
    val detail: String,
    val dotColor: Color,
    val version: String?,
)

private fun runtimeBanner(
    runtimeState: io.github.spectordev9.opencode.core.runtime.RuntimeState?,
    serverState: io.github.spectordev9.opencode.core.supervisor.ServerState?,
    palette: OpencodePalette,
): RuntimeBanner {
    val offline = RuntimeBanner(
        label = "RUNTIME OFFLINE",
        detail = "Sessions and tools are unavailable",
        dotColor = palette.DangerCol,
        version = null,
    )
    return when {
        runtimeState == null -> offline
        runtimeState.status == io.github.spectordev9.opencode.core.runtime.RuntimeStateStatus.ABSENT
            -> offline
        runtimeState.status == io.github.spectordev9.opencode.core.runtime.RuntimeStateStatus.INSTALLING
            -> RuntimeBanner(
                label = "RUNTIME INSTALLING",
                detail = runtimeState.reason ?: "Pinned runtime is being verified and installed",
                dotColor = palette.WarningCol,
                version = null,
            )
        runtimeState.status == io.github.spectordev9.opencode.core.runtime.RuntimeStateStatus.FAILED
            -> RuntimeBanner(
                label = "RUNTIME FAILED",
                detail = runtimeState.reason ?: "Runtime installation or verification failed",
                dotColor = palette.DangerCol,
                version = null,
            )
        else -> {
            val endpoint = serverState?.endpoint
            when (serverState?.status) {
                io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.HEALTHY ->
                    RuntimeBanner(
                        label = "RUNTIME ONLINE",
                        detail = buildString {
                            if (endpoint != null) append("${endpoint.host}:${endpoint.port}")
                            append(" · supervised opencode server")
                        },
                        dotColor = palette.SuccessCol,
                        version = serverState.version?.takeIf { it.isNotBlank() },
                    )
                io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.STARTING,
                io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.BACKOFF,
                io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.CRASH_LOOP,
                ->
                    RuntimeBanner(
                        label = "SERVER RECOVERING",
                        detail = serverState.reason
                            ?: "Supervised server is starting or retrying on loopback",
                        dotColor = palette.WarningCol,
                        version = null,
                    )
                io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.DEGRADED ->
                    RuntimeBanner(
                        label = "SERVER DEGRADED",
                        detail = serverState.reason ?: "Supervised server is degraded",
                        dotColor = palette.WarningCol,
                        version = null,
                    )
                else ->
                    RuntimeBanner(
                        label = "SERVER STOPPED",
                        detail = "Runtime is ready; the supervised server is not running",
                        dotColor = palette.DangerCol,
                        version = null,
                    )
            }
        }
    }
}
