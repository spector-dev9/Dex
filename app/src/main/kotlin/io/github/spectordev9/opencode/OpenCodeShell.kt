package io.github.spectordev9.opencode

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/* Design tokens mirroring the reference mobile client (`Test` App.css). */
private val DarkBg = Color(0xFF10111B)
private val Panel = Color(0xFF191A29)
private val Panel2 = Color(0xFF1E1F30)
private val Panel3 = Color(0xFF24253A)
private val BorderCol = Color(0xFF2B2C42)
private val BorderStrong = Color(0xFF3D3E5A)
private val TextCol = Color(0xFFF7F6FF)
private val Muted = Color(0xFFA7A5BC)
private val Muted2 = Color(0xFF8E8CA6)
private val Accent = Color(0xFF8B72F8)
private val AccentStrong = Color(0xFF7B60EF)
private val AccentSoft = Color(0xFF248B72F8)
private val Highlight = Color(0xFFC084FC)
private val SuccessCol = Color(0xFF68D6A4)
private val WarningCol = Color(0xFFE6B86B)
private val DangerCol = Color(0xFFF08B9D)
private val UserBubbleBg = Color(0xFF2A2456)
private val UserBubbleBorder = Color(0xFF4D8274EA)
private val ToolChipBg = Color(0xFF19191E)
private val ToolChipBorder = Color(0xFF303038)
private val CodeBg = Color(0xFF14151F)
private val CodeBorder = Color(0xFF32334E)
private val CodeText = Color(0xFFD8D6EA)
private val InlineCodeText = Color(0xFFE6C07B)
private val TopbarBg = Color(0xFF151518)
private val TerminalBlack = Color(0xFF0B0E14)
private val TerminalGreen = Color(0xFF68D6A4)

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
    runtimeStateFlow: kotlinx.coroutines.flow.StateFlow<io.github.spectordev9.opencode.core.runtime.RuntimeState>? = null,
    serverStateFlow: kotlinx.coroutines.flow.StateFlow<io.github.spectordev9.opencode.core.supervisor.ServerState>? = null,
    onOpenLicenses: () -> Unit,
    onRetryRuntime: () -> Unit = {},
) {
    val runtimeState = runtimeStateFlow?.collectAsState()?.value
    val serverState = serverStateFlow?.collectAsState()?.value
    val chatState = chat?.state?.collectAsState()?.value
    var selectedTab by remember { mutableStateOf(AppTab.Chat) }
    var showSettings by remember { mutableStateOf(false) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondary = AccentStrong,
            onSecondary = Color.White,
            tertiary = Highlight,
            background = DarkBg,
            surface = Panel,
            onSurface = TextCol,
            onBackground = TextCol,
            surfaceVariant = Panel3,
            onSurfaceVariant = Muted,
            error = DangerCol,
        ),
    ) {
        Scaffold(
            containerColor = DarkBg,
            topBar = {
                TopAppBar(
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                        containerColor = TopbarBg,
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("◈", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Column {
                                Text("OPENCODE", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "MOBILE WORKSPACE",
                                    fontSize = 9.sp,
                                    letterSpacing = 1.4.sp,
                                    color = Muted2,
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
                                            online -> SuccessCol
                                            runtimeState?.status ==
                                                io.github.spectordev9.opencode.core.runtime.RuntimeStateStatus.FAILED
                                                -> DangerCol
                                            else -> WarningCol
                                        },
                                    ),
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = { showSettings = true }, enabled = chatState?.connected == true) {
                            Text("Settings", color = Muted, fontSize = 12.sp)
                        }
                        TextButton(onClick = onOpenLicenses) {
                            Text("Licenses", color = Muted, fontSize = 12.sp)
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Panel) {
                    AppTab.entries.forEach { tab ->
                        val selected = selectedTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { selectedTab = tab },
                            icon = {
                                Text(
                                    tab.glyph,
                                    fontSize = 15.sp,
                                    color = if (selected) Accent else Muted2,
                                )
                            },
                            label = {
                                Text(
                                    tab.label,
                                    fontSize = 10.sp,
                                    color = if (selected) TextCol else Muted2,
                                )
                            },
                        )
                    }
                }
            },
        ) { padding ->
            when (selectedTab) {
                AppTab.Chat -> ChatHome(
                    modifier = Modifier.padding(padding),
                    chat = chat,
                    runtimeState = runtimeState,
                    serverState = serverState,
                    onRetryRuntime = onRetryRuntime,
                )
                AppTab.Files -> FilesScreen(
                    modifier = Modifier.padding(padding),
                    files = files,
                )
                AppTab.Terminal -> TerminalScreen(
                    modifier = Modifier.padding(padding),
                    ptyService = ptyService,
                )
            }
        }

        if (showSettings && chat != null) {
            SettingsSheet(
                chat = chat,
                state = chatState ?: ChatUiState(),
                onDismiss = { showSettings = false },
            )
        }
    }
}

