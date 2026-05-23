package dev.matasar.antigravity.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import dev.matasar.antigravity.service.AntigravityCompanionService

// StartupActivity.DumbAware matches what plugin.xml's <postStartupActivity> extension point
// declares it accepts. The newer ProjectActivity (suspend) interface technically works on
// 2023.2+ at runtime, but the EP is officially typed as StartupActivity — aligning the class
// with the declared contract avoids relying on undocumented platform dispatch behaviour.
class AntigravityStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        // Touching the service lazily instantiates it (and runs its init {} setup).
        project.service<AntigravityCompanionService>()
    }
}
