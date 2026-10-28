package io.github.spectordev9.opencode

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.spectordev9.opencode.chat.ChatController
import io.github.spectordev9.opencode.chat.ChatUiState
import io.github.spectordev9.opencode.chat.FilesController
import io.github.spectordev9.opencode.chat.FilesUiState
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.model.FileEntry
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

private val Ink = Color(0xFF202A27)
private val Paper = Color(0xFFF3F1E9)
private val Moss = Color(0xFF23745E)
private val Citrus = Color(0xFFE7B64A)
private val Clay = Color(0xFFB95F43)
private val TerminalBlack = Color(0xFF111916)
private val TerminalGreen = Color(0xFFB8E6C5)

private enum class AppTab(val label: String, val glyph: String) {
    Chat("Chat", "01"),
    Files("Files", "02"),
    Terminal("Terminal", "03"),
}

private data class VerificationTest(
    val id: String,
    val title: String,
    val summary: String,
)

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun OpenCodeShell(
    ptyService: PtyService? = null,
    chat: ChatController? = null,
    files: FilesController? = null,
    runtimeStateFlow: kotlinx.coroutines.flow.StateFlow<io.github.spectordev9.opencode.core.runtime.RuntimeState>? = null,
    serverStateFlow: kotlinx.coroutines.flow.StateFlow<io.github.spectordev9.opencode.core.supervisor.ServerState>? = null,
    onOpenLicenses: () -> Unit,
) {
    val runtimeState = runtimeStateFlow?.collectAsState()?.value
    val serverState = serverStateFlow?.collectAsState()?.value
    var selectedTab by remember { mutableStateOf(AppTab.Chat) }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Moss,
            onPrimary = Color.White,
            secondary = Clay,
            onSecondary = Color.White,
            tertiary = Citrus,
            background = Paper,
            surface = Color(0xFFFFFEFA),
            onSurface = Ink,
            onBackground = Ink,
            surfaceVariant = Color(0xFFE8E7DD),
            onSurfaceVariant = Color(0xFF535D58),
        ),
    ) {
        Scaffold(
            containerColor = Paper,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("OPENCODE", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "MOBILE WORKSPACE",
                                fontSize = 10.sp,
                                letterSpacing = 1.4.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = onOpenLicenses) { Text("Licenses") }
                    },
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFFFFFEFA)) {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Text(tab.glyph, fontSize = 11.sp) },
                            label = { Text(tab.label) },
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
    }
}

