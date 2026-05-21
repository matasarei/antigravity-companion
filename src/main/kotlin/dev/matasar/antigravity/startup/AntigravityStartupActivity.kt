package dev.matasar.antigravity.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import dev.matasar.antigravity.service.AntigravityCompanionService

class AntigravityStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        project.getService(AntigravityCompanionService::class.java)
    }
}
