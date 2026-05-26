package dev.matasar.antigravity.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import dev.matasar.antigravity.service.AntigravityCompanionService

// Icon is declared on the <action> element in plugin.xml so the IDE can resolve it without
// touching this class; no need to duplicate the IconLoader call here.
class StartSessionAction : AnAction(
    "Open Antigravity CLI",
    "Open the Antigravity CLI in an IDE-embedded terminal",
    null,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<AntigravityCompanionService>().triggerNewTerminalSession()
    }

    companion object {
        // Kept in sync with the `id="..."` attribute on this action in plugin.xml. Shared so the
        // settings panel can ask KeymapUtil for the currently bound shortcut without hard-coding
        // the string in two places.
        const val ACTION_ID: String = "dev.matasar.antigravity.StartSessionAction"
    }
}
