package dev.matasar.antigravity.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.matasar.antigravity.service.AntigravityCompanionService

class AntigravityStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Touching the service lazily instantiates it (and runs its init {} setup).
        project.service<AntigravityCompanionService>()
    }
}
