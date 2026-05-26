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

        // Two single-SVG icons, no `_dark.svg` siblings: both files use JetBrains' canonical
        // icon palette (`#6C707E` for the default, `#000000` for the active variant). The
        // platform's SVG color patcher re-tints those palette values automatically based on
        // the current LAF — light themes paint them roughly as-authored; dark themes invert
        // to a light grey / near-white. Avoid introducing custom hex values here: the patcher
        // skips colors it doesn't recognise, leaving them dark in both themes (the exact
        // failure mode that caused the "black icon on dark stripe" bug we previously papered
        // over with explicit `_dark.svg` companions).
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