@Composable
private fun ChatHome(
    modifier: Modifier = Modifier,
    chat: ChatController?,
    runtimeState: io.github.spectordev9.opencode.core.runtime.RuntimeState?,
    serverState: io.github.spectordev9.opencode.core.supervisor.ServerState?,
    onRetryRuntime: () -> Unit = {},
) {
    val banner = runtimeBanner(runtimeState, serverState)
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
            color = Panel,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderCol),
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
                    Text(banner.label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextCol)
                    Text(banner.detail, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (banner.version != null) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = Panel3,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderStrong),
                    ) {
                        Text(
                            "v${banner.version}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontSize = 10.sp,
                            color = Muted,
                        )
                    }
                }
            }
        }

        if (chat != null && chatState != null && chatState.connected) {
            ChatScreen(
                chat = chat,
                state = chatState,
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
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var showSessions by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue()) }
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
                color = Panel2,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderCol),
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
                        color = TextCol,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text("▾", color = Muted2, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = Panel2,
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentStrong.copy(alpha = 0.45f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { showModelPicker = true },
            ) {
                val model = state.selectedModel ?: state.defaultModel
                Text(
                    model?.let { "${it.providerID}/${it.modelID}" } ?: "Model",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    fontSize = 11.sp,
                    color = Muted,
                    maxLines = 1,
                )
            }
            TextButton(
                onClick = { scope.launch { chat.newSession() } },
                enabled = !state.sending,
            ) {
                Text("＋", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (state.error != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0x12DF7B7B),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x52DF7B7B)),
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
                        color = Color(0xFFE8B7B7),
                    )
                    TextButton(
                        onClick = { scope.launch { chat.selectSession(state.activeSessionId ?: return@launch) } },
                        enabled = state.activeSessionId != null,
                    ) {
                        Text("Retry", color = Accent, fontSize = 12.sp)
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
                                .background(AccentSoft)
                                .border(1.dp, AccentStrong.copy(alpha = 0.4f), RoundedCornerShape(50)),
                        ) {
                            Text(
                                "✳",
                                modifier = Modifier.align(Alignment.Center),
                                color = Accent,
                                fontSize = 20.sp,
                            )
                        }
                        Text("What should we work on?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextCol)
                        Text(
                            "Send the first prompt to start this session.",
                            fontSize = 12.sp,
                            color = Muted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("◈ Server online", fontSize = 10.sp, color = Muted2)
                            Text("⌁ ${modelLabel(state)}", fontSize = 10.sp, color = Muted2, maxLines = 1)
                            Text("◫ workspace", fontSize = 10.sp, color = Muted2)
                        }
                    }
                }
                items(SUGGESTIONS) { suggestion ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Panel2,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderCol),
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
                            color = Muted,
                        )
                    }
                }
            }
            items(state.messages, key = { it.info.id }) { message ->
                MessageRow(message)
            }
            if (state.sending) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        TypingDots()
                        Text("OpenCode is working…", fontSize = 11.sp, color = Muted2)
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
            color = Panel2,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF41414A)),
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                BasicTextField(
                    value = draft,
                    onValueChange = { next -> draft = next },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp),
                    enabled = true,
                    textStyle = TextStyle(color = Color(0xFFF3F3F6), fontSize = 14.sp, lineHeight = 20.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    decorationBox = { innerTextField ->
                        Box(Modifier.heightIn(min = 40.dp)) {
                            if (draft.text.isEmpty()) {
                                Text(
                                    "Ask OpenCode to inspect, explain, or change this project…",
                                    color = Color(0xFF777782),
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
                            color = if (selected) AccentSoft else Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (selected) AccentStrong.copy(alpha = 0.5f) else BorderCol,
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
                                color = if (selected) Color(0xFFD9D2FF) else Muted2,
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
                            state.sending -> Color(0x33DF7B7B)
                            canSend -> Accent
                            else -> Panel3
                        },
                        border = if (state.sending) {
                            androidx.compose.foundation.BorderStroke(1.dp, Color(0x66DF7B7B))
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
                                    state.sending -> Color(0xFFE8B7B7)
                                    canSend -> Color.White
                                    else -> Muted2
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
                    .background(Accent.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun MessageRow(message: Message) {
    val isUser = message.info.role == "user"
    val text = message.parts
        .filter { it.type == "text" && it.ignored != true && it.synthetic != true }
        .joinToString("\n\n") { it.text.orEmpty() }
        .trim()
    val tools = message.parts.filter { it.type == "tool" }
    val errors = message.parts.filter { it.type == "error" }

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
                color = Color(0xFFBDBDC8),
            )
            if (!isUser && message.info.modelID != null) {
                Text(message.info.modelID.orEmpty(), fontSize = 10.sp, color = Color(0xFF7E7E8A))
            }
        }
        if (isUser) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp,
                ),
                color = UserBubbleBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, UserBubbleBorder),
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    fontSize = 14.sp,
                    color = Color(0xFFF1EFFF),
                    lineHeight = 20.sp,
                )
            }
        } else {
            if (text.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    splitCodeFences(text).forEach { segment ->
                        when (segment) {
                            is TextSegment.Plain -> Text(
                                text = segment.text,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                                color = Color(0xFFE2E2E9),
                            )
                            is TextSegment.Code -> Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = CodeBg,
                                border = androidx.compose.foundation.BorderStroke(1.dp, CodeBorder),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = segment.text.trimEnd(),
                                    modifier = Modifier
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    color = CodeText,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                    }
                }
            }
            tools.forEach { part -> ToolCard(part) }
            errors.forEach { part ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0x12DF7B7B),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x52DF7B7B)),
                ) {
                    Text(
                        text = part.text.orEmpty().ifBlank { "Task failed" },
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        fontSize = 11.sp,
                        color = Color(0xFFE8B7B7),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCard(part: Part) {
    var expanded by remember(part.id) { mutableStateOf(false) }
    val status = part.state.stringField("status") ?: "done"
    val title = part.state.stringField("title")
        ?: part.tool?.replaceFirstChar { it.uppercase() }
        ?: "Tool"
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = ToolChipBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, ToolChipBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFAAAAB6))
                Text(status, fontSize = 10.sp, color = Color(0xFF81818D))
            }
            if (expanded) {
                val output = part.state.stringField("output")
                if (!output.isNullOrBlank()) {
                    Text(
                        text = output.take(4000),
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth(),
                        color = CodeText,
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
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0x5949371A),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x66D8A85B)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                "Permission · ${permission.permission}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF1DDB8),
            )
            val command = permission.metadata.stringField("command")
            if (!command.isNullOrBlank()) {
                Text(
                    command,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFE6C07B),
                )
            }
            if (permission.patterns.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    permission.patterns.take(4).forEach { pattern ->
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = Color(0x29000000),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x40D8A85B)),
                        ) {
                            Text(
                                pattern,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                fontSize = 9.sp,
                                color = Color(0xFFD8C39C),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = { onAnswer(PermissionResponse.ONCE) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD9D2FF)),
                ) {
                    Text("Allow once", fontSize = 11.sp)
                }
                TextButton(
                    onClick = { onAnswer(PermissionResponse.ALWAYS) },
                    colors = ButtonDefaults.textButtonColors(contentColor = SuccessCol),
                ) {
                    Text("Always allow", fontSize = 11.sp)
                }
                TextButton(
                    onClick = { onAnswer(PermissionResponse.REJECT) },
                    colors = ButtonDefaults.textButtonColors(contentColor = DangerCol),
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel2,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sessions", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextCol)
                TextButton(onClick = onNew) { Text("＋ New session", color = Accent) }
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
                            color = TextCol,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatRelativeTime(session.time.updated),
                            fontSize = 11.sp,
                            color = Muted2,
                        )
                    }
                    HorizontalDivider(color = BorderCol)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel2,
        titleContentColor = TextCol,
        textContentColor = Muted,
        title = { Text("Model", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Muted) }
        },
        text = {
            Box(Modifier.sizeIn(maxHeight = 380.dp)) {
                if (state.providers.isEmpty()) {
                    Text(
                        "No providers configured yet.\nThe on-device mock handles prompts until a real provider profile is added.",
                        fontSize = 13.sp,
                        color = Muted,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.providers.forEach { provider ->
                            item {
                                Text(
                                    provider.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Accent,
                                )
                            }
                            items(provider.models, key = { "${provider.id}/${it.id}" }) { model ->
                                val selected = state.selectedModel?.providerID == provider.id &&
                                    state.selectedModel?.modelID == model.id
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) AccentSoft else Panel3,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (selected) AccentStrong else BorderCol,
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
                                        color = if (selected) Color(0xFFE5E1FF) else TextCol,
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
    chat: ChatController,
    state: ChatUiState,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel2,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Settings", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextCol)
            Text(
                "Provider keys are stored on the device server (PUT /auth) and picked up by real provider profiles when they ship.",
                fontSize = 11.sp,
                color = Muted2,
            )
            if (state.authMessage != null) {
                Text(state.authMessage, fontSize = 11.sp, color = SuccessCol)
            }
            QUICK_PROVIDERS.forEach { provider ->
                var key by remember(provider.id) { mutableStateOf("") }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(provider.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextCol)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = key,
                            onValueChange = { next -> key = next },
                            modifier = Modifier
                                .weight(1f)
                                .border(
                                    1.dp,
                                    BorderStrong,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Color(0xFFF3F3F6),
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
                                            color = Color(0xFF777782),
                                            fontSize = 12.sp,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                        TextButton(
                            onClick = { scope.launch { chat.saveApiKey(provider.id, key) } },
                            enabled = key.isNotBlank(),
                        ) {
                            Text("Save", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                HorizontalDivider(color = BorderCol)
            }
        }
    }
}

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
                .background(AccentSoft)
                .border(1.dp, AccentStrong.copy(alpha = 0.4f), RoundedCornerShape(50)),
        ) {
            Text(
                "✳",
                modifier = Modifier.align(Alignment.Center),
                color = Accent,
                fontSize = 20.sp,
            )
        }
        Text(banner.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextCol)
        Text(
            banner.detail,
            fontSize = 12.sp,
            color = Muted,
            textAlign = TextAlign.Center,
        )
        if (showRetry) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
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
) {
    var session by remember { mutableStateOf<PtySession?>(null) }
    var status by remember { mutableStateOf(if (ptyService == null) "PTY service not connected" else "Starting PTY") }
    var output by remember { mutableStateOf("OpenCode Mobile terminal\n") }
    var input by remember { mutableStateOf(TextFieldValue()) }
    var forwardedInput by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

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
                Text("TERMINAL", fontSize = 20.sp, fontWeight = FontWeight.Black, color = TextCol)
                Text(status, fontSize = 12.sp, color = Muted2)
            }
            TextButton(onClick = { focusRequester.requestFocus() }, enabled = session != null) {
                Text("Focus input", color = Accent)
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            color = TerminalBlack,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderCol),
        ) {
            Text(
                text = if (session == null) {
                    "PTY service is not wired in this build.\n\nThe terminal UI is ready for the native PtyService."
                } else {
                    output
                },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                color = TerminalGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("ESC" to "\u001b", "TAB" to "\t", "CTRL-C" to "\u0003", "ALT" to "\u001b", "↑" to "\u001b[A", "↓" to "\u001b[B").forEach { (label, sequence) ->
                KeyButton(label, enabled = session != null) { sendControl(sequence) }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF41414A), RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = Panel2,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("$", color = Accent, fontWeight = FontWeight.Bold)
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
                        color = TextCol,
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
                                    color = Color(0xFF777782),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                TextButton(onClick = ::submitInput, enabled = session != null) {
                    Text("Send", color = Accent)
                }
            }
        }
    }
}

