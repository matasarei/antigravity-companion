package dev.matasar.antigravity.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.io.File

@Service(Service.Level.APP)
@State(
    name = "AntigravityCompanionSettings",
    storages = [Storage("antigravity-companion.xml")],
)
class AntigravitySettings : PersistentStateComponent<AntigravitySettings.State> {

    data class State(
        var agyPath: String = "",
        // When true, every toolbar click spawns a brand-new Antigravity terminal tab.
        // When false (default), the toolbar focuses the existing tab — multiple parallel
        // agy sessions are still supported by MCP, but most users want a single tab.
        var alwaysOpenNewTab: Boolean = false,
        // When true, agy is launched under a non-`xterm` TERM so its renderer stops emitting
        // CSI Z (cursor backward tabulation), which JediTerm ignores — see TerminfoCompat.
        // Without this, editing in the middle of the agy prompt garbles the display.
        var fixPromptCursorMovement: Boolean = true,
        // When true, agy is launched with --dangerously-skip-permissions, so it carries out tool
        // calls — shell commands included — without stopping to ask. agy's own default is its
        // `request-review` tool-permission mode, which is what produces the prompts. Off by
        // default and deliberately so: this hands agy unattended execution in the workspace.
        var autoApproveTools: Boolean = false,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
    }

    var agyPath: String
        get() = state.agyPath
        set(value) { state.agyPath = value.trim() }

    var alwaysOpenNewTab: Boolean
        get() = state.alwaysOpenNewTab
        set(value) { state.alwaysOpenNewTab = value }

    var fixPromptCursorMovement: Boolean
        get() = state.fixPromptCursorMovement
        set(value) { state.fixPromptCursorMovement = value }

    var autoApproveTools: Boolean
        get() = state.autoApproveTools
        set(value) { state.autoApproveTools = value }

    /**
     * Returns the path that should actually be executed: the user-configured value if it
     * points at an executable, otherwise a best-effort PATH/common-location lookup. Empty
     * string means we could not find `agy` anywhere.
     */
    fun resolvedAgyPath(): String {
        val configured = state.agyPath
        if (configured.isNotBlank() && File(configured).canExecute()) return configured
        return findAgyOnPath() ?: ""
    }

    companion object {
        fun getInstance(): AntigravitySettings =
            ApplicationManager.getApplication().getService(AntigravitySettings::class.java)

        fun findAgyOnPath(): String? {
            val binary = agyBinaryName()
            val candidates = LinkedHashSet<String>()
            System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
                if (dir.isNotBlank()) candidates.add(File(dir, binary).absolutePath)
            }
            val home = System.getProperty("user.home")
            candidates += "$home/.local/bin/$binary"
            candidates += "/opt/homebrew/bin/$binary"
            candidates += "/usr/local/bin/$binary"
            return candidates.firstOrNull { File(it).canExecute() }
        }

        private fun agyBinaryName(): String =
            if (isWindows()) "agy.exe" else "agy"

        fun isWindows(): Boolean =
            System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true
    }
}
