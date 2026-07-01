package dev.matasar.antigravity.service

import dev.matasar.antigravity.settings.AntigravitySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the WSL2 launch logic from issue #9. These are pure-logic tests — no IDE, no Windows —
 * so they run on any OS (including the macOS dev machine).
 */
class WslLaunchTest {

    @Test
    fun `parses wsl_localhost UNC path into distro and linux path`() {
        val parsed = AntigravitySettings.parseWslPath("\\\\wsl.localhost\\Ubuntu\\home\\arty\\.local\\bin\\agy")
        assertEquals("Ubuntu", parsed?.distro)
        assertEquals("/home/arty/.local/bin/agy", parsed?.linuxPath)
    }

    @Test
    fun `parses legacy wsl$ prefix and forward slashes`() {
        val parsed = AntigravitySettings.parseWslPath("//wsl\$/Debian/usr/local/bin/agy")
        assertEquals("Debian", parsed?.distro)
        assertEquals("/usr/local/bin/agy", parsed?.linuxPath)
    }

    @Test
    fun `non-wsl paths are not treated as wsl`() {
        assertNull(AntigravitySettings.parseWslPath("C:\\Users\\arty\\agy.exe"))
        assertNull(AntigravitySettings.parseWslPath("/home/arty/.local/bin/agy"))
        assertNull(AntigravitySettings.parseWslPath("/opt/homebrew/bin/agy"))
        // Distro root with no path after it is not a runnable target.
        assertNull(AntigravitySettings.parseWslPath("\\\\wsl.localhost\\Ubuntu"))
        assertNull(AntigravitySettings.parseWslPath(""))
    }

    @Test
    fun `builds wsl_exe invocation that cd's into the project and execs agy`() {
        val cmd = WslLaunch.wslCommand(
            agyPath = "\\\\wsl.localhost\\Ubuntu\\home\\arty\\.local\\bin\\agy",
            workingDir = "\\\\wsl.localhost\\Ubuntu\\home\\arty\\projects\\test1",
        )
        assertEquals(
            listOf(
                "wsl.exe", "-d", "Ubuntu", "bash", "-l", "-i", "-c",
                "cd '/home/arty/projects/test1' && exec '/home/arty/.local/bin/agy'",
            ),
            cmd,
        )
    }

    @Test
    fun `omits cd when project lives in a different distro`() {
        val cmd = WslLaunch.wslCommand(
            agyPath = "\\\\wsl.localhost\\Ubuntu\\home\\arty\\.local\\bin\\agy",
            workingDir = "\\\\wsl.localhost\\Debian\\home\\arty\\project",
        )
        assertEquals(
            listOf("wsl.exe", "-d", "Ubuntu", "bash", "-l", "-i", "-c", "exec '/home/arty/.local/bin/agy'"),
            cmd,
        )
    }

    @Test
    fun `returns null for a non-wsl agy path so caller uses the normal launch`() {
        assertNull(WslLaunch.wslCommand("/opt/homebrew/bin/agy", "/Users/arty/project"))
        assertNull(WslLaunch.wslCommand("C:\\tools\\agy.exe", "C:\\projects\\app"))
    }
}
