package dev.matasar.antigravity.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory

class ArtifactsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ArtifactsPanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)

        // JetBrains' default state-paint for stripe buttons doesn't visibly change our icon
        // (it nudges the background, but the icon stays the same colour), so we explicitly
        // swap to an inverse-colour variant while the tool window is open. IconLoader picks
        // up the `_dark.svg` variant automatically on dark themes for both states.
        val normalIcon = IconLoader.getIcon("/icons/antigravity_artifacts.svg", javaClass)
        val activeIcon = IconLoader.getIcon("/icons/antigravity_artifacts_active.svg", javaClass)

        fun updateIcon() {
            toolWindow.setIcon(if (toolWindow.isVisible) activeIcon else normalIcon)
        }
        updateIcon()

        project.messageBus.connect(panel).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(twm: ToolWindowManager) {
                    updateIcon()
                }
            },
        )
    }
}
