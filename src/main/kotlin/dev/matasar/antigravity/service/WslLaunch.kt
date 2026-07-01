package dev.matasar.antigravity.service

import dev.matasar.antigravity.settings.AntigravitySettings

/**
 * Builds the command line that starts `agy` inside a WSL2 distribution.
 *
 * This is the cross-OS scenario from issue #9: PhpStorm runs as a native Windows process, but the
 * project and the `agy` binary live inside WSL2 under `\\wsl.localhost\<distro>\...`. That path is
 * a Linux ELF binary — Windows `CreateProcess` cannot execute it directly, so a plain
 * `listOf(agyPath)` fails with "Failed to start". We instead route through `wsl.exe` so agy runs
 * inside the distro.
 *
 * Kept as a pure, IDE-free object so the (Windows-only) behaviour can be unit-tested on any OS.
 */
object WslLaunch {

    /**
     * Returns the wsl.exe argv to launch agy inside its distro, or null when [agyPath] is not a
     * WSL path (the caller then falls back to the normal Windows / macOS / Linux launch).
     *
     * When [workingDir] resolves to the same distro, agy is started in that Linux directory.
     */
    fun wslCommand(agyPath: String, workingDir: String?): List<String>? {
        val agy = AntigravitySettings.parseWslPath(agyPath) ?: return null
        val workLinux = workingDir
            ?.let { AntigravitySettings.parseWslPath(it) }
            ?.takeIf { it.distro.equals(agy.distro, ignoreCase = true) }
            ?.linuxPath
        // Login + interactive bash (`-l -i`) so the user's PATH from .profile/.bashrc is loaded
        // before agy starts — same reasoning as the macOS/Linux launch. `cd` into the project (when
        // it lives in the same distro); `exec` replaces bash so no zombie shell lingers. Each token
        // is a distinct argv entry, so wsl.exe hands the single `-c` string to bash verbatim.
        val cdPrefix = workLinux?.let { "cd ${shellEscape(it)} && " } ?: ""
        val inner = "${cdPrefix}exec ${shellEscape(agy.linuxPath)}"
        return listOf("wsl.exe", "-d", agy.distro, "bash", "-l", "-i", "-c", inner)
    }

    /** Single-quote a string for safe inclusion in a shell `-c` command. */
    private fun shellEscape(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}
