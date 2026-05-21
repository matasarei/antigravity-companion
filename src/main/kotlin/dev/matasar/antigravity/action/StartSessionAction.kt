package dev.matasar.antigravity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.util.IconLoader
import dev.matasar.antigravity.service.AntigravityCompanionService

class StartSessionAction : AnAction(
    "Start Antigravity Session",
    "Launch a new agy terminal session for this project",
    IconLoader.getIcon("/icons/antigravity.svg", StartSessionAction::class.java),
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.getService(AntigravityCompanionService::class.java) ?: return
        service.triggerNewTerminalSession()
    }
}
