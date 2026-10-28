package io.github.spectordev9.opencode.features

/**
 * Aggregates advanced feature controllers for the UI (F10, F51-F55, F68-F70,
 * F74-F76). The graph itself stays in AppRuntime so composables never own
 * Android or server clients directly.
 */
class AdvancedTools(
    val extensions: ServerExtensionsController,
    val projects: WorkspaceCatalog,
    val environment: ProjectEnvironmentStore,
    val builds: BuildController,
    val diagnostics: DiagnosticsController,
    val notifier: TaskNotifier,
    val selectProject: (String) -> io.github.spectordev9.opencode.core.common.ApiResult<ProjectWorkspace>,
    val createProject: (String) -> io.github.spectordev9.opencode.core.common.ApiResult<ProjectWorkspace>,
    private val compatibilityProbe: () -> CompatibilityReport,
) {
    fun compatibility(): CompatibilityReport = compatibilityProbe()
}
