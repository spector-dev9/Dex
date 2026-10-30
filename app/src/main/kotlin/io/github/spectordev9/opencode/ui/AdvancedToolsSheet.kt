package io.github.spectordev9.opencode.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.spectordev9.opencode.chat.ChatController
import io.github.spectordev9.opencode.features.AdvancedTools
import io.github.spectordev9.opencode.features.BuildArtifact
import io.github.spectordev9.opencode.features.BuildController
import io.github.spectordev9.opencode.features.CompatibilityReport
import io.github.spectordev9.opencode.features.McpServerInfo
import io.github.spectordev9.opencode.features.ProjectWorkspace
import io.github.spectordev9.opencode.features.ServerCommandInfo
import io.github.spectordev9.opencode.features.ServerSkill
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Advanced project and runtime tools (F10, F51-F55, F68-F70, F74-F77).
 * Business logic lives in feature controllers; this sheet only renders state
 * and forwards explicit user actions.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AdvancedToolsSheet(
    tools: AdvancedTools,
    chat: ChatController?,
    onDismiss: () -> Unit,
) {
    val palette = LocalOpencodePalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val extensions by tools.extensions.state.collectAsState()
    val build by tools.builds.state.collectAsState()

    var projects by remember { mutableStateOf(tools.projects.list()) }
    var projectName by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<String?>(null) }
    var compatibility by remember { mutableStateOf<CompatibilityReport?>(null) }

    var mcpName by remember { mutableStateOf("") }
    var mcpCommand by remember { mutableStateOf("") }
    var mcpUrl by remember { mutableStateOf("") }
    var environmentName by remember { mutableStateOf("") }
    var environmentValue by remember { mutableStateOf("") }
    var environmentNames by remember {
        mutableStateOf(tools.environment.names(tools.projects.activeDirectory()))
    }
    var customCommand by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        compatibility = withContext(Dispatchers.Default) { tools.compatibility() }
        tools.extensions.refresh()
    }

    fun refreshProjects() {
        projects = tools.projects.list()
        environmentNames = tools.environment.names(tools.projects.activeDirectory())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.Panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "POWER TOOLS",
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.2.sp,
                color = palette.Accent,
            )
            Text(
                "Advanced tools",
                fontSize = 24.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
                color = palette.TextCol,
            )
            Text(
                "Projects, MCP servers, skills, builds, and environment.",
                fontSize = 12.sp,
                color = palette.Muted2,
            )
            messages?.let { Text(it, color = palette.Accent, fontSize = 12.sp) }

            CompatibilitySection(compatibility)
            HorizontalDivider(color = palette.BorderCol)

            SectionTitle("Projects")
            projects.forEach { project ->
                ProjectRow(project) {
                    when (val result = tools.selectProject(project.id)) {
                        is io.github.spectordev9.opencode.core.common.ApiResult.Success -> {
                            messages = "Active project: ${result.value.name}"
                            refreshProjects()
                            tools.builds.clear()
                        }
                        is io.github.spectordev9.opencode.core.common.ApiResult.Failure ->
                            messages = result.error.message
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
                            is io.github.spectordev9.opencode.core.common.ApiResult.Success -> {
                                projectName = ""
                                messages = "Created ${result.value.name}"
                                refreshProjects()
                            }
                            is io.github.spectordev9.opencode.core.common.ApiResult.Failure ->
                                messages = result.error.message
                        }
                    },
                    enabled = projectName.isNotBlank(),
                ) {
                    Text("Create", color = palette.Accent)
                }
            }
            HorizontalDivider(color = palette.BorderCol)

            SectionTitle("Skills and commands")
            if (extensions.loading) {
                CircularProgressIndicator(modifier = Modifier.sizeIn(maxWidth = 22.dp), color = palette.Accent)
            }
            if (extensions.skills.isEmpty() && extensions.commands.isEmpty()) {
                Text(
                    "No skills or commands reported by this server.",
                    color = palette.Muted,
                    fontSize = 12.sp,
                )
            }
            extensions.skills.take(20).forEach { skill -> SkillRow(skill) }
            extensions.commands.take(30).forEach { command ->
                CommandRow(command) {
                    scope.launch {
                        messages = if (chat?.runCommand(command.name) == true) {
                            "Running /${command.name}"
                        } else {
                            "Could not run /${command.name}"
                        }
                    }
                }
            }
            TextButton(onClick = { tools.extensions.refresh() }) {
                Text("Refresh extensions", color = palette.Accent)
            }
            HorizontalDivider(color = palette.BorderCol)

            SectionTitle("MCP servers")
            if (extensions.mcpServers.isEmpty()) {
                Text("No MCP servers configured.", color = palette.Muted, fontSize = 12.sp)
            }
            extensions.mcpServers.forEach { server -> McpRow(server) { connect ->
                scope.launch {
                    val result = if (connect) {
                        tools.extensions.connectMcp(server.name)
                    } else {
                        tools.extensions.disconnectMcp(server.name)
                    }
                    if (result is io.github.spectordev9.opencode.core.common.ApiResult.Failure) {
                        messages = result.error.message
                    }
                }
            } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
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
                        if (result is io.github.spectordev9.opencode.core.common.ApiResult.Success) {
                            mcpName = ""
                            mcpCommand = ""
                            messages = "MCP server added"
                        } else if (result is io.github.spectordev9.opencode.core.common.ApiResult.Failure) {
                            messages = result.error.message
                        }
                    }
                },
                enabled = mcpName.isNotBlank() && mcpCommand.isNotBlank(),
            ) {
                Text("Add local MCP", color = palette.Accent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = mcpUrl,
                    onValueChange = { mcpUrl = it },
                    label = { Text("Remote HTTPS URL") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            val result = tools.extensions.addRemoteMcp(mcpName, mcpUrl)
                            if (result is io.github.spectordev9.opencode.core.common.ApiResult.Success) {
                                mcpUrl = ""
                                messages = "Remote MCP server added"
                            } else if (result is io.github.spectordev9.opencode.core.common.ApiResult.Failure) {
                                messages = result.error.message
                            }
                        }
                    },
                    enabled = mcpName.isNotBlank() && mcpUrl.isNotBlank(),
                ) {
                    Text("Add remote", color = palette.Accent)
                }
            }
            HorizontalDivider(color = palette.BorderCol)

            SectionTitle("Project environment")
            Text(
                "Values are encrypted with Android Keystore, never shown, and never written to project files.",
                color = palette.Muted2,
                fontSize = 11.sp,
            )
            environmentNames.forEach { name ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, color = palette.TextCol, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    TextButton(
                        onClick = {
                            scope.launch {
                                tools.environment.remove(tools.projects.activeDirectory(), name)
                                environmentNames = tools.environment.names(tools.projects.activeDirectory())
                            }
                        },
                    ) {
                        Text("Remove", color = palette.DangerCol)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
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
                        if (result is io.github.spectordev9.opencode.core.common.ApiResult.Success) {
                            environmentName = ""
                            environmentValue = ""
                            environmentNames = tools.environment.names(tools.projects.activeDirectory())
                            messages = "Environment variable saved"
                        } else if (result is io.github.spectordev9.opencode.core.common.ApiResult.Failure) {
                            messages = result.error.message
                        }
                    }
                },
                enabled = environmentName.isNotBlank() && environmentValue.isNotBlank(),
            ) {
                Text("Save variable", color = palette.Accent)
            }
            HorizontalDivider(color = palette.BorderCol)

            SectionTitle("Build and test")
            tools.builds.presets.filter { it.id != "custom" }.forEach { preset ->
                TextButton(
                    onClick = { tools.builds.start(preset.command, preset.label) },
                    enabled = !build.running,
                ) {
                    Text(preset.label, color = palette.Accent)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = customCommand,
                    onValueChange = { customCommand = it },
                    label = { Text("Custom shell command") },
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
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = palette.Panel,
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.BorderCol),
                ) {
                    Text(
                        build.output,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                        color = palette.CodeText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
            }
            if (!build.running && build.status != io.github.spectordev9.opencode.features.BuildStatus.IDLE) {
                Text(
                    "Exit code: ${build.exitCode ?: "unknown"}",
                    color = if (build.status == io.github.spectordev9.opencode.features.BuildStatus.SUCCEEDED) {
                        palette.SuccessCol
                    } else {
                        palette.DangerCol
                    },
                    fontSize = 11.sp,
                )
            }
            if (build.artifacts.isNotEmpty()) {
                Text("Artifacts", color = palette.TextCol, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                build.artifacts.forEach { artifact ->
                    ArtifactRow(artifact) {
                        val shared = shareArtifact(context, tools.builds, artifact)
                        messages = if (shared) "Sharing ${artifact.path}" else "Artifact is unavailable"
                    }
                }
            }
            HorizontalDivider(color = palette.BorderCol)

            SectionTitle("Server extensions")
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
            extensions.error?.let { error -> Text(error, color = palette.DangerCol, fontSize = 11.sp) }

            TextButton(
                onClick = {
                    scope.launch {
                        val report = tools.diagnostics.exportJson()
                        messages = report.fold(
                            onSuccess = { text ->
                                shareText(context, text)
                                "Diagnostics ready to share"
                            },
                            onFailure = { failure ->
                                "Diagnostics export failed: ${failure.message}"
                            },
                        )
                    }
                },
            ) {
                Text("Export redacted diagnostics", color = palette.Accent)
            }
            Text(
                "Notifications: ${if (tools.notifier.hasPermission()) "allowed" else "not granted"}",
                color = palette.Muted2,
                fontSize = 11.sp,
            )
            TextButton(onClick = onDismiss) {
                Text("Close", color = palette.Muted)
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    val palette = LocalOpencodePalette.current
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = palette.TextCol)
}

@Composable
internal fun CompatibilitySection(report: CompatibilityReport?) {
    val palette = LocalOpencodePalette.current
    if (report == null) {
        Text("Checking device compatibility…", color = palette.Muted, fontSize = 11.sp)
        return
    }
    Text(
        "Runtime: ${if (report.supported) "ready" else "not ready"}  |  " +
            "Android SDK ${report.sdkInt}  |  ${formatArtifactBytes(report.freeStorageBytes)} free",
        color = if (report.supported) palette.SuccessCol else palette.WarningCol,
        fontSize = 11.sp,
    )
    report.reasons.forEach { reason -> Text("• $reason", color = palette.DangerCol, fontSize = 10.sp) }
    report.warnings.forEach { warning -> Text("• $warning", color = palette.WarningCol, fontSize = 10.sp) }
}

@Composable
internal fun ProjectRow(project: ProjectWorkspace, onSelect: () -> Unit) {
    val palette = LocalOpencodePalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            project.name,
            color = if (project.active) palette.Accent else palette.TextCol,
            fontSize = 12.sp,
        )
        if (!project.active) {
            TextButton(onClick = onSelect) { Text("Use", color = palette.Accent, fontSize = 11.sp) }
        }
    }
}

