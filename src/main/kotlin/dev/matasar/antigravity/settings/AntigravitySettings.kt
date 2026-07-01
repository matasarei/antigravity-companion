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

    /**
     * Returns the path that should actually be executed: the user-configured value if it
     * points at an executable, otherwise a best-effort PATH/common-location lookup. Empty
     * string means we could not find `agy` anywhere.
     */
    fun resolvedAgyPath(): String {
        val configured = state.agyPath
        if (configured.isNotBlank()) {
            val file = File(configured)
            // Over the \\wsl.localhost\ (9p) share, Java's canExecute() is unreliable — the
            // Linux executable bit isn't surfaced through the Windows file API. For a configured
            // WSL path, accept it as long as it exists; wsl.exe enforces the real exec bit later.
            val usable = file.canExecute() || (parseWslPath(configured) != null && file.exists())
            if (usable) return configured
        }
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

        /** A Windows UNC path resolved into the WSL distro it lives in and its Linux-side path. */
        data class WslPath(val distro: String, val linuxPath: String)

        /**
         * Recognises Windows UNC paths that point into a WSL2 distribution's filesystem:
         *   \\wsl.localhost\Ubuntu\home\me\.local\bin\agy  → distro=Ubuntu, /home/me/.local/bin/agy
         *   \\wsl$\Ubuntu\home\me\project                  → distro=Ubuntu, /home/me/project
         *
         * Returns null for every non-WSL path (ordinary Windows drive paths, macOS/Linux paths),
         * so callers can use a non-null result as "this must run inside WSL".
         */
        fun parseWslPath(path: String): WslPath? {
            if (path.isBlank()) return null
            // Accept either slash style; the UNC prefix can arrive as \\ or //.
            val norm = path.replace('/', '\\')
            val lower = norm.lowercase()
            val prefix = when {
                lower.startsWith("\\\\wsl.localhost\\") -> "\\\\wsl.localhost\\"
                lower.startsWith("\\\\wsl\$\\") -> "\\\\wsl\$\\"
                else -> return null
            }
            val rest = norm.substring(prefix.length)
            val slash = rest.indexOf('\\')
            // Need both a distro segment and at least one path segment after it.
            if (slash <= 0 || slash == rest.length - 1) return null
            val distro = rest.substring(0, slash)
            val linuxPath = "/" + rest.substring(slash + 1).replace('\\', '/')
            return WslPath(distro, linuxPath)
        }
    }
}
