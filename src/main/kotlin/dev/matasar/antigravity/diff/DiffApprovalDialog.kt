package dev.matasar.antigravity.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Modal side-by-side diff dialog used by the `ide_show_diff` MCP tool. Offers three buttons:
 *
 * - **Reject** — discard the proposed change.
 * - **Accept** — apply this change; future diffs in the same MCP connection still prompt.
 * - **Accept All** — apply this change AND mark the connection as auto-accept so subsequent
 *   `ide_show_diff` calls skip the dialog.
 *
 * The caller blocks on [show] (it's modal) and then reads [outcome].
 */
class DiffApprovalDialog(
    private val project: Project,
    private val filePath: String,
    private val originalContent: String,
    private val proposedContent: String,
    private val summary: String?,
    private val fileType: FileType,
) : DialogWrapper(project, true) {

    enum class Outcome { REJECT, ACCEPT, ACCEPT_ALL }

    var outcome: Outcome = Outcome.REJECT
        private set

    private val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
    private val isNewFile: Boolean = originalContent.isEmpty() && !File(filePath).exists()

    init {
        val shortName = File(filePath).name
        title = if (isNewFile) {
            "Antigravity proposes creating $shortName"
        } else {
            "Antigravity proposes changes to $shortName"
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(1000, 600)

        val headerText = buildString {
            append("<html><body style='padding: 4px 8px;'>")
            append("<b>").append(filePath).append("</b>")
            if (!summary.isNullOrBlank()) {
                append("<br>").append(escapeHtml(summary))
            }
            append("</body></html>")
        }
        panel.add(JBLabel(headerText).apply { border = JBUI.Borders.emptyBottom(4) }, BorderLayout.NORTH)

        val factory = DiffContentFactory.getInstance()
        val original = factory.create(project, originalContent, fileType)
        val proposed = factory.create(project, proposedContent, fileType)
        val request = SimpleDiffRequest(
            File(filePath).name,
            original,
            proposed,
            if (isNewFile) "(does not exist)" else "Current",
            "Proposed",
        )
        diffPanel.setRequest(request)
        panel.add(diffPanel.component, BorderLayout.CENTER)
        return panel
    }

    override fun createActions(): Array<Action> {
        val reject = object : AbstractAction("Reject") {
            override fun actionPerformed(e: ActionEvent) {
                outcome = Outcome.REJECT
                close(CANCEL_EXIT_CODE)
            }
        }
        val accept = object : AbstractAction("Accept") {
            override fun actionPerformed(e: ActionEvent) {
                outcome = Outcome.ACCEPT
                close(OK_EXIT_CODE)
            }
        }
        val acceptAll = object : AbstractAction("Accept All") {
            override fun actionPerformed(e: ActionEvent) {
                outcome = Outcome.ACCEPT_ALL
                close(OK_EXIT_CODE)
            }
        }
        // Order matters visually: Reject (destructive) on the left, Accept variants on the right.
        return arrayOf(reject, accept, acceptAll)
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
}
