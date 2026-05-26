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

        // Light-theme files use JetBrains' canonical icon palette (`#6C707E` for default,
        // `#000000` for the selected variant). The platform's SVG color patcher will tint
        // those palette values on dark themes, but its exact dark-theme mappings vary by IDE
        // build and custom theme — empirically a touch darker than we want for an idle stripe
        // icon. The two `_dark.svg` siblings (`#AFB1B3` idle / `#FFFFFF` active) pin the dark
        // appearance explicitly so the icon renders identically across IDE versions and
        // themes. This is hybrid by design, not a workaround: the previous bug was the two
        // dark siblings having the *wrong* colors (active was near-black, idle was light),
        // not the existence of the siblings themselves.
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
