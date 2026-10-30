package io.github.spectordev9.opencode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.spectordev9.opencode.chat.ChatController
import io.github.spectordev9.opencode.chat.ChatUiState
import io.github.spectordev9.opencode.chat.TaskStatus
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticEvent
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticLevel
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticReport
import io.github.spectordev9.opencode.features.AdvancedTools
import io.github.spectordev9.opencode.features.BuildStatus
import io.github.spectordev9.opencode.features.CompatibilityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun OperationsScreen(
    kicker: String,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalOpencodePalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                kicker,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.2.sp,
                color = palette.Accent,
            )
            Text(
                title,
                fontSize = 26.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = palette.TextCol,
            )
            Text(subtitle, fontSize = 11.sp, color = palette.Muted2)
        }
        HorizontalDivider(color = palette.BorderCol)
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun OperationsStatusCard(
    label: String,
    value: String,
    tone: Color,
    detail: String? = null,
) {
    val palette = LocalOpencodePalette.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = palette.Panel2.copy(alpha = 0.72f),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.BorderCol),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(tone),
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 10.sp, color = palette.Muted2)
                Text(
                    value,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.TextCol,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                detail?.let {
                    Text(
                        it,
                        fontSize = 10.sp,
                        color = palette.Muted2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun OperationsMessage(message: String?, tone: Color) {
    if (message == null) return
    val palette = LocalOpencodePalette.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = tone.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, tone.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            color = tone,
            fontSize = 11.sp,
        )
    }
}

@Composable
internal fun BuildTestScreen(tools: AdvancedTools) {
    val palette = LocalOpencodePalette.current
    val context = LocalContext.current
    val build by tools.builds.state.collectAsState()
    var customCommand by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageTone by remember { mutableStateOf(palette.Accent) }
    val statusTone = when (build.status) {
        BuildStatus.RUNNING -> palette.WarningCol
        BuildStatus.SUCCEEDED -> palette.SuccessCol
        BuildStatus.FAILED -> palette.DangerCol
        BuildStatus.CANCELLED -> palette.WarningCol
        BuildStatus.IDLE -> palette.Muted2
    }

    OperationsScreen(
        kicker = "DEVELOPMENT",
        title = "Build & Test",
        subtitle = "Run project commands in the guest runtime, inspect output, and share artifacts.",
    ) {
        OperationsStatusCard(
            label = "Task",
            value = when (build.status) {
                BuildStatus.IDLE -> "Ready"
                BuildStatus.RUNNING -> "Running"
                BuildStatus.SUCCEEDED -> "Passed"
                BuildStatus.FAILED -> "Failed"
                BuildStatus.CANCELLED -> "Cancelled"
            },
            tone = statusTone,
            detail = build.command.takeIf { it.isNotBlank() },
        )
        OperationsMessage(message, messageTone)

        SectionTitle("Presets")
        tools.builds.presets.filter { it.id != "custom" }.forEach { preset ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.Panel2.copy(alpha = 0.62f),
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.BorderCol),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = !build.running,
                        role = Role.Button,
                    ) { tools.builds.start(preset.command, preset.label) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            preset.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (build.running) palette.Muted2 else palette.TextCol,
                        )
                        Text(
                            preset.command,
                            fontSize = 9.sp,
                            color = palette.Muted2,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "Run",
                        color = if (build.running) palette.Muted2 else palette.Accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        SectionTitle("Custom command")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = customCommand,
                onValueChange = { customCommand = it },
                label = { Text("Shell command") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { tools.builds.start(customCommand, "Custom command") },
                enabled = customCommand.isNotBlank() && !build.running,
            ) {
                Text("Run", color = palette.Accent)
            }
        }

        if (build.running) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Running…", color = palette.WarningCol, fontSize = 12.sp)
                TextButton(onClick = { tools.builds.cancel() }) {
                    Text("Cancel", color = palette.DangerCol)
                }
            }
        }
        build.error?.let { error -> Text(error, color = palette.DangerCol, fontSize = 12.sp) }

        if (build.output.isNotBlank()) {
            SectionTitle("Output")
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = palette.CodeBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.CodeBorder),
            ) {
                Text(
                    build.output,
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                    color = palette.CodeText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }

        if (!build.running && build.status != BuildStatus.IDLE) {
            Text(
                "Exit code: ${build.exitCode ?: "unknown"}",
                color = if (build.status == BuildStatus.SUCCEEDED) palette.SuccessCol else palette.DangerCol,
                fontSize = 11.sp,
            )
        }

        if (build.artifacts.isNotEmpty()) {
            SectionTitle("Artifacts")
            build.artifacts.forEach { artifact ->
                ArtifactRow(artifact) {
                    val shared = shareArtifact(context, tools.builds, artifact)
                    message = if (shared) "Sharing ${artifact.path}" else "Artifact is unavailable"
                    messageTone = if (shared) palette.Accent else palette.DangerCol
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { tools.builds.refreshArtifacts() }) {
                Text("Refresh artifacts", color = palette.Accent)
            }
            TextButton(onClick = { tools.builds.clear() }, enabled = !build.running) {
                Text("Clear output", color = palette.Muted)
            }
        }
    }
}

