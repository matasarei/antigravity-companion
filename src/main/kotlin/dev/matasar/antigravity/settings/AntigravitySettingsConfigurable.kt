package dev.matasar.antigravity.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class AntigravitySettingsConfigurable : Configurable {

    private var pathField: TextFieldWithBrowseButton? = null
    private var rootPanel: JPanel? = null

    override fun getDisplayName(): String = "Antigravity Companion"

    override fun createComponent(): JComponent {
        val settings = AntigravitySettings.getInstance()

        // Replace the deprecated 4-arg addBrowseFolderListener(title, description, project, descriptor)
        // with the non-deprecated form: title and description live on the descriptor itself,
        // and the listener wraps it.
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            .withTitle("Select the agy Executable")
            .withDescription("Choose the location of the agy CLI binary the plugin should launch.")

        val field = TextFieldWithBrowseButton().apply {
            text = settings.agyPath
            addBrowseFolderListener(TextBrowseFolderListener(descriptor))
            textField.toolTipText = "Absolute path to the agy executable"
        }
        pathField = field

        val detected = AntigravitySettings.findAgyOnPath()
        val hint = if (detected != null) {
            "Leave blank to auto-detect. Currently resolved to: $detected"
        } else {
            "Leave blank to auto-detect. Searched \$PATH, ~/.local/bin, /opt/homebrew/bin, /usr/local/bin — none had agy."
        }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Path to agy executable:"), field, 1, false)
            .addComponentToRightColumn(JBLabel(hint).apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            })
            .addComponentFillVertically(JPanel(), 0)
            .panel

        val container = JPanel(BorderLayout())
        container.add(form, BorderLayout.NORTH)
        rootPanel = container
        return container
    }

    override fun isModified(): Boolean =
        (pathField?.text ?: "") != AntigravitySettings.getInstance().agyPath

    override fun apply() {
        AntigravitySettings.getInstance().agyPath = pathField?.text ?: ""
    }

    override fun reset() {
        pathField?.text = AntigravitySettings.getInstance().agyPath
    }

    override fun disposeUIResources() {
        pathField = null
        rootPanel = null
    }
}