@Composable
private fun RowScope.KeyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .sizeIn(minHeight = 40.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, if (enabled) BorderStrong else BorderCol, RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) Panel3 else Panel2,
    ) {
        Box(Modifier.padding(horizontal = 4.dp, vertical = 10.dp)) {
            Text(
                label,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Color(0xFFD9D2FF) else Muted2,
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
) {
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
                Text("FILES", fontSize = 20.sp, fontWeight = FontWeight.Black, color = TextCol)
                Text(
                    filesState?.let { state ->
                        if (state.currentPath.isBlank()) "workspace/" else state.currentPath
                    } ?: "workspace",
                    fontSize = 12.sp,
                    color = Muted2,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = { scope.launch { files?.goUp() } },
                enabled = filesState?.let { it.connected && it.currentPath.isNotBlank() } == true,
            ) {
                Text("Up", color = Accent)
            }
        }

        if (filesState == null || !filesState.connected) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Panel,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderCol),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Files are available once the supervised server is online.",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp,
                    color = Muted,
                )
            }
        } else {
            if (filesState.error != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0x12DF7B7B),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x52DF7B7B)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        filesState.error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 12.sp,
                        color = Color(0xFFE8B7B7),
                    )
                }
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
                            color = Accent,
                        )
                    }
                }
                items(filesState.entries, key = { it.path }) { entry ->
                    val isDirectory = entry.type == FileEntryType.DIRECTORY
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Panel,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderCol),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { scope.launch { files?.open(entry) } },
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (isDirectory) "▸" else "▤",
                                fontSize = 13.sp,
                                color = if (isDirectory) Accent else Muted2,
                            )
                            Text(
                                entry.name,
                                fontSize = 13.sp,
                                color = TextCol,
                                fontWeight = if (isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
            onDismiss = { scope.launch { files?.closePreview() } },
        )
    }
}

