package io.github.spectordev9.opencode.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.spectordev9.opencode.R
import io.github.spectordev9.opencode.features.ProjectWorkspace

internal enum class DrawerSection(val label: String) {
    DEVELOPMENT("DEVELOPMENT"),
    WORK_MANAGEMENT("WORK MANAGEMENT"),
    ENVIRONMENT("ENVIRONMENT"),
    PREFERENCES("PREFERENCES & SUPPORT"),
}

internal enum class ShellDestination(
    val label: String,
    val detail: String,
    val glyph: String,
    val section: DrawerSection,
    val enabled: Boolean = true,
) {
    CHAT("Chat", "Agent conversation", "✦", DrawerSection.DEVELOPMENT),
    FILES("File Explorer", "Browse and preview files", "▤", DrawerSection.DEVELOPMENT),
    GIT("Source Control", "Changes, branches, commits", "⑂", DrawerSection.DEVELOPMENT),
    TERMINAL("Terminal", "Interactive shell", "❯", DrawerSection.DEVELOPMENT),
    PREVIEW(
        "Preview",
        "No preview backend is available in this build",
        "◫",
        DrawerSection.DEVELOPMENT,
        enabled = false,
    ),
    BUILD_TEST("Build & Test", "Commands, output, artifacts", "▷", DrawerSection.DEVELOPMENT),
    SESSIONS("Sessions", "Conversation history", "≡", DrawerSection.WORK_MANAGEMENT),
    TASKS("Tasks & Activity", "Runs, approvals, failures", "◷", DrawerSection.WORK_MANAGEMENT),
    RUNTIME("Runtime & Tools", "Device and server health", "⌁", DrawerSection.ENVIRONMENT),
    INTEGRATIONS("Integrations", "MCP, skills, commands", "⊞", DrawerSection.ENVIRONMENT),
    PROJECT_CONFIG("Project Configuration", "Workspace environment", "⚑", DrawerSection.ENVIRONMENT),
    SETTINGS("Settings", "Providers, profiles, data", "⚙", DrawerSection.PREFERENCES),
    APPEARANCE("Appearance", "Theme and typography", "◐", DrawerSection.PREFERENCES),
    DIAGNOSTICS("Diagnostics", "Redacted reports", "◎", DrawerSection.PREFERENCES),
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun OpenCodeDrawerSheet(
    selected: ShellDestination?,
    online: Boolean,
    statusLabel: String,
    projects: List<ProjectWorkspace>,
    selectedProject: ProjectWorkspace?,
    sessionCount: Int,
    buildRunning: Boolean,
    advancedAvailable: Boolean,
    sessionsAvailable: Boolean,
    onSelect: (ShellDestination) -> Unit,
    onSelectProject: (ProjectWorkspace) -> String?,
    onNewSession: () -> Unit,
    onRefresh: () -> Unit,
    onOpenLicenses: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalOpencodePalette.current
    var environmentExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selected) {
        if (selected?.section == DrawerSection.ENVIRONMENT) environmentExpanded = true
    }

    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 336.dp),
        drawerShape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp),
        drawerContainerColor = palette.Panel,
        drawerContentColor = palette.TextCol,
        drawerTonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxHeight()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                item {
                    DrawerHeader(
                        online = online,
                        statusLabel = statusLabel,
                        onRefresh = onRefresh,
                        onClose = onClose,
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    ProjectWorkspaceCard(
                        projects = projects,
                        selectedProject = selectedProject,
                        advancedAvailable = advancedAvailable,
                        newSessionEnabled = sessionsAvailable,
                        onSelectProject = onSelectProject,
                        onNewSession = onNewSession,
                    )
                }
                item { Spacer(Modifier.height(10.dp)) }

                item { DrawerSectionLabel(DrawerSection.DEVELOPMENT.label) }
                items(
                    items = ShellDestination.entries.filter { it.section == DrawerSection.DEVELOPMENT },
                    key = { it.name },
                ) { destination ->
                    DrawerDestinationItem(
                        destination = destination,
                        selected = selected == destination,
                        badge = when {
                            destination == ShellDestination.BUILD_TEST && buildRunning -> "RUNNING"
                            !destination.enabled -> "UNAVAILABLE"
                            else -> null
                        },
                        onClick = { onSelect(destination) },
                    )
                }

                item { Spacer(Modifier.height(8.dp)) }
                item { DrawerSectionLabel(DrawerSection.WORK_MANAGEMENT.label) }
                items(
                    items = ShellDestination.entries.filter { it.section == DrawerSection.WORK_MANAGEMENT },
                    key = { it.name },
                ) { destination ->
                    DrawerDestinationItem(
                        destination = destination,
                        selected = selected == destination,
                        badge = when {
                            destination == ShellDestination.SESSIONS && !sessionsAvailable ->
                                "OFFLINE"
                            destination == ShellDestination.SESSIONS && sessionCount > 0 ->
                                sessionCount.toString()
                            else -> null
                        },
                        enabled = destination != ShellDestination.SESSIONS || sessionsAvailable,
                        onClick = { onSelect(destination) },
                    )
                }

                item { Spacer(Modifier.height(8.dp)) }
                item {
                    DrawerSectionLabel(
                        label = DrawerSection.ENVIRONMENT.label,
                        expanded = environmentExpanded,
                        onClick = { environmentExpanded = !environmentExpanded },
                    )
                }
                if (environmentExpanded) {
                    items(
                        items = ShellDestination.entries.filter { it.section == DrawerSection.ENVIRONMENT },
                        key = { it.name },
                    ) { destination ->
                        DrawerDestinationItem(
                            destination = destination,
                            selected = selected == destination,
                            enabled = advancedAvailable,
                            onClick = { onSelect(destination) },
                        )
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
                item { DrawerSectionLabel(DrawerSection.PREFERENCES.label) }
                items(
                    items = ShellDestination.entries.filter { it.section == DrawerSection.PREFERENCES },
                    key = { it.name },
                ) { destination ->
                    DrawerDestinationItem(
                        destination = destination,
                        selected = selected == destination,
                        enabled = when (destination) {
                            ShellDestination.SETTINGS,
                            ShellDestination.APPEARANCE,
                            -> true
                            else -> advancedAvailable
                        },
                        onClick = { onSelect(destination) },
                    )
                }
            }

            DrawerFooter(onOpenLicenses = onOpenLicenses)
        }
    }
}