@Composable
internal fun IntegrationsScreen(tools: AdvancedTools, chat: ChatController?) {
    val palette = LocalOpencodePalette.current
    val extensions by tools.extensions.state.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var messageTone by remember { mutableStateOf(palette.Accent) }
    var mcpName by rememberSaveable { mutableStateOf("") }
    var mcpCommand by remember { mutableStateOf("") }
    var remoteMcpName by rememberSaveable { mutableStateOf("") }
    var mcpUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { tools.extensions.refresh() }

    OperationsScreen(
        kicker = "ENVIRONMENT",
        title = "Integrations",
        subtitle = "MCP servers, skills, commands, plugins, and the server configuration document.",
    ) {
        OperationsMessage(message, messageTone)
        OperationsStatusCard(
            label = "Extension service",
            value = when {
                extensions.loading -> "Loading"
                extensions.error != null -> "Needs attention"
                else -> if (extensions.loaded) "Ready" else "Not loaded"
            },
            tone = when {
                extensions.loading -> palette.WarningCol
                extensions.error != null -> palette.DangerCol
                else -> palette.Accent
            },
            detail = extensions.error,
        )

        SectionTitle("Skills")
        if (extensions.skills.isEmpty()) {
            Text("No skills reported by this server.", color = palette.Muted, fontSize = 12.sp)
        }
        extensions.skills.take(30).forEach { skill -> SkillRow(skill) }

        SectionTitle("Commands")
        if (extensions.commands.isEmpty()) {
            Text("No commands reported by this server.", color = palette.Muted, fontSize = 12.sp)
        }
        extensions.commands.take(40).forEach { command ->
            CommandRow(command) {
                scope.launch {
                    val ran = chat?.runCommand(command.name) == true
                    message = if (ran) "Running /${command.name}" else "Connect to a server to run commands"
                    messageTone = if (ran) palette.Accent else palette.WarningCol
                }
            }
        }

        SectionTitle("MCP servers")
        if (extensions.mcpServers.isEmpty()) {
            Text("No MCP servers configured.", color = palette.Muted, fontSize = 12.sp)
        }
        extensions.mcpServers.forEach { server ->
            McpRow(server) { connect ->
                scope.launch {
                    val result = if (connect) {
                        tools.extensions.connectMcp(server.name)
                    } else {
                        tools.extensions.disconnectMcp(server.name)
                    }
                    if (result is ApiResult.Failure) {
                        message = result.error.message
                        messageTone = palette.DangerCol
                    }
                }
            }
        }

        SectionTitle("Add local MCP")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = mcpName,
                onValueChange = { mcpName = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = mcpCommand,
                onValueChange = { mcpCommand = it },
                label = { Text("Command") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(
            onClick = {
                scope.launch {
                    val result = tools.extensions.addLocalMcp(mcpName, mcpCommand)
                    when (result) {
                        is ApiResult.Success -> {
                            mcpName = ""
                            mcpCommand = ""
                            message = "MCP server added"
                            messageTone = palette.Accent
                        }
                        is ApiResult.Failure -> {
                            message = result.error.message
                            messageTone = palette.DangerCol
                        }
                    }
                }
            },
            enabled = mcpName.isNotBlank() && mcpCommand.isNotBlank(),
        ) {
            Text("Add local MCP", color = palette.Accent)
        }

        SectionTitle("Add remote MCP")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = remoteMcpName,
                onValueChange = { remoteMcpName = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = mcpUrl,
                onValueChange = { mcpUrl = it },
                label = { Text("HTTPS URL") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(
            onClick = {
                scope.launch {
                    val result = tools.extensions.addRemoteMcp(remoteMcpName, mcpUrl)
                    when (result) {
                        is ApiResult.Success -> {
                            remoteMcpName = ""
                            mcpUrl = ""
                            message = "Remote MCP server added"
                            messageTone = palette.Accent
                        }
                        is ApiResult.Failure -> {
                            message = result.error.message
                            messageTone = palette.DangerCol
                        }
                    }
                }
            },
            enabled = remoteMcpName.isNotBlank() && mcpUrl.isNotBlank(),
        ) {
            Text("Add remote", color = palette.Accent)
        }

        SectionTitle("Server configuration")
        val config = extensions.config
        if (config == null) {
            Text("Configuration is unavailable while the server is disconnected.", color = palette.Muted, fontSize = 12.sp)
        } else {
            Text(
                "shell=${config.shell ?: "default"}  log=${config.logLevel ?: "default"}  " +
                    "plugins=${config.pluginCount}  mcp=${config.mcpCount}  commands=${config.commandCount}",
                color = palette.Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        extensions.plugins.forEach { plugin ->
            Text("Plugin: ${plugin.name}", color = palette.Muted, fontSize = 11.sp)
        }
        if (extensions.plugins.isNotEmpty()) {
            Text(
                "Server plugins are remote extensions; desktop-only plugins are not assumed to run on Android.",
                color = palette.Muted2,
                fontSize = 10.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { tools.extensions.refresh() }) {
                Text("Refresh", color = palette.Accent)
            }
        }
    }
}

@Composable
internal fun ProjectConfigurationScreen(tools: AdvancedTools) {
    val palette = LocalOpencodePalette.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf(tools.projects.list()) }
    var environmentNames by remember {
        mutableStateOf(tools.environment.names(tools.projects.activeDirectory()))
    }
    var projectName by rememberSaveable { mutableStateOf("") }
    var environmentName by rememberSaveable { mutableStateOf("") }
    var environmentValue by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageTone by remember { mutableStateOf(palette.Accent) }
    val active = projects.firstOrNull { it.active }

    fun refreshProjects() {
        projects = tools.projects.list()
        environmentNames = tools.environment.names(tools.projects.activeDirectory())
    }

    OperationsScreen(
        kicker = "ENVIRONMENT",
        title = "Project Configuration",
        subtitle = "Select the active workspace and manage encrypted project-scoped environment values.",
    ) {
        OperationsMessage(message, messageTone)
        OperationsStatusCard(
            label = "Active project",
            value = active?.name ?: "Unavailable",
            tone = if (active == null) palette.DangerCol else palette.Accent,
            detail = active?.directory,
        )

        SectionTitle("Workspace")
        projects.forEach { project ->
            ProjectRow(project) {
                when (val result = tools.selectProject(project.id)) {
                    is ApiResult.Success -> {
                        message = "Active project: ${result.value.name}"
                        messageTone = palette.Accent
                        refreshProjects()
                    }
                    is ApiResult.Failure -> {
                        message = result.error.message
                        messageTone = palette.DangerCol
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = projectName,
                onValueChange = { projectName = it },
                label = { Text("New folder") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    when (val result = tools.createProject(projectName)) {
                        is ApiResult.Success -> {
                            projectName = ""
                            message = "Created ${result.value.name}"
                            messageTone = palette.Accent
                            refreshProjects()
                        }
                        is ApiResult.Failure -> {
                            message = result.error.message
                            messageTone = palette.DangerCol
                        }
                    }
                },
                enabled = projectName.isNotBlank(),
            ) {
                Text("Create", color = palette.Accent)
            }
        }

        HorizontalDivider(color = palette.BorderCol)
        SectionTitle("Environment")
        Text(
            "Values are encrypted with Android Keystore, never shown, and never written to project files.",
            color = palette.Muted2,
            fontSize = 11.sp,
        )
        if (environmentNames.isEmpty()) {
            Text("No environment variables for this project.", color = palette.Muted, fontSize = 12.sp)
        }
        environmentNames.forEach { name ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name,
                    color = palette.TextCol,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            when (tools.environment.remove(tools.projects.activeDirectory(), name)) {
                                is ApiResult.Success -> {
                                    environmentNames = tools.environment.names(tools.projects.activeDirectory())
                                    message = "Removed $name"
                                    messageTone = palette.Accent
                                }
                                is ApiResult.Failure -> {
                                    message = "Could not remove $name"
                                    messageTone = palette.DangerCol
                                }
                            }
                        }
                    },
                ) {
                    Text("Remove", color = palette.DangerCol)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = environmentName,
                onValueChange = { environmentName = it },
                label = { Text("NAME") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = environmentValue,
                onValueChange = { environmentValue = it },
                label = { Text("Value") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(
            onClick = {
                scope.launch {
                    val result = tools.environment.set(
                        tools.projects.activeDirectory(),
                        environmentName,
                        environmentValue,
                    )
                    when (result) {
                        is ApiResult.Success -> {
                            environmentName = ""
                            environmentValue = ""
                            environmentNames = tools.environment.names(tools.projects.activeDirectory())
                            message = "Environment variable saved"
                            messageTone = palette.Accent
                        }
                        is ApiResult.Failure -> {
                            message = result.error.message
                            messageTone = palette.DangerCol
                        }
                    }
                }
            },
            enabled = environmentName.isNotBlank() && environmentValue.isNotBlank(),
        ) {
            Text("Save variable", color = palette.Accent)
        }
    }
}

@Composable
internal fun RuntimeToolsScreen(
    tools: AdvancedTools,
    runtimeLabel: String,
    serverLabel: String,
) {
    val palette = LocalOpencodePalette.current
    val build by tools.builds.state.collectAsState()
    var compatibility by remember { mutableStateOf<CompatibilityReport?>(null) }
    var checkKey by remember { mutableIntStateOf(0) }
    val active = remember(checkKey) { tools.projects.list().firstOrNull { it.active } }
    val environmentCount = remember(checkKey) {
        tools.environment.names(tools.projects.activeDirectory()).size
    }

    LaunchedEffect(checkKey) {
        compatibility = withContext(Dispatchers.Default) { tools.compatibility() }
    }

    OperationsScreen(
        kicker = "ENVIRONMENT",
        title = "Runtime & Tools",
        subtitle = "Device compatibility, supervised server state, and the local toolchain.",
    ) {
        CompatibilitySection(compatibility)
        OperationsStatusCard(
            label = "Runtime",
            value = runtimeLabel,
            tone = if (runtimeLabel.equals("ready", ignoreCase = true)) palette.Accent else palette.WarningCol,
        )
        OperationsStatusCard(
            label = "Server",
            value = serverLabel,
            tone = if (serverLabel.equals("connected", ignoreCase = true)) palette.Accent else palette.WarningCol,
        )
        OperationsStatusCard(
            label = "Workspace",
            value = active?.name ?: "Unavailable",
            tone = if (active == null) palette.DangerCol else palette.Accent,
            detail = active?.directory,
        )
        OperationsStatusCard(
            label = "Toolchain",
            value = "${tools.builds.presets.size} build presets · $environmentCount environment values",
            tone = palette.Muted,
            detail = "Artifacts tracked: ${build.artifacts.size}",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { checkKey++ }) {
                Text("Re-check", color = palette.Accent)
            }
            TextButton(onClick = { tools.builds.refreshArtifacts() }) {
                Text("Refresh artifacts", color = palette.Muted)
            }
        }
        Text(
            "Compatibility reflects this Android device. Build presets that require an Android SDK " +
                "remain unavailable when the guest runtime cannot provide one.",
            color = palette.Muted2,
            fontSize = 10.sp,
        )
    }
}

@Composable
internal fun DiagnosticsScreen(
    tools: AdvancedTools,
    runtimeLabel: String,
    serverLabel: String,
) {
    val palette = LocalOpencodePalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageTone by remember { mutableStateOf(palette.Accent) }
    var loadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(loadKey) {
        loading = true
        report = when (val result = tools.diagnostics.report()) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> {
                message = result.error.message
                messageTone = palette.DangerCol
                null
            }
        }
        loading = false
    }

    OperationsScreen(
        kicker = "PREFERENCES & SUPPORT",
        title = "Diagnostics",
        subtitle = "Inspect redacted runtime events and export a support report.",
    ) {
        OperationsMessage(message, messageTone)
        OperationsStatusCard(
            label = "Runtime",
            value = runtimeLabel,
            tone = palette.Muted,
        )
        OperationsStatusCard(
            label = "Server",
            value = serverLabel,
            tone = if (serverLabel.equals("connected", ignoreCase = true)) palette.Accent else palette.WarningCol,
        )
        OperationsStatusCard(
            label = "Notifications",
            value = if (tools.notifier.hasPermission()) "Allowed" else "Not granted",
            tone = if (tools.notifier.hasPermission()) palette.Accent else palette.WarningCol,
        )
        if (loading) {
            CircularProgressIndicator(color = palette.Accent, modifier = Modifier.size(22.dp))
        }
        report?.let { active ->
            Text(
                "App ${active.appVersion} · runtime ${active.runtimeVersion ?: "unknown"} · " +
                    "server ${active.openCodeVersion ?: "unknown"}",
                color = palette.Muted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "${active.events.size} event(s) in the in-memory buffer" +
                    if (active.truncated) " · older events were dropped" else "",
                color = palette.Muted2,
                fontSize = 10.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    scope.launch {
                        tools.diagnostics.exportJson().fold(
                            onSuccess = { text ->
                                shareText(context, text)
                                message = "Diagnostics ready to share"
                                messageTone = palette.Accent
                            },
                            onFailure = { failure ->
                                message = "Diagnostics export failed: ${failure.message}"
                                messageTone = palette.DangerCol
                            },
                        )
                    }
                },
            ) {
                Text("Export redacted report", color = palette.Accent)
            }
            TextButton(onClick = { loadKey++ }) {
                Text("Refresh", color = palette.Muted)
            }
        }

        SectionTitle("Recent events")
        val events = report?.events?.takeLast(30)?.asReversed().orEmpty()
        if (events.isEmpty()) {
            Text("No diagnostics events recorded.", color = palette.Muted, fontSize = 12.sp)
        }
        events.forEach { event -> DiagnosticEventRow(event) }
    }
}

@Composable
private fun DiagnosticEventRow(event: DiagnosticEvent) {
    val palette = LocalOpencodePalette.current
    val tone = when (event.level) {
        DiagnosticLevel.ERROR -> palette.DangerCol
        DiagnosticLevel.WARN -> palette.WarningCol
        DiagnosticLevel.INFO -> palette.Accent
        DiagnosticLevel.DEBUG -> palette.Muted2
    }
    val time = remember(event.epochMillis) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(event.epochMillis))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(palette.Panel2.copy(alpha = 0.45f))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            event.level.name.take(4),
            color = tone,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(34.dp).padding(top = 1.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(event.message, color = palette.TextCol, fontSize = 11.sp)
            Text(
                "$time · ${event.category}",
                color = palette.Muted2,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
internal fun TasksActivityScreen(
    state: ChatUiState?,
    tools: AdvancedTools,
    connectionLabel: String,
) {
    val palette = LocalOpencodePalette.current
    val build by tools.builds.state.collectAsState()
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var loadKey by remember { mutableIntStateOf(0) }
    val status = state?.taskStatus ?: TaskStatus.IDLE
    val statusTone = when (status) {
        TaskStatus.RUNNING -> palette.Accent
        TaskStatus.WAITING_APPROVAL -> palette.WarningCol
        TaskStatus.COMPLETED -> palette.SuccessCol
        TaskStatus.FAILED -> palette.DangerCol
        TaskStatus.INTERRUPTED -> palette.WarningCol
        TaskStatus.IDLE -> palette.Muted2
    }
    val statusLabel = when (status) {
        TaskStatus.RUNNING -> "Running"
        TaskStatus.WAITING_APPROVAL -> "Waiting for approval"
        TaskStatus.COMPLETED -> "Completed"
        TaskStatus.FAILED -> "Failed"
        TaskStatus.INTERRUPTED -> "Interrupted"
        TaskStatus.IDLE -> "Idle"
    }
    val activeSession = state?.let { current ->
        current.sessions.firstOrNull { it.id == current.activeSessionId }
    }
    val activeSessionTitle = activeSession?.let { session ->
        session.title.ifBlank { session.slug }
    } ?: "No active session"

    LaunchedEffect(loadKey) {
        report = when (val result = tools.diagnostics.report()) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> null
        }
    }

    OperationsScreen(
        kicker = "WORK MANAGEMENT",
        title = "Tasks & Activity",
        subtitle = "Live chat task state, pending approvals, builds, and recorded task events.",
    ) {
        OperationsStatusCard(
            label = "Current task",
            value = statusLabel,
            tone = statusTone,
        )
        OperationsStatusCard(
            label = "Session",
            value = activeSessionTitle,
            tone = if (activeSession == null) palette.Muted2 else palette.Accent,
            detail = activeSession?.id,
        )
        OperationsStatusCard(
            label = "Connection",
            value = connectionLabel,
            tone = if (connectionLabel.equals("connected", ignoreCase = true)) palette.Accent else palette.WarningCol,
        )
        OperationsStatusCard(
            label = "Pending approvals",
            value = (state?.permissions?.size ?: 0).toString(),
            tone = if ((state?.permissions?.size ?: 0) > 0) palette.WarningCol else palette.Muted,
        )
        OperationsStatusCard(
            label = "Build task",
            value = when (build.status) {
                BuildStatus.IDLE -> "Idle"
                BuildStatus.RUNNING -> "Running"
                BuildStatus.SUCCEEDED -> "Completed"
                BuildStatus.FAILED -> "Failed"
                BuildStatus.CANCELLED -> "Interrupted"
            },
            tone = when (build.status) {
                BuildStatus.RUNNING -> palette.Accent
                BuildStatus.SUCCEEDED -> palette.SuccessCol
                BuildStatus.FAILED -> palette.DangerCol
                BuildStatus.CANCELLED -> palette.WarningCol
                BuildStatus.IDLE -> palette.Muted2
            },
            detail = build.command.takeIf { it.isNotBlank() },
        )
        Text(
            "Notifications: ${if (tools.notifier.hasPermission()) "allowed" else "not granted"}",
            color = palette.Muted2,
            fontSize = 10.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { loadKey++ }) {
                Text("Refresh activity", color = palette.Accent)
            }
        }

        SectionTitle("Recorded task events")
        val eventRows = report?.events
            ?.filter { it.category == "task" }
            ?.takeLast(30)
            ?.asReversed()
            .orEmpty()
        if (eventRows.isEmpty()) {
            Text(
                "No task events have been recorded since launch. Task history is in-memory and " +
                    "retains completed, failed, permission, and interrupt events from the live session.",
                color = palette.Muted,
                fontSize = 11.sp,
            )
        }
        eventRows.forEach { event ->
            val kind = event.attributes["kind"]
            Text(
                buildString {
                    append(kind?.let { "$it · " } ?: "")
                    append(event.message)
                },
                color = when (event.level) {
                    DiagnosticLevel.ERROR -> palette.DangerCol
                    DiagnosticLevel.WARN -> palette.WarningCol
                    else -> palette.TextCol
                },
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                event.attributes["session"] ?: event.category,
                color = palette.Muted2,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