@Composable
private fun FilePreviewDialog(
    path: String,
    content: String?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel2,
        titleContentColor = TextCol,
        textContentColor = CodeText,
        title = {
            Text(
                path.substringAfterLast('/'),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Muted) } },
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
                        color = Accent,
                    )
                } else {
                    Text(
                        text = content?.ifBlank { "(empty file)" } ?: "(unavailable)",
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = CodeText,
                    )
                }
            }
        },
    )
}

/** Rendered segments of assistant text; fenced blocks become code surfaces. */
private sealed interface TextSegment {
    data class Plain(val text: String) : TextSegment

    data class Code(val text: String) : TextSegment
}

private fun splitCodeFences(text: String): List<TextSegment> {
    val segments = mutableListOf<TextSegment>()
    var remainder = text
    while (true) {
        val start = remainder.indexOf("```")
        if (start < 0) {
            if (remainder.isNotBlank()) segments += TextSegment.Plain(remainder)
            break
        }
        val before = remainder.substring(0, start)
        if (before.isNotBlank()) segments += TextSegment.Plain(before)
        val bodyStart = start + 3
        val end = remainder.indexOf("```", bodyStart)
        if (end < 0) {
            remainder.substring(bodyStart).takeIf { it.isNotBlank() }
                ?.let { segments += TextSegment.Code(it) }
            break
        }
        remainder.substring(bodyStart, end).takeIf { it.isNotBlank() }
            ?.let { segments += TextSegment.Code(it) }
        remainder = remainder.substring(end + 3)
    }
    return segments.map { segment ->
        if (segment is TextSegment.Code) {
            val body = segment.text
            val tag = body.substringBefore('\n')
            val hasLanguageTag = body.contains('\n') &&
                tag.isNotEmpty() && tag.length <= 20 && !tag.contains(' ')
            when {
                hasLanguageTag -> TextSegment.Code(body.substringAfter('\n', ""))
                body.startsWith('\n') -> TextSegment.Code(body.removePrefix("\n"))
                else -> segment
            }
        } else {
            segment
        }
    }
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
): RuntimeBanner {
    val offline = RuntimeBanner(
        label = "RUNTIME OFFLINE",
        detail = "Sessions and tools are unavailable",
        dotColor = DangerCol,
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
                dotColor = WarningCol,
                version = null,
            )
        runtimeState.status == io.github.spectordev9.opencode.core.runtime.RuntimeStateStatus.FAILED
            -> RuntimeBanner(
                label = "RUNTIME FAILED",
                detail = runtimeState.reason ?: "Runtime installation or verification failed",
                dotColor = DangerCol,
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
                        dotColor = SuccessCol,
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
                        dotColor = WarningCol,
                        version = null,
                    )
                io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.DEGRADED ->
                    RuntimeBanner(
                        label = "SERVER DEGRADED",
                        detail = serverState.reason ?: "Supervised server is degraded",
                        dotColor = WarningCol,
                        version = null,
                    )
                else ->
                    RuntimeBanner(
                        label = "SERVER STOPPED",
                        detail = "Runtime is ready; the supervised server is not running",
                        dotColor = DangerCol,
                        version = null,
                    )
            }
        }
    }
}
