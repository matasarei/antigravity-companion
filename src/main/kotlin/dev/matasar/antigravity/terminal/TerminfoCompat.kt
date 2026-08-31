package dev.matasar.antigravity.terminal

import com.intellij.openapi.diagnostic.Logger
import dev.matasar.antigravity.settings.AntigravitySettings
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Works around a JediTerm gap that corrupts the `agy` prompt whenever you edit in the middle
 * of it.
 *
 * `agy` is a charmbracelet (Bubble Tea) TUI whose renderer picks the cheapest encoding for a
 * cursor move: backspace for one column, `CSI n D` for a few, and `CSI Z` — CBT, cursor
 * backward tabulation — to jump back by tab stops (it declares `CSI ? 5 W` at startup to put
 * a stop every 8 columns). JediTerm's `JediEmulator.processControlSequence` has no case for
 * final byte `Z`: it falls through to the default branch, logs "Unhandled Control Sequence"
 * and does nothing. Its `Terminal` interface has no `cursorBackwardTab` at all, even though
 * `JediTerminal$DefaultTabulator` already implements `Tabulator.previousTab(int)` — nothing
 * calls it.
 *
 * So the cursor silently stays put while `agy` believes it moved, and the next repaint (which
 * writes `<new char><rest of line>` and then walks back) lands N columns too far right,
 * painting the tail over itself. `agy`'s own buffer stays correct — only the screen is wrong,
 * which is why the text looks right again the moment you submit it. Both terminal engines are
 * affected: `TerminalStarter.createEmulator()` returns a `JediEmulator`, and the reworked
 * terminal's `TerminalStarterEx` extends it, so switching engines in Settings does not help.
 *
 * The lever we have is that `agy` enables the tab optimisation purely on `TERM` carrying an
 * `xterm` prefix — the terminfo database is never consulted, so cancelling `cbt` on an
 * `xterm`-named entry changes nothing. Launching it under a terminal name that is *not*
 * `xterm*` suppresses `CSI Z` outright. We therefore prefer a private entry that is
 * `xterm-256color` under a different name (identical capabilities, so anything `agy` spawns
 * still gets exactly the xterm key encodings it expects), and fall back to
 * `nsterm-256color`, which ships in the standard ncurses database, when we cannot compile
 * our own.
 *
 * The name is not free-form: it must keep a `-256color` component. `TERM` is the only signal
 * `agy` has for colour depth here, because the IDE terminal exports `TERM=xterm-256color` and
 * no `COLORTERM` (verified in `plugins/terminal/lib/terminal.jar`). `agy`'s detector
 * (`charmbracelet/colorprofile`) splits `TERM` on `-` and matches the parts against a fixed
 * table — `256color` is what selects the 256-colour profile; a bare `agyterm` matches nothing
 * and drops it to the 16-colour ANSI profile. The visible symptom of that degradation is
 * `agy`'s greys and slates collapsing onto the nearest ANSI colour, which is blue: body text
 * turns blue and inline-code chips pick up a dark navy background that is unreadable against
 * a light IDE theme. Only the first part is tested for the `xterm` prefix, so an
 * `<name>-256color` entry keeps full colour *and* keeps `CSI Z` suppressed.
 *
 * Measured cost of losing the optimisation: nothing. `CSI Z` is 3 bytes where the equivalent
 * `CSI n D` is 4–5, on a local PTY carrying a couple of KB per editing session.
 */
object TerminfoCompat {

    /**
     * Our private terminfo entry: `xterm-256color` under a non-`xterm` name. The `-256color`
     * suffix is load-bearing for colour depth, not decoration — see the class comment.
     */
    const val ENTRY_NAME: String = "agyterm-256color"

    /**
     * Shipped in the standard ncurses database, so usually present when `tic` is not. Keeps
     * the `-256color` suffix for the same reason [ENTRY_NAME] does.
     */
    private const val FALLBACK_TERM: String = "nsterm-256color"

    // Continuation lines must begin with whitespace — terminfo source is tab-sensitive.
    private val ENTRY_SOURCE: String =
        "$ENTRY_NAME|xterm-256color without cursor tab motions,\n" +
            "\tcbt@, cht@,\n" +
            "\tuse=xterm-256color,\n"

