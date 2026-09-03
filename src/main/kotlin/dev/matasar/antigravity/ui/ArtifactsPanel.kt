package dev.matasar.antigravity.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager

/**
 * Right-anchored tool window content listing agy "artifacts" — brain entries (plans,
 * summaries, walkthroughs) from recent agy conversations on this machine. Files come from
 * `~/.gemini/antigravity-cli/brain/<conversation-uuid>/`; we sort by mtime so the active
 * session's output sits at the top regardless of which project a given conversation belongs
 * to. (Mapping brain dirs to projects would require parsing the protobuf conversation files,
 * which isn't worth the cost; relying on mtime is good enough in practice.)
 *
 * Polls the filesystem every 3 seconds while the tool window is visible (paused while
 * hidden) and exposes a manual Refresh button. Double-click or Enter opens an artifact in
 * the editor.
 */
class ArtifactsPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : SimpleToolWindowPanel(true, true), Disposable {

    @Volatile private var disposed = false
    @Volatile private var pollScheduled = false
    private val listModel = DefaultListModel<ArtifactItem>()
    private val list: JBList<ArtifactItem> = object : JBList<ArtifactItem>(listModel) {
        // Per-row tooltips on a JList must come from this override — tooltips set on the cell
        // renderer don't propagate because the renderer's component isn't a real Swing child.
        override fun getToolTipText(event: MouseEvent): String? {
            val index = locationToIndex(event.point)
            if (index < 0) return null
            val bounds = getCellBounds(index, index) ?: return null
            if (!bounds.contains(event.point)) return null
            val item = listModel.getElementAt(index) ?: return null
            return item.summary ?: "Conversation ${item.conversationId}"
        }
    }.apply {
        cellRenderer = ArtifactCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "No agy artifacts yet."
        emptyText.appendLine("Start an agy session in this project to generate plans, summaries, and notes.")
        ToolTipManager.sharedInstance().registerComponent(this)
    }
    private val poller = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        // Toolbar: Refresh + Open Brain Folder
        val refreshAction = object : AnAction("Refresh", "Reload the artifact list now", AllIcons.Actions.Refresh) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                reloadAsync()
            }
        }
        val openFolderAction = object : AnAction(
            "Open Brain Folder",
            "Open the agy brain folder in the system file manager",
            AllIcons.Nodes.Folder,
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

            // agy creates the brain directory on its first conversation, so it is genuinely
            // absent on a fresh install. Grey the button out rather than leave a click that
            // does nothing. The isDirectory check is filesystem I/O, which is what BGT is for.
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = ArtifactsRepository.brainBaseDir() != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                openBrainFolder()
            }
        }
        val group = DefaultActionGroup(refreshAction, openFolderAction)
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = list
        setToolbar(toolbar.component)

        // Scrollable list as content
        setContent(JBScrollPane(list))

        // Double-click opens; Enter opens
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openSelected()
                }
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    openSelected()
                    e.consume()
                }
            }
        })

        // Right-click menu. Both entries act on the selected row, so the popup trigger has to
        // move the selection under the cursor first: Swing does not do that for a JList, and
        // without it the menu would silently act on whatever was selected beforehand.
        list.addMouseListener(object : MouseAdapter() {
            // The popup trigger is the press on Windows/Linux but the release on macOS, so
            // both handlers test for it rather than assuming one or the other.
            override fun mousePressed(e: MouseEvent) = selectRowUnderCursor(e)

            override fun mouseReleased(e: MouseEvent) = selectRowUnderCursor(e)

            private fun selectRowUnderCursor(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val index = list.locationToIndex(e.point)
                if (index < 0) return
                // locationToIndex returns the nearest row for a click past the last one, so
                // confirm the point is actually inside it before hijacking the selection.
                val bounds = list.getCellBounds(index, index) ?: return
                if (bounds.contains(e.point)) list.selectedIndex = index
            }
        })
        PopupHandler.installPopupMenu(
            list,
            DefaultActionGroup(copyPathAction(), revealInFileManagerAction()),
            ActionPlaces.TOOLWINDOW_POPUP,
        )

        // Don't assume the panel was created in response to the tool window opening: the
        // platform may instantiate content while the window is still hidden (e.g. during
        // project-open layout restoration). Only do the initial reload / start polling if
        // we're actually visible right now — the listener below will fire and bootstrap
        // both the moment the user opens the tab.
        if (toolWindow.isVisible) {
            reloadAsync()
            startPolling()
        }

        // Pause/resume polling based on the tool window's visibility so we don't scan the
        // filesystem every 3 seconds while the panel is closed.
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(twm: ToolWindowManager) {
                    if (disposed) return
                    if (toolWindow.isVisible) {
                        // Catch up on anything that changed while we were hidden, then poll.
                        reloadAsync()
                        startPolling()
                    } else {
                        stopPolling()
                    }
                }
            },
        )
    }

    /**
     * Safe to call from any thread — schedules the filesystem listing on a pooled thread and
     * applies the result back on the EDT via `reloadInline`. In practice callers are EDT-side
     * (toolbar `actionPerformed`, Swing listeners), but the implementation itself doesn't
     * assume that, so declaring `ActionUpdateThread.BGT` on the toolbar actions is fine.
     */
    private fun reloadAsync() {
        if (disposed) return
        ApplicationManager.getApplication().executeOnPooledThread { reloadInline() }
    }

    /** Use when already on a worker thread (e.g. inside the poller's tick). */
    private fun reloadInline() {
        if (disposed) return
        val items = try {
            ArtifactsRepository.listArtifacts()
        } catch (t: Throwable) {
            log.warn("Failed to list agy artifacts", t)
            emptyList()
        }
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            applyItems(items)
        }
    }

    private fun applyItems(items: List<ArtifactItem>) {
        // Preserve selection across reloads by path.
        val previouslySelectedPath = list.selectedValue?.path
        listModel.clear()
        items.forEach { listModel.addElement(it) }
        if (previouslySelectedPath != null) {
            val idx = items.indexOfFirst { it.path == previouslySelectedPath }
            if (idx >= 0) list.selectedIndex = idx
        }
    }

    private fun startPolling() {
        if (disposed || pollScheduled) return
        pollScheduled = true
        schedulePollTick()
    }

    private fun stopPolling() {
        pollScheduled = false
        poller.cancelAllRequests()
    }

    private fun schedulePollTick() {
        if (disposed || !pollScheduled) return
        poller.addRequest({
            // Already on a pooled thread — do the listing inline instead of dispatching again.
            try {
                reloadInline()
            } finally {
                schedulePollTick()
            }
        }, POLL_INTERVAL_MS)
    }

    /**
     * Copies the artifact's absolute path — the useful thing to paste into a terminal or back
     * into an `agy` prompt. Artifacts live outside the project's content roots, so the
     * platform's own "Copy Path" (which works off a VirtualFile in a project view) isn't
     * available to this list.
     */
    private fun copyPathAction(): AnAction =
        object : AnAction("Copy Path", "Copy the artifact's full path to the clipboard", AllIcons.Actions.Copy) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = list.selectedValue != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                val item = list.selectedValue ?: return
                CopyPasteManager.getInstance().setContents(StringSelection(item.path))
            }
        }

    /**
     * Selects the artifact in the OS file manager. The label is "Reveal in Finder", "Show in
     * Explorer" or "Show in Files" depending on the platform, which is why it comes from
     * [RevealFileAction] rather than being hardcoded.
     */
    private fun revealInFileManagerAction(): AnAction =
        object : AnAction(RevealFileAction.getActionName(), "Select the artifact in the system file manager", null) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = list.selectedValue != null && RevealFileAction.isSupported()
            }

            override fun actionPerformed(e: AnActionEvent) {
                val item = list.selectedValue ?: return
                val file = File(item.path)
                // The list is a 3-second snapshot, so the row can outlive the file (agy prunes
                // brain entries). Refresh instead of handing the file manager a dead path.
                if (!file.exists()) {
                    log.info("Artifact no longer exists on disk: ${item.path}")
                    reloadAsync()
                    return
                }
                RevealFileAction.openFile(file)
            }
        }

    private fun openSelected() {
        val item = list.selectedValue ?: return
        // Deliberately off the EDT. `openTextEditor` is documented as EDT-only, and on the EDT
        // FileEditorManagerImpl waits for the editor composite by pumping a nested event loop
        // (blockingWaitForCompositeFileOpen). Editor state is then restored inside that nested
        // pump — in a write-unsafe context, with PSI not yet committed — which the platform
        // reports as two internal errors (see GitHub issue #12). Called from a background
        // thread, `openFile` takes the platform's coroutine path instead: no nested pump, and
        // it still returns only once the composite is available.
        ApplicationManager.getApplication().executeOnPooledThread {
            // refreshAndFindFileByIoFile normalises path separators (matters on Windows where
            // File.absolutePath uses backslashes, while VFS path APIs expect forward slashes).
            // It is a synchronous VFS refresh, which belongs off the EDT anyway.
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(item.path))
            if (vf == null) {
                log.info("Selected artifact no longer exists on disk: ${item.path}")
                reloadAsync()
                return@executeOnPooledThread
            }
            if (disposed || project.isDisposed) return@executeOnPooledThread
            val editorManager = FileEditorManager.getInstance(project)
            editorManager.openFile(vf, true)
            // `openFile` selects the file's default editor provider. For `.md` artifacts that is
            // the Markdown editor, whose JCEF preview pane renders a blank screen for files
            // outside the project's content roots — and brain artifacts at
            // `~/.gemini/antigravity-cli/brain/...` are always out-of-project. Switch the
            // composite to the plain text editor so double-click/Enter reliably shows the
            // content (see GitHub issue #7).
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed || !vf.isValid) return@invokeLater
                editorManager.setSelectedEditor(vf, TextEditorProvider.getInstance().editorTypeId)
            }, ModalityState.defaultModalityState())
        }
    }

    private fun openBrainFolder() {
        val dir = ArtifactsRepository.brainBaseDir() ?: return
        // openDirectory shows the folder's contents; openFile would select it inside its
        // parent, leaving the user in ~/.gemini/antigravity-cli/ rather than in brain/.
        RevealFileAction.openDirectory(dir)
    }

    override fun dispose() {
        disposed = true
        pollScheduled = false
        // The Alarm registered with `this` as parent is auto-disposed by the platform.
    }

    companion object {
        private val log = Logger.getInstance(ArtifactsPanel::class.java)
        private const val POLL_INTERVAL_MS = 3000
    }
}

