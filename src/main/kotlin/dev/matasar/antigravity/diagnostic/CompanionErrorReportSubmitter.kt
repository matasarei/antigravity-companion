package dev.matasar.antigravity.diagnostic

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import java.awt.Component
import java.net.URLEncoder

/**
 * Handles the "Report" button on the IDE's internal-error dialog for errors the platform blames
 * on this plugin.
 *
 * The IDE attributes an error to whichever plugin it finds while walking the stack trace
 * (`PluginUtil.findPluginId`), so an assertion raised by platform code that we merely *called*
 * lands here rather than with JetBrains. Issue #12 is exactly that: opening a file is EDT-only,
 * the platform then waits for the editor composite by pumping a nested event loop, and restoring
 * editor state inside that pump trips two of its own assertions. It is cosmetic — the file opens
 * correctly — but the balloon says "Antigravity CLI Companion", so the reports arrive here.
 *
 * We cannot stop the balloon: `errorHandler` is the only error-related extension point, and it
 * governs reporting, not whether an error surfaces. Suppressing it from plugin code would mean
 * flipping a global registry key or replacing the logger factory, both of which would silence
 * the same diagnostics for the whole IDE. So instead this tells the user what they are looking
 * at, and stops another duplicate of #12 being filed.
 *
 * Anything we do not recognise gets the normal treatment: a prefilled GitHub issue.
 */
internal class CompanionErrorReportSubmitter : ErrorReportSubmitter() {

    override fun getReportActionText(): String = "Report to Antigravity CLI Companion"

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val event = events.firstOrNull()
        if (event != null && isKnownPlatformEditorOpenAssertion(event)) {
            consumer.consume(
                SubmittedReportInfo(
                    KNOWN_ISSUE_URL,
                    "Known IDE platform issue — your file opened correctly, nothing is lost (#12)",
                    SubmittedReportInfo.SubmissionStatus.DUPLICATE,
                )
            )
            return true
        }

        BrowserUtil.browse(newIssueUrl(event, additionalInfo))
        consumer.consume(
            SubmittedReportInfo(
                null,
                "GitHub issue form opened in your browser",
                SubmittedReportInfo.SubmissionStatus.NEW_ISSUE,
            )
        )
        return true
    }

    /**
     * Both assertions in #12 are raised while the editor composite restores its state inside the
     * platform's own nested event pump. Matching the message alone would be too broad — a
     * write-unsafe context is a generic complaint — so we also require an `EditorComposite` frame,
     * which is what pins it to this specific open-a-file path rather than to a real bug of ours.
     */
    private fun isKnownPlatformEditorOpenAssertion(event: IdeaLoggingEvent): Boolean {
        val text = event.throwableText
        val messageMatches = KNOWN_ASSERTION_MESSAGES.any { text.contains(it) }
        return messageMatches && text.contains("com.intellij.openapi.fileEditor.impl.EditorComposite")
    }

    private fun newIssueUrl(event: IdeaLoggingEvent?, additionalInfo: String?): String {
        val title = event?.throwable?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
            ?: "Unexpected error"
        // Browsers and GitHub both tolerate far more than this, but a truncated trace that opens
        // beats a complete one that silently fails to load.
        val body = buildString {
            additionalInfo?.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
            append("```\n").append(event?.throwableText.orEmpty().take(STACK_TRACE_LIMIT)).append("\n```\n")
        }
        return "$REPO_URL/issues/new" +
            "?title=" + encode(title.take(TITLE_LIMIT)) +
            "&body=" + encode(body)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val REPO_URL = "https://github.com/matasarei/antigravity-companion"
        const val KNOWN_ISSUE_URL = "$REPO_URL/issues/12"
        const val TITLE_LIMIT = 180
        const val STACK_TRACE_LIMIT = 5000

        val KNOWN_ASSERTION_MESSAGES = listOf(
            "File should be parsed when changing editor state",
            "Write-unsafe context!",
        )
    }
}