@Composable
private fun DrawerHeader(
    online: Boolean,
    statusLabel: String,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalOpencodePalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        palette.AccentSoft,
                        palette.Panel2.copy(alpha = 0.35f),
                        Color.Transparent,
                    ),
                ),
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = palette.AccentSoft,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    palette.AccentStrong.copy(alpha = 0.34f),
                ),
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.logo_mark),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "OPENCODE",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = palette.TextCol,
                )
                Text(
                    "MOBILE STUDIO",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.7.sp,
                    color = palette.Muted2,
                )
            }
            DrawerIconButton(
                glyph = "↻",
                description = "Refresh connection status",
                onClick = onRefresh,
            )
            Spacer(Modifier.width(6.dp))
            DrawerIconButton(
                glyph = "✕",
                description = "Close navigation",
                onClick = onClose,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tone = if (online) palette.Accent else palette.WarningCol
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(tone),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                statusLabel.uppercase(),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp,
                color = tone,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (online) "LOCAL SERVER" else "NO CONNECTION",
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.7.sp,
                color = palette.Muted2,
            )
        }
    }
}

@Composable
private fun DrawerIconButton(
    glyph: String,
    description: String,
    onClick: () -> Unit,
) {
    val palette = LocalOpencodePalette.current
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = palette.Panel2.copy(alpha = 0.9f),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.BorderCol),
        modifier = Modifier
            .size(38.dp)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(glyph, color = palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ProjectWorkspaceCard(
    projects: List<ProjectWorkspace>,
    selectedProject: ProjectWorkspace?,
    advancedAvailable: Boolean,
    newSessionEnabled: Boolean,
    onSelectProject: (ProjectWorkspace) -> String?,
    onNewSession: () -> Unit,
) {
    val palette = LocalOpencodePalette.current
    var menuExpanded by remember { mutableStateOf(false) }
    var projectError by remember { mutableStateOf<String?>(null) }
    val project = selectedProject ?: projects.firstOrNull()
    val canSwitch = advancedAvailable && projects.size > 1

    Box {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = palette.Panel2.copy(alpha = 0.72f),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.BorderCol),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "PROJECT WORKSPACE",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp,
                    color = palette.Accent,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = canSwitch, role = Role.Button) { menuExpanded = true }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            project?.name ?: "Workspace unavailable",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (project == null) palette.Muted else palette.TextCol,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            project?.directory ?: "Advanced tools are unavailable",
                            fontSize = 9.sp,
                            color = palette.Muted2,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (canSwitch) {
                        Text("⌄", color = palette.Muted, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    DrawerProjectAction(
                        label = "Switch",
                        enabled = canSwitch,
                        accent = false,
                        onClick = { menuExpanded = true },
                    )
                    DrawerProjectAction(
                        label = "＋  New session",
                        enabled = newSessionEnabled,
                        accent = true,
                        onClick = onNewSession,
                    )
                }
                projectError?.let { error ->
                    Spacer(Modifier.height(7.dp))
                    Text(error, color = palette.DangerCol, fontSize = 10.sp)
                }
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            projects.forEach { candidate ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                candidate.name,
                                color = if (candidate.active) palette.Accent else palette.TextCol,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                candidate.directory,
                                color = palette.Muted2,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    onClick = {
                        menuExpanded = false
                        if (!candidate.active) projectError = onSelectProject(candidate)
                    },
                )
            }
        }
    }
}