data class ArtifactItem(
    val name: String,
    val path: String,
    val mtime: Long,
    val conversationId: String,
    val summary: String?,
)

private class ArtifactCellRenderer : ColoredListCellRenderer<ArtifactItem>() {
    override fun customizeCellRenderer(
        list: JList<out ArtifactItem>,
        value: ArtifactItem?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value == null) return
        icon = iconFor(value.name)
        append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("   " + relativeTime(value.mtime), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        // Add the conversation ID prefix as a discriminator — multiple brain dirs often share
        // the same artifact filename (e.g. "implementation_plan.md"), so the user needs to see
        // which conversation it belongs to.
        append("   " + value.conversationId.take(8), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        // Per-row tooltips are handled by JList.getToolTipText in ArtifactsPanel.
    }

    private fun iconFor(name: String): Icon = when {
        name.endsWith(".md", ignoreCase = true) -> AllIcons.FileTypes.Text
        name.endsWith(".json", ignoreCase = true) -> AllIcons.FileTypes.Json
        else -> AllIcons.FileTypes.Any_type
    }

    private fun relativeTime(mtime: Long): String {
        val seconds = (System.currentTimeMillis() - mtime) / 1000
        return when {
            seconds < 5 -> "just now"
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            seconds < 604_800 -> "${seconds / 86400}d ago"
            else -> "${seconds / 604_800}w ago"
        }
    }
}

object ArtifactsRepository {

