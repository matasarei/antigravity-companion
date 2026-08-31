package dev.matasar.antigravity.settings

import com.intellij.ide.DataManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
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
    private var fixCursorMovementBox: JBCheckBox? = null
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

        val cursorFixBox = JBCheckBox("Fix prompt editing in the IDE terminal").apply {
            isSelected = settings.fixPromptCursorMovement
            toolTipText = "Launches agy under a non-xterm TERM so it stops emitting CSI Z, " +
                "which the IDE's terminal emulator ignores."
        }
        fixCursorMovementBox = cursorFixBox

        val cursorFixHint = JBLabel(
            "<html>The IDE terminal ignores <code>CSI Z</code> (cursor backward tabulation), " +
                "so editing in the middle of the <code>agy</code> prompt garbles the display. " +
                "Launching <code>agy</code> under an equivalent non-<code>xterm</code> terminal " +
                "name avoids the sequence. Compiles a private <code>agyterm</code> entry into " +
                "<code>~/.terminfo</code> on first use, falling back to <code>nsterm</code>. " +
                "Turn off if you run <code>ssh</code> or containers from inside <code>agy</code> " +
                "and need the terminal name passed through unchanged.</html>"
        ).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

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
        // The label is kept in sync via reset(): the Settings dialog calls Configurable.reset()
        // whenever this panel becomes the visible one — including after the user navigates
        // back from the in-dialog Keymap, where they may have rebound the action. The Keymap
        // configurable updates the live KeymapManager immediately on edit (Apply/Cancel just
        // commits or rolls back), so reading via KeymapUtil in reset() reflects current state.
        val shortcutValue = JBLabel(currentShortcutText())
        shortcutValueLabel = shortcutValue

        val keymapLink = HyperlinkLabel("Configure shortcut in Keymap settings…")
        keymapLink.addHyperlinkListener(HyperlinkListener { event ->
            if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) return@HyperlinkListener
            // When this configurable is shown inside the Settings dialog (the usual case), the
            // `Settings` instance is available via DataContext. Navigate within the dialog
            // instead of opening a second one. We deliberately do NOT pre-filter via
            // select(configurable, option): the option is applied as a search query that
            // collapses Keymap to whichever group matches (e.g. "Other") without selecting the
            // actual row, which is more confusing than helpful. Land on Keymap at its top
            // level and let the user use the inline tip below to search.
            val ctx = DataManager.getInstance().getDataContext(keymapLink)
            val settingsDialog = Settings.KEY.getData(ctx)
            if (settingsDialog != null) {
                val keymapConfigurable = settingsDialog.find(KEYMAP_CONFIGURABLE_ID)
                if (keymapConfigurable != null) {
                    settingsDialog.select(keymapConfigurable)
                    return@HyperlinkListener
                }
            }
            // Fallback: somehow invoked outside the Settings dialog. Open a fresh one.
            ShowSettingsUtil.getInstance().showSettingsDialog(null, KEYMAP_CONFIGURABLE_ID)
            shortcutValueLabel?.text = currentShortcutText()
        })

        val shortcutHint = JBLabel(
            "Tip: search for \"Open Antigravity CLI\" in the Keymap dialog."
        ).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

        val formBuilder = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Path to agy executable:"), field, 1, false)
            .addComponentToRightColumn(JBLabel(hint).apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            })
            
        if (AntigravitySettings.isWindows()) {
            formBuilder.addComponentToRightColumn(JBLabel("<html>Note for WSL2: For WSL projects, you must run the IDE inside WSL (e.g. via Gateway/WSLg).</html>").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            })
        }

        formBuilder
            .addComponent(newTabBox)
            .addComponentToRightColumn(newTabHint)

        // Windows spawns agy directly rather than through a login shell, so there is no TERM
        // to override and the workaround has nothing to do — don't show a dead control.
        if (!AntigravitySettings.isWindows()) {
            formBuilder
                .addComponent(cursorFixBox)
                .addComponentToRightColumn(cursorFixHint)
        }

        val form = formBuilder
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
        val cursorFixChanged =
            (fixCursorMovementBox?.isSelected ?: true) != settings.fixPromptCursorMovement
        return pathChanged || flagChanged || cursorFixChanged
    }

    override fun apply() {
        val settings = AntigravitySettings.getInstance()
        settings.agyPath = pathField?.text ?: ""
        settings.alwaysOpenNewTab = alwaysOpenNewTabBox?.isSelected ?: false
        settings.fixPromptCursorMovement = fixCursorMovementBox?.isSelected ?: true
    }

    override fun reset() {
        val settings = AntigravitySettings.getInstance()
        pathField?.text = settings.agyPath
        alwaysOpenNewTabBox?.isSelected = settings.alwaysOpenNewTab
        fixCursorMovementBox?.isSelected = settings.fixPromptCursorMovement
        shortcutValueLabel?.text = currentShortcutText()
    }

    override fun disposeUIResources() {
        pathField = null
        alwaysOpenNewTabBox = null
        fixCursorMovementBox = null
        shortcutValueLabel = null
        rootPanel = null
    }

    private fun currentShortcutText(): String {
        val text = KeymapUtil.getFirstKeyboardShortcutText(StartSessionAction.ACTION_ID)
        return text.ifBlank { "Not set" }
    }

    companion object {
        // Stable ID of the Keymap configurable (registered by the platform in plugin.xml since
        // very old IDE versions). Used both for in-dialog navigation and as the fallback target
        // when opening a fresh Settings dialog.
        private const val KEYMAP_CONFIGURABLE_ID: String = "preferences.keymap"
    }
}
