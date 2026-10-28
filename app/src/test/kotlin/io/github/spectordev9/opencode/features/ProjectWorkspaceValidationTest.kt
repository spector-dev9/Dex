package io.github.spectordev9.opencode.features

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectWorkspaceValidationTest {
    @Test
    fun `project folder names cannot escape the workspace`() {
        assertEquals("my-project", WorkspaceCatalog.validateProjectName(" my-project "))
        assertNull(WorkspaceCatalog.validateProjectName("../escape"))
        assertNull(WorkspaceCatalog.validateProjectName("a/b"))
        assertNull(WorkspaceCatalog.validateProjectName(".."))
        assertNull(WorkspaceCatalog.validateProjectName(""))
        assertNull(WorkspaceCatalog.validateProjectName("x".repeat(65)))
    }

    @Test
    fun `environment names and values are constrained`() {
        assertEquals("API_URL", ProjectEnvironmentStore.validateName("API_URL"))
        assertNull(ProjectEnvironmentStore.validateName("1BAD"))
        assertNull(ProjectEnvironmentStore.validateName("WITH SPACE"))
        assertTrue(ProjectEnvironmentStore.validateValue("https://example.com"))
        assertFalse(ProjectEnvironmentStore.validateValue(""))
        assertFalse(ProjectEnvironmentStore.validateValue("line\nbreak"))
    }
}