@Composable
private fun ChatHome(
    modifier: Modifier = Modifier,
    chat: ChatController?,
    runtimeState: io.github.spectordev9.opencode.core.runtime.RuntimeState?,
    serverState: io.github.spectordev9.opencode.core.supervisor.ServerState?,
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
            shape = RoundedCornerShape(8.dp),
            color = banner.container,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(banner.label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(banner.detail, fontSize = 12.sp)
                }
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .sizeIn(minWidth = 8.dp, minHeight = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(banner.dotColor),
                )
            }
        }

        if (chat != null && chatState != null && chatState.connected) {
            ChatScreen(
                chat = chat,
                state = chatState,
                modifier = Modifier.weight(1f),
            )
        } else {
            DiagnosticsPanel(modifier = Modifier.weight(1f))
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFE6E4DA),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { showSessions = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        activeSession?.title?.ifBlank { "Untitled session" } ?: "New session",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text("  ▾", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            TextButton(onClick = { scope.launch { chat.newSession() } }) {
                Text("+ New", fontSize = 13.sp)
            }
        }

        if (state.sending) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = Moss,
                trackColor = Color(0xFFE6E4DA),
            )
        }

        if (state.error != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF6E7E4),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    state.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 12.sp,
                    color = Color(0xFFB3261E),
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.messages.isEmpty() && !state.sending) {
                item {
                    Text(
                        "What should we work on?",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(SUGGESTIONS) { suggestion ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFEAF0EE),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                draft = TextFieldValue()
                                scope.launch { chat.sendOrStart(suggestion) }
                            },
                    ) {
                        Text(
                            suggestion,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            fontSize = 13.sp,
                            color = Moss,
                        )
                    }
                }
            }
            items(state.messages, key = { it.info.id }) { message ->
                MessageRow(message)
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
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFD0D0C5), RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFFFFFEFA),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("build", "plan").forEach { mode ->
                        val selected = state.mode == mode
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (selected) Color(0xFFE7F3EE) else Color(0xFFE6E4DA),
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { scope.launch { chat.setMode(mode) } },
                        ) {
                            Text(
                                mode.replaceFirstChar { it.uppercase() },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) Moss else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    val model = state.selectedModel ?: state.defaultModel
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFE6E4DA),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { showModelPicker = true },
                    ) {
                        Text(
                            model?.let { "${it.providerID}/${it.modelID}" } ?: "Model",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { next -> draft = next },
                        modifier = Modifier.weight(1f),
                        enabled = true,
                        textStyle = TextStyle(color = Ink, fontSize = 14.sp, lineHeight = 19.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        decorationBox = { innerTextField ->
                            Box {
                                if (draft.text.isEmpty()) {
                                    Text(
                                        "Send a message…",
                                        color = Color(0xFF838A84),
                                        fontSize = 14.sp,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    TextButton(
                        onClick = {
                            val text = draft.text.take(MAX_COMPOSER_CHARS)
                            draft = TextFieldValue()
                            scope.launch { chat.sendOrStart(text) }
                        },
                        enabled = !state.sending && draft.text.isNotBlank(),
                    ) {
                        Text("Send", fontWeight = FontWeight.SemiBold)
                    }
                    if (state.sending) {
                        TextButton(onClick = { scope.launch { chat.abort() } }) {
                            Text("Stop", color = Clay, fontWeight = FontWeight.SemiBold)
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
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = if (isUser) {
            androidx.compose.ui.Alignment.End
        } else {
            androidx.compose.ui.Alignment.Start
        },
    ) {
        Text(
            text = if (isUser) "You" else (message.info.agent ?: "Assistant"),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isUser) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Moss,
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    fontSize = 14.sp,
                    color = Color.White,
                )
            }
        } else {
            if (text.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    splitCodeFences(text).forEach { segment ->
                        when (segment) {
                            is TextSegment.Plain -> Text(
                                text = segment.text,
                                fontSize = 14.sp,
                                lineHeight = 19.sp,
                            )
                            is TextSegment.Code -> Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = TerminalBlack,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = segment.text.trimEnd(),
                                    modifier = Modifier
                                        .horizontalScroll(rememberScrollState())
                                        .padding(10.dp),
                                    color = TerminalGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                )
                            }
                        }
                    }
                }
            }
            tools.forEach { part -> ToolCard(part) }
            errors.forEach { part ->
                Text(
                    text = part.text.orEmpty().ifBlank { "Task failed" },
                    fontSize = 12.sp,
                    color = Color(0xFFB3261E),
                )
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
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFEAF0EE),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(status, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                val output = part.state.stringField("output")
                if (!output.isNullOrBlank()) {
                    Text(
                        text = output.take(4000),
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth(),
                        color = Ink,
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
        color = Color(0xFFF3EAD6),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Permission · ${permission.permission}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8A6D1A),
            )
            val command = permission.metadata.stringField("command")
            if (!command.isNullOrBlank()) {
                Text(
                    command,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Ink,
                )
            }
            if (permission.patterns.isNotEmpty()) {
                Text(
                    permission.patterns.joinToString("  "),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAnswer(PermissionResponse.ONCE) },
                    colors = ButtonDefaults.buttonColors(containerColor = Moss),
                ) {
                    Text("Once", fontSize = 13.sp)
                }
                Button(
                    onClick = { onAnswer(PermissionResponse.ALWAYS) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B8568)),
                ) {
                    Text("Always", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { onAnswer(PermissionResponse.REJECT) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Clay),
                ) {
                    Text("Reject", fontSize = 13.sp)
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
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text("Sessions", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onNew) { Text("+ New session") }
            LazyColumn {
                items(state.sessions, key = { it.id }) { session ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(session.id) }
                            .padding(vertical = 9.dp),
                    ) {
                        Text(
                            session.title.ifBlank { session.slug },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            formatRelativeTime(session.time.updated),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = Color(0xFFE6E4DA))
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
        title = { Text("Model", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        text = {
            Box(Modifier.sizeIn(maxHeight = 380.dp)) {
                if (state.providers.isEmpty()) {
                    Text(
                        "No providers configured yet.\nAuthenticate a provider or set a model in the server config.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.providers.forEach { provider ->
                            item {
                                Text(
                                    provider.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Moss,
                                )
                            }
                            items(provider.models, key = { "${provider.id}/${it.id}" }) { model ->
                                val selected = state.selectedModel?.providerID == provider.id &&
                                    state.selectedModel?.modelID == model.id
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (selected) Color(0xFFE7F3EE) else Color(0xFFE6E4DA),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            onPick(PromptModel(provider.id, model.id))
                                        },
                                ) {
                                    Text(
                                        model.name,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        fontSize = 13.sp,
                                        color = if (selected) Moss else Ink,
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

@Composable
private fun DiagnosticsPanel(modifier: Modifier = Modifier) {
    var selectedTest by remember { mutableStateOf("S1") }
    val tests = listOf(
        VerificationTest("S1", "S1", "Loader, health, and mock-provider prompt"),
        VerificationTest("S2-cold", "S2 cold start", "Cold launch and profile-a startup"),
        VerificationTest("S2-rss", "S2 idle RSS", "Idle memory footprint under load"),
        VerificationTest("S2-git", "S2 git status", "10k-file git status check"),
        VerificationTest("S2-npm", "S2 npm test", "npm validation on device"),
        VerificationTest("S3", "S3", "Guest shell and native PTY match"),
        VerificationTest("S6", "S6", "OpenCode 2.x contract check"),
        VerificationTest("S7", "S7", "Background survival and service soak"),
        VerificationTest("S8", "S8", "Gboard field-input test"),
        VerificationTest("RT-1", "RT-1", "Offline installability"),
        VerificationTest("RT-2", "RT-2", "Reference-device startup"),
        VerificationTest("LC-2", "LC-2", "10-minute active-agent run"),
        VerificationTest("TERM-5", "TERM-5", "Native terminal input with Gboard"),
        VerificationTest("STR-5", "STR-5", "Live-device drop test"),
    )

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Diagnostics", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "The checklist moves out of the way while the server is online.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val rows = tests.chunked(2)
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { test ->
                    val isSelected = selectedTest == test.id
                    OutlinedButton(
                        onClick = { selectedTest = test.id },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) Color(0xFFE7F3EE) else Color(0xFFFFFEFA),
                            contentColor = Ink,
                        ),
                    ) {
                        Text(test.title)
                    }
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        val selectedSummary = tests.firstOrNull { it.id == selectedTest }?.summary ?: "Select a test to verify."
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFFEAF0EE),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Selected test", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selectedTest, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(selectedSummary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("TERMINAL", fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { focusRequester.requestFocus() }, enabled = session != null) {
                Text("Focus input")
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(8.dp),
            color = TerminalBlack,
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
                .border(1.dp, Color(0xFFD0D0C5), RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFFFFFEFA),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("$", color = Moss, fontWeight = FontWeight.Bold)
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
                        color = Ink,
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
                                    color = Color(0xFF838A84),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                TextButton(onClick = ::submitInput, enabled = session != null) { Text("Send") }
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
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) Color(0xFFE1E7E0) else Color(0xFFE7E6DF),
    ) {
        Box(Modifier.padding(horizontal = 4.dp, vertical = 10.dp)) {
            Text(
                label,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Moss else Color(0xFF999C96),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("FILES", fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(
                    filesState?.let { state ->
                        if (state.currentPath.isBlank()) "workspace/" else state.currentPath
                    } ?: "workspace",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = { scope.launch { files?.goUp() } },
                enabled = filesState?.let { it.connected && it.currentPath.isNotBlank() } == true,
            ) {
                Text("Up")
            }
        }

        if (filesState == null || !filesState.connected) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFE6E4DA),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Files are available once the supervised server is online.",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            if (filesState.error != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFF6E7E4),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        filesState.error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 12.sp,
                        color = Color(0xFFB3261E),
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
                            color = Moss,
                        )
                    }
                }
                items(filesState.entries, key = { it.path }) { entry ->
                    val isDirectory = entry.type == FileEntryType.DIRECTORY
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFFFFEFA),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { scope.launch { files?.open(entry) } },
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                if (isDirectory) "▸" else "▤",
                                fontSize = 13.sp,
                                color = if (isDirectory) Moss else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                entry.name,
                                fontSize = 13.sp,
                                fontWeight = if (isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
        title = {
            Text(
                path.substringAfterLast('/'),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
                        color = Moss,
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
                        color = Ink,
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
    val container: Color,
)

private fun runtimeBanner(
    runtimeState: io.github.spectordev9.opencode.core.runtime.RuntimeState?,
    serverState: io.github.spectordev9.opencode.core.supervisor.ServerState?,
): RuntimeBanner {
    val offline = RuntimeBanner(
        label = "RUNTIME OFFLINE",
        detail = "Sessions and tools are unavailable",
        dotColor = Clay,
        container = Color(0xFFE4EEE7),
    )
    return when {
        runtimeState == null -> offline
        runtimeState.status == io.github.spectordev9.opencode.core.runtime.RuntimeStateStatus.ABSENT
            -> offline
        runtimeState.status == io.github.spectordev9.opencode.core.runtime.RuntimeStateStatus.INSTALLING
            -> RuntimeBanner(
                label = "RUNTIME INSTALLING",
                detail = runtimeState.reason ?: "Pinned runtime is being verified and installed",
                dotColor = Citrus,
                container = Color(0xFFE4EEE7),
            )
        runtimeState.status == io.github.spectordev9.opencode.core.runtime.RuntimeStateStatus.FAILED
            -> RuntimeBanner(
                label = "RUNTIME FAILED",
                detail = runtimeState.reason ?: "Runtime installation or verification failed",
                dotColor = Color(0xFFB3261E),
                container = Color(0xFFF6E7E4),
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
                            serverState.version?.takeIf { it.isNotBlank() }?.let { append(" · v$it") }
                        },
                        dotColor = Moss,
                        container = Color(0xFFE7F3EE),
                    )
                io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.STARTING,
                io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.BACKOFF,
                io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.CRASH_LOOP,
                ->
                    RuntimeBanner(
                        label = "SERVER RECOVERING",
                        detail = serverState.reason
                            ?: "Supervised server is starting or retrying on loopback",
                        dotColor = Citrus,
                        container = Color(0xFFE4EEE7),
                    )
                io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.DEGRADED ->
                    RuntimeBanner(
                        label = "SERVER DEGRADED",
                        detail = serverState.reason ?: "Supervised server is degraded",
                        dotColor = Citrus,
                        container = Color(0xFFE4EEE7),
                    )
                else ->
                    RuntimeBanner(
                        label = "SERVER STOPPED",
                        detail = "Runtime is ready; the supervised server is not running",
                        dotColor = Clay,
                        container = Color(0xFFE4EEE7),
                    )
            }
        }
    }
}