@Composable
private fun RowScope.DrawerProjectAction(
    label: String,
    enabled: Boolean,
    accent: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalOpencodePalette.current
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = when {
            !enabled -> palette.Panel3.copy(alpha = 0.5f)
            accent -> palette.AccentSoft
            else -> palette.Panel3
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
                !enabled -> palette.BorderCol.copy(alpha = 0.5f)
                accent -> palette.AccentStrong.copy(alpha = 0.32f)
                else -> palette.BorderCol
            },
        ),
        modifier = Modifier
            .weight(if (accent) 1.25f else 1f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                !enabled -> palette.Muted2.copy(alpha = 0.5f)
                accent -> palette.Accent
                else -> palette.Muted
            },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun DrawerSectionLabel(
    label: String,
    expanded: Boolean? = null,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalOpencodePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.7.sp,
            color = palette.Muted2,
        )
        if (expanded != null) {
            Text(
                if (expanded) "−" else "+",
                color = palette.Muted2,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DrawerDestinationItem(
    destination: ShellDestination,
    selected: Boolean,
    badge: String? = null,
    enabled: Boolean = destination.enabled,
    onClick: () -> Unit,
) {
    val palette = LocalOpencodePalette.current
    val foreground = when {
        !enabled -> palette.Muted2.copy(alpha = 0.5f)
        selected -> palette.Accent
        else -> palette.TextCol
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) palette.AccentSoft else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) palette.Accent else Color.Transparent),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (selected) palette.AccentSoft else palette.Panel2.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                destination.glyph,
                color = when {
                    !enabled -> palette.Muted2.copy(alpha = 0.45f)
                    selected -> palette.Accent
                    else -> palette.Muted
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                destination.label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                destination.detail,
                fontSize = 9.sp,
                color = if (!enabled) palette.Muted2.copy(alpha = 0.55f) else palette.Muted2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) {
            Surface(
                shape = RoundedCornerShape(50),
                color = when (badge) {
                    "RUNNING" -> palette.WarningCol.copy(alpha = 0.14f)
                    "UNAVAILABLE", "OFFLINE" -> palette.Panel3
                    else -> palette.AccentSoft
                },
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when (badge) {
                        "RUNNING" -> palette.WarningCol.copy(alpha = 0.35f)
                        "UNAVAILABLE", "OFFLINE" -> palette.BorderCol
                        else -> palette.AccentStrong.copy(alpha = 0.3f)
                    },
                ),
            ) {
                Text(
                    badge,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = when (badge) {
                        "RUNNING" -> palette.WarningCol
                        "UNAVAILABLE", "OFFLINE" -> palette.Muted2
                        else -> palette.Accent
                    },
                )
            }
        }
    }
}

@Composable
private fun DrawerFooter(onOpenLicenses: () -> Unit) {
    val palette = LocalOpencodePalette.current
    Column {
        HorizontalDivider(color = palette.BorderCol.copy(alpha = 0.8f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(palette.AccentSoft)
                    .border(1.dp, palette.AccentStrong.copy(alpha = 0.28f), RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                Text("⌂", color = palette.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Local profile",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.TextCol,
                )
                Text(
                    "On-device · No account required",
                    fontSize = 9.sp,
                    color = palette.Muted2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "Licenses",
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onOpenLicenses)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.Muted,
            )
        }
    }
}
