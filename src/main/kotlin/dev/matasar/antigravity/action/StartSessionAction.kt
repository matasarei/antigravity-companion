package dev.matasar.antigravity.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import dev.matasar.antigravity.service.AntigravityCompanionService

// Icon is declared on the <action> element in plugin.xml so the IDE can resolve it without
// touching this class; no need to duplicate the IconLoader call here.
class StartSessionAction : AnAction(
    "Start Antigravity Session",
    "Launch a new agy terminal session for this project",
    null,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<AntigravityCompanionService>().triggerNewTerminalSession()
    }
}
