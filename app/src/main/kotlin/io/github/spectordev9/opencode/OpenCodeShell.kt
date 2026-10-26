package io.github.spectordev9.opencode

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.terminal.PtyEvent
import io.github.spectordev9.opencode.core.terminal.PtyService
import io.github.spectordev9.opencode.core.terminal.PtySession
import io.github.spectordev9.opencode.core.terminal.PtySize
import io.github.spectordev9.opencode.core.terminal.PtySpec
import kotlinx.coroutines.flow.collect

private val Ink = Color(0xFF202A27)
private val Paper = Color(0xFFF3F1E9)
private val Moss = Color(0xFF23745E)
private val Citrus = Color(0xFFE7B64A)
private val Clay = Color(0xFFB95F43)
private val TerminalBlack = Color(0xFF111916)
private val TerminalGreen = Color(0xFFB8E6C5)

private enum class AppTab(val label: String) {
    Chat("Chat"),
    Terminal("Terminal"),
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun OpenCodeShell(
    ptyService: PtyService? = null,
    onOpenLicenses: () -> Unit,
) {
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
                            icon = { Text(if (tab == AppTab.Chat) "01" else "02", fontSize = 11.sp) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { padding ->
            when (selectedTab) {
                AppTab.Chat -> ChatHome(
                    modifier = Modifier.padding(padding),
                    onOpenTerminal = { selectedTab = AppTab.Terminal },
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
    onOpenTerminal: () -> Unit,
) {
    var selectedMode by remember { mutableStateOf("Build") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Workspace", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("No project selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = {}, enabled = false) { Text("Choose folder") }
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFFE4EEE7),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("RUNTIME OFFLINE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Sessions and tools are unavailable", fontSize = 13.sp)
                }
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .sizeIn(minWidth = 8.dp, minHeight = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Clay),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFE6E4DA))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("Build", "Plan").forEach { mode ->
                val selected = selectedMode == mode
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { selectedMode = mode },
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) Color(0xFFFFFEFA) else Color.Transparent,
                    shadowElevation = if (selected) 1.dp else 0.dp,
                ) {
                    Text(
                        mode,
                        modifier = Modifier.padding(vertical = 10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = onOpenTerminal, modifier = Modifier.weight(1f)) {
                Text("Terminal")
            }
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                Text("New session")
            }
        }

        HorizontalDivider(color = Color(0xFFD7D6CC))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Recent sessions", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("0", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Sessions appear here when a project and runtime are connected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(color = Color(0xFFD7D6CC))
        Text("Workspace tools", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Files", "Changes", "Todos").forEach { tool ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFFFFEFA),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(tool, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Offline", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

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