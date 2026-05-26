package dev.matasar.antigravity.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.matasar.antigravity.action.StartSessionAction
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener

class AntigravitySettingsConfigurable : Configurable {

    private var pathField: TextFieldWithBrowseButton? = null
    private var alwaysOpenNewTabBox: JBCheckBox? = null
    private var shortcutValueLabel: JBLabel? = null
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

        val newTabBox = JBCheckBox("Always open a new tab").apply {
            isSelected = settings.alwaysOpenNewTab
            toolTipText = "When off, the toolbar button focuses the existing Antigravity " +
                "terminal tab if one is open."
        }
        alwaysOpenNewTabBox = newTabBox

        val newTabHint = JBLabel(
            "When off, clicking the toolbar focuses the existing Antigravity tab. " +
                "Turn on to spawn a fresh agy session on every click."
        ).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

        // Read-only view of the current keymap binding. We deliberately don't ship a default
        // shortcut (no chord is safe across all bundled keymaps — Default, Eclipse, VS, Mac,
        // Emacs, etc. — and a silently overridden user binding is worse than no binding). The
        // hyperlink below opens Settings → Keymap so the user can pick their own.
        val shortcutValue = JBLabel(currentShortcutText())
        shortcutValueLabel = shortcutValue

        val keymapLink = HyperlinkLabel("Configure shortcut in Keymap settings…").apply {
            addHyperlinkListener(HyperlinkListener { event ->
                if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    // Opens the top-level Keymap configurable; the user searches for
                    // "Open Antigravity CLI" or our action ID to find the entry.
                    ShowSettingsUtil.getInstance().showSettingsDialog(null, "preferences.keymap")
                    // Refresh the displayed shortcut after the user returns. The settings
                    // dialog is modal, so by the time we reach this line it has closed and
                    // any keymap edits are already applied.
                    shortcutValueLabel?.text = currentShortcutText()
                }
            })
        }

        val shortcutHint = JBLabel(
            "Tip: search for \"Open Antigravity CLI\" in the Keymap dialog."
        ).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Path to agy executable:"), field, 1, false)
            .addComponentToRightColumn(JBLabel(hint).apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            })
            .addComponent(newTabBox)
            .addComponentToRightColumn(newTabHint)
            .addLabeledComponent(JBLabel("Keyboard shortcut:"), shortcutValue, 1, false)
            .addComponentToRightColumn(keymapLink)
            .addComponentToRightColumn(shortcutHint)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        val container = JPanel(BorderLayout())
        container.add(form, BorderLayout.NORTH)
        rootPanel = container
        return container
    }

    override fun isModified(): Boolean {
        val settings = AntigravitySettings.getInstance()
        val pathChanged = (pathField?.text ?: "") != settings.agyPath
        val flagChanged = (alwaysOpenNewTabBox?.isSelected ?: false) != settings.alwaysOpenNewTab
        return pathChanged || flagChanged
    }

    override fun apply() {
        val settings = AntigravitySettings.getInstance()
        settings.agyPath = pathField?.text ?: ""
        settings.alwaysOpenNewTab = alwaysOpenNewTabBox?.isSelected ?: false
    }

    override fun reset() {
        val settings = AntigravitySettings.getInstance()
        pathField?.text = settings.agyPath
        alwaysOpenNewTabBox?.isSelected = settings.alwaysOpenNewTab
        shortcutValueLabel?.text = currentShortcutText()
    }

    override fun disposeUIResources() {
        pathField = null
        alwaysOpenNewTabBox = null
        shortcutValueLabel = null
        rootPanel = null
    }

    private fun currentShortcutText(): String {
        val text = KeymapUtil.getFirstKeyboardShortcutText(StartSessionAction.ACTION_ID)
        return text.ifBlank { "Not set" }
    }
}
