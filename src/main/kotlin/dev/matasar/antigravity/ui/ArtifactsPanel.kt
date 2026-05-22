package dev.matasar.antigravity.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Right-anchored tool window content showing the agy "artifacts" (brain entries — plans,
 * summaries, notes) generated for the current project. Auto-refreshes every 3 seconds and
 * exposes a manual Refresh button. Double-click or Enter opens an artifact in the editor.
 */
class ArtifactsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val log = Logger.getInstance(ArtifactsPanel::class.java)
    @Volatile private var disposed = false
    private val listModel = DefaultListModel<ArtifactItem>()
    private val list = JBList(listModel).apply {
        cellRenderer = ArtifactCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "No agy artifacts yet."
        emptyText.appendLine("Start an agy session in this project to generate plans, summaries, and notes.")
    }
    private val poller = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        // Toolbar: Refresh + Open Brain Folder
        val refreshAction = object : AnAction("Refresh", "Reload the artifact list now", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                reload()
            }
        }
        val openFolderAction = object : AnAction(
            "Open Brain Folder",
            "Reveal the agy brain folder for this project in the system file manager",
            AllIcons.Nodes.Folder,
        ) {
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

        // Initial load + start polling
        reload()
        schedulePoll()
    }

    private fun reload() {
        if (disposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
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

    private fun schedulePoll() {
        if (disposed) return
        poller.addRequest({
            try {
                reload()
            } finally {
                schedulePoll()
            }
        }, POLL_INTERVAL_MS)
    }

    private fun openSelected() {
        val item = list.selectedValue ?: return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(item.path)
        if (vf == null) {
            log.info("Selected artifact no longer exists on disk: ${item.path}")
            reload()
            return
        }
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    private fun openBrainFolder() {
        val dir = ArtifactsRepository.brainBaseDir() ?: return
        com.intellij.ide.actions.RevealFileAction.openFile(dir)
    }

    override fun dispose() {
        disposed = true
        // Alarm registered with `this` as parent is auto-disposed via Disposer.
    }

    companion object {
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
        val tip = value.summary ?: "Conversation ${value.conversationId}"
        toolTipText = tip
    }

    private fun iconFor(name: String): javax.swing.Icon = when {
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

    /** Directory holding all conversations' brain entries, regardless of project. */
    fun brainBaseDir(): File? {
        val dir = File(System.getProperty("user.home"), ".gemini/antigravity-cli/brain")
        return dir.takeIf { it.isDirectory }
    }

    fun listArtifacts(): List<ArtifactItem> {
        // We do NOT filter by project. The agy CLI's brain dirs live at
        // `~/.gemini/antigravity-cli/brain/<conversation-uuid>/`, and the conversation↔project
        // mapping is stored inside protobuf .pb files we can't cheaply parse. Instead we list
        // files from the N most recently modified brain dirs, sorted globally by mtime desc.
        // In practice the user's currently-active session is always on top (it just wrote);
        // older sessions from other projects scroll down and are easy to ignore.
        val brainBase = brainBaseDir() ?: return emptyList()
        val recentBrainDirs = brainBase.listFiles()
            ?.filter { it.isDirectory && UUID_RE.matches(it.name) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_BRAIN_DIRS)
            ?: return emptyList()

        val results = mutableListOf<ArtifactItem>()
        for (brainDir in recentBrainDirs) {
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

    private const val MAX_BRAIN_DIRS = 20
    private const val MAX_ARTIFACTS = 100

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