    private val log = Logger.getInstance(ArtifactsRepository::class.java)
    private val UUID_RE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    // A readdir budget, not a recency window: we list this many conversation dirs per scan and
    // let artifact mtimes decide what actually surfaces (see listArtifacts). Kept generous
    // because a dir's own mtime says almost nothing about whether it holds artifacts.
    private const val MAX_SCANNED_BRAIN_DIRS = 250
    private const val MAX_ARTIFACTS = 100

    /** Directory holding all conversations' brain entries, regardless of project. */
    fun brainBaseDir(): File? {
        val dir = File(System.getProperty("user.home"), ".gemini/antigravity-cli/brain")
        return dir.takeIf { it.isDirectory }
    }

    fun listArtifacts(): List<ArtifactItem> {
        // We do NOT filter by project. The agy CLI's brain dirs live at
        // `~/.gemini/antigravity-cli/brain/<conversation-uuid>/`, and the conversation↔project
        // mapping is stored inside protobuf .pb files we can't cheaply parse. So we scan every
        // conversation dir and rank the *files* we find by mtime; the active session's plan or
        // walkthrough lands on top because it was just written, and older sessions from other
        // projects scroll down.
        //
        // Ranking the dirs by mtime and keeping only the newest handful does NOT work: agy
        // creates `.system_generated/` (transcripts, per-step output, task logs) at conversation
        // start and writes into it continuously, which bumps the conversation dir's mtime for the
        // whole session while the user-facing artifacts — implementation_plan.md, walkthrough.md,
        // code_review.md, images — may never appear at all. Every busy-but-artifact-less
        // conversation then evicts a dir that does hold artifacts, and the panel goes empty.
        val brainBase = brainBaseDir() ?: return emptyList()
        val brainDirs = brainBase.listFiles()
            ?.filter { it.isDirectory && UUID_RE.matches(it.name) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_SCANNED_BRAIN_DIRS)
            ?: return emptyList()

        val results = mutableListOf<ArtifactItem>()
        for (brainDir in brainDirs) {
            brainDir.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && !it.name.endsWith(".metadata.json") }
                ?.forEach { f ->
                    val meta = readMetadata(File(brainDir, "${f.name}.metadata.json"))
                    results += ArtifactItem(
                        name = f.name,
                        path = f.absolutePath,
                        mtime = f.lastModified(),
                        conversationId = brainDir.name,
                        summary = meta?.summary,
                    )
                }
        }
        return results.sortedByDescending { it.mtime }.take(MAX_ARTIFACTS)
    }

    private fun readMetadata(file: File): ArtifactMetadata? {
        if (!file.isFile) return null
        return try {
            val obj = Json.parseToJsonElement(file.readText()).jsonObject
            ArtifactMetadata(
                summary = obj["summary"]?.jsonPrimitive?.contentOrNull,
                artifactType = obj["artifactType"]?.jsonPrimitive?.contentOrNull,
                updatedAt = obj["updatedAt"]?.jsonPrimitive?.contentOrNull,
            )
        } catch (e: Exception) {
            log.debug("Could not parse metadata at ${file.absolutePath}: ${e.message}")
            null
        }
    }
}

private data class ArtifactMetadata(
    val summary: String?,
    val artifactType: String?,
    val updatedAt: String?,
)