@Composable
internal fun SkillRow(skill: ServerSkill) {
    val palette = LocalOpencodePalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("/${skill.name}", color = palette.TextCol, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        skill.description?.let { Text(it, color = palette.Muted, fontSize = 10.sp) }
        skill.preview?.let { Text(it, color = palette.Muted2, fontSize = 10.sp, maxLines = 2) }
    }
}

@Composable
internal fun CommandRow(command: ServerCommandInfo, onRun: () -> Unit) {
    val palette = LocalOpencodePalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "/${command.name}",
                color = palette.TextCol,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            command.description?.let { Text(it, color = palette.Muted, fontSize = 10.sp) }
        }
        TextButton(onClick = onRun) { Text("Run", color = palette.Accent, fontSize = 11.sp) }
    }
}

@Composable
internal fun McpRow(server: McpServerInfo, onToggle: (Boolean) -> Unit) {
    val palette = LocalOpencodePalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(server.name, color = palette.TextCol, fontSize = 12.sp)
            Text(
                server.status + (server.detail?.let { ": $it" } ?: ""),
                color = if (server.needsAttention) palette.DangerCol else palette.Muted2,
                fontSize = 10.sp,
            )
        }
        TextButton(onClick = { onToggle(!server.connected) }) {
            Text(
                if (server.connected) "Disconnect" else "Connect",
                color = if (server.connected) palette.DangerCol else palette.Accent,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
internal fun ArtifactRow(artifact: BuildArtifact, onShare: () -> Unit) {
    val palette = LocalOpencodePalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(artifact.path, color = palette.TextCol, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(formatArtifactBytes(artifact.sizeBytes), color = palette.Muted2, fontSize = 10.sp)
        }
        TextButton(onClick = onShare) { Text("Share", color = palette.Accent, fontSize = 11.sp) }
    }
}

internal fun shareArtifact(context: Context, builds: BuildController, artifact: BuildArtifact): Boolean {
    val file = builds.resolveArtifact(artifact.path) ?: return false
    return runCatching {
        val shareDirectory = File(context.cacheDir, "shared").apply { mkdirs() }
        val target = File(shareDirectory, file.name)
        file.copyTo(target, overwrite = true)
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target,
        )
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(target.extension.lowercase())
            ?: "application/octet-stream"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share artifact"))
    }.isSuccess
}

internal fun formatArtifactBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GiB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MiB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KiB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