    private val log = Logger.getInstance(TerminfoCompat::class.java)

    private val lock = Any()

    @Volatile private var resolved: Boolean = false
    @Volatile private var term: String? = null

    /**
     * The value to export as `TERM` for `agy`, or null when we could not find a usable
     * terminal name (Windows, or no ncurses tooling) and should leave the IDE's `TERM` alone.
     *
     * Cached for the lifetime of the IDE. Resolution shells out to `infocmp`/`tic`, so
     * [warmUp] is normally called off the EDT at project open; a caller that beats it here
     * blocks for the length of one or two short process spawns, once.
     */
    fun resolvedTerm(): String? {
        if (resolved) return term
        synchronized(lock) {
            if (resolved) return term
            term = compute()
            resolved = true
            log.info("TerminfoCompat: agy will run with TERM=${term ?: "<unchanged>"}")
            return term
        }
    }

    /**
     * Resolve in the background so the first toolbar click does not pay for the lookup.
     *
     * Does nothing while the workaround is switched off. Resolution can compile a file into
     * the user's `~/.terminfo`, and a disabled feature has no business writing to `$HOME` —
     * least of all at project open, for a user who may never launch `agy` at all. The guard
     * lives here rather than in [compute] on purpose: [resolvedTerm] caches for the IDE's
     * lifetime, so refusing to resolve there would pin a `null` that survives the user
     * switching the setting back on. Enabling it calls this again (see the settings panel);
     * failing that, the launch path resolves lazily and pays for one or two process spawns
     * once.
     */
    fun warmUp() {
        if (!AntigravitySettings.getInstance().fixPromptCursorMovement) return
        resolvedTerm()
    }

    private fun compute(): String? {
        // Windows has no terminfo and no `xterm`-prefixed TERM to begin with.
        if (AntigravitySettings.isWindows()) return null

        if (entryExists(ENTRY_NAME)) return ENTRY_NAME
        if (installEntry() && entryExists(ENTRY_NAME)) return ENTRY_NAME
        if (entryExists(FALLBACK_TERM)) return FALLBACK_TERM

        log.info("TerminfoCompat: no usable terminfo entry; leaving TERM as the IDE set it")
        return null
    }

    /** True when ncurses can resolve [name] — `infocmp` exits 0 only for a known entry. */
    private fun entryExists(name: String): Boolean =
        run("infocmp", "-x", name) == 0

    /**
     * Compiles [ENTRY_SOURCE] into the user's private `~/.terminfo`, which ncurses searches
     * automatically. Nothing outside the user's home is touched, and anything `agy` runs
     * locally resolves the entry the same way `agy` itself does.
     */
    private fun installEntry(): Boolean {
        var source: File? = null
        return try {
            source = Files.createTempFile("agyterm", ".terminfo").toFile()
            source.writeText(ENTRY_SOURCE)
            val target = File(System.getProperty("user.home"), ".terminfo")
            val exit = run("tic", "-x", "-o", target.absolutePath, source.absolutePath)
            if (exit != 0) log.info("TerminfoCompat: tic exited $exit; falling back")
            exit == 0
        } catch (e: Exception) {
            log.info("TerminfoCompat: could not compile the $ENTRY_NAME entry", e)
            false
        } finally {
            source?.delete()
        }
    }

    /**
     * Runs an ncurses tool and returns its exit code, or a non-zero sentinel if it could not
     * be started. A GUI-launched IDE on macOS inherits launchd's sparse PATH, so we retry at
     * the conventional absolute location before giving up.
     */
    private fun run(tool: String, vararg args: String): Int {
        for (executable in listOf(tool, "/usr/bin/$tool")) {
            try {
                val process = ProcessBuilder(listOf(executable) + args)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return FAILED
                }
                return process.exitValue()
            } catch (_: Exception) {
                // Not on PATH / not at that location — try the next candidate.
            }
        }
        return FAILED
    }

    private const val FAILED: Int = -1
}
