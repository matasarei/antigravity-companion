<p align="center">
  <img src="src/main/resources/META-INF/pluginIcon.svg" width="120" alt="Antigravity Companion plugin icon">
</p>

# Antigravity Companion for JetBrains IDEs

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/31899?label=JetBrains%20Marketplace)](https://plugins.jetbrains.com/plugin/31899-antigravity-companion)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/31899)](https://plugins.jetbrains.com/plugin/31899-antigravity-companion)
[![GitHub release](https://img.shields.io/github/v/release/matasarei/antigravity-companion)](https://github.com/matasarei/antigravity-companion/releases)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2023.2%2B-blue)](https://plugins.jetbrains.com/docs/intellij/welcome.html)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

A JetBrains plugin that bridges the [Antigravity](https://antigravity.google) CLI
(`agy`) with JetBrains IDEs (IntelliJ IDEA, PhpStorm, WebStorm, PyCharm, GoLand,
and other IntelliJ-based IDEs) so the agent can see your active file, selection,
open tabs, and inspection diagnostics on demand.

## Features

The plugin exposes four MCP tools to `agy`:

| Tool | What it returns |
| --- | --- |
| `ide_get_active_editor` | File path, language, cursor (1-based), selection range and text, full file content |
| `ide_get_open_files` | Absolute paths of every file currently open in an editor tab |
| `ide_get_diagnostics` | Inspections, syntax errors, and warnings for a file (or the active editor) |
| `ide_open_file` | Open a file at an optional line/column |

When you click the **Open Antigravity CLI** toolbar button, `agy` launches in
an IDE-embedded terminal already wired to the current project — no manual
configuration steps. By default the button focuses the existing *Antigravity*
terminal tab if one is open; toggle *Always open a new tab* in settings to
spawn a fresh session on every click.

## Requirements

- A JetBrains IDE built on IntelliJ Platform 232+ (PhpStorm, IntelliJ IDEA,
  WebStorm, GoLand, RustRover, … — anything from 2023.2 through the current
  release).
- The Antigravity CLI installed locally. The plugin will look for `agy` (or
  `agy.exe` on Windows) on `$PATH`, then in `~/.local/bin`, `/opt/homebrew/bin`,
  and `/usr/local/bin`. You can override the location in the plugin settings.
- macOS, Linux, or Windows. The plugin's stdio bridge uses the IDE's bundled
  JBR (Java) — no external tools like `nc` are required.

## Installation

### From a release ZIP

1. Grab or build an `antigravity-companion-<version>.zip` (see
   [Building from source](#building-from-source) if you don't have one).
2. In the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Pick the ZIP and restart when prompted.

### From source

See [Building from source](#building-from-source).

## Configuration

Open **Settings → Tools → Antigravity Companion**.

- **Path to agy executable** — absolute path to the `agy` binary the plugin
  should launch. Leave blank to auto-detect (`$PATH` + common install dirs).
  The settings panel tells you which path it currently resolves to.
- **Always open a new tab** — when off (default), clicking the toolbar focuses
  the existing *Antigravity* terminal tab if one is open; when on, every click
  spawns a fresh `agy` session.
- **Fix prompt editing in the IDE terminal** — on by default (not shown on
  Windows). Launches `agy` under a non-`xterm` terminal name so it stops
  emitting `CSI Z`, which the IDE's terminal emulator ignores. See
  [Prompt editing workaround](#prompt-editing-workaround). Turn it off if you
  run `ssh` or containers from inside `agy` and need `TERM` passed through
  unchanged.
- **Keyboard shortcut** — no shortcut is bound by default (no chord is safe
  across every bundled keymap). Click *Configure shortcut in Keymap settings…*
  in the panel, or open **Settings → Keymap** directly and search for
  *Open Antigravity CLI*, to assign one. Multiple parallel `agy` sessions are
  still supported regardless of how the action is triggered.

No other configuration is required. The plugin manages
`~/.gemini/config/mcp_config.json` on its own and cleans up after itself when
projects close.

## Usage

1. Open a project and the file you want `agy` to see.
2. Click the lightning ⚡ toolbar button (top-right) or invoke
   **Open Antigravity CLI** from *Find Action* (`⇧⌘A` / `Ctrl+Shift+A`).
3. A terminal tab opens with `agy` running. Ask it about your code — when it
   needs context it will call the plugin's MCP tools transparently.

A useful first prompt: *"What file am I looking at? Use ide\_get\_active\_editor."*

## How it works

`agy` reads MCP server definitions from `~/.gemini/config/mcp_config.json`. On
project open the plugin:

1. Starts a small JSON-RPC server on `127.0.0.1:<random>` (line-delimited MCP
   over a plain TCP socket).
2. Writes a per-project bridge script —
   `jetbrains-mcp-bridge-<productCode>-<projectHash>.sh` on Unix or `.bat` on
   Windows — under `~/.gemini/antigravity-cli/`. `<productCode>` is the
   JetBrains IDE code (`iu`, `ps`, `ws`, `py`, `go`, …) so two IDEs opening
   the same project don't collide. The script uses the IDE's bundled JBR to
   run a tiny pure-Java stdio↔TCP relay, so `agy` (which speaks stdio MCP)
   can reach the in-IDE TCP server with no external dependencies.
3. Merges its entry into `mcp_config.json` under
   `mcpServers.jetbrains-companion-<productCode>-<projectHash>` (preserving
   any other entries).

On project close it removes the config entry and deletes the bridge script.

```
┌────────────────────┐   stdio    ┌──────────────────┐   TCP    ┌─────────────────┐
│ agy CLI (terminal) │ ─────────► │ bridge script    │ ───────► │ Plugin MCP svr  │
│                    │ ◄───────── │ (JBR StdioBridge)│ ◄─────── │ (in JetBrains)  │
└────────────────────┘            └──────────────────┘          └─────────────────┘
```

## Where things live

| Path | Purpose |
| --- | --- |
| `~/.gemini/config/mcp_config.json` | The plugin merges/removes its `jetbrains-companion-<productCode>-<projectHash>` entry here. Other entries are preserved. On register, the plugin also removes any legacy keys for **this project's** `projectHash` (e.g. `phpstorm-companion-<projectHash>`) left behind by older plugin versions; legacy entries belonging to other projects are not touched. |
| `~/.gemini/antigravity-cli/jetbrains-mcp-bridge-<productCode>-<projectHash>.{sh,bat}` | Auto-generated bridge script; deleted on project close. Do not edit by hand. |
| `~/.gemini/antigravity-cli/cli.log` *(symlink to latest run)* | `agy`'s log — useful when MCP traffic looks wrong. |
| IDE `idea.log` | Plugin logs. Look for `AntigravityCompanionService`: `MCP server listening on 127.0.0.1:<port>`, `MCP client connected from …`. |

## Troubleshooting

**The "Open Antigravity CLI" button shows a notification "agy executable
not found".**
Open **Settings → Tools → Antigravity Companion** and set the path explicitly,
or install `agy` to one of the auto-detected locations.

**`agy` answers "I don't have visibility into your IDE".**
The plugin probably isn't registered. Check:
1. **Settings → Plugins** — make sure the plugin is enabled.
2. `idea.log` for `MCP server listening on 127.0.0.1:<port>`.
3. `~/.gemini/config/mcp_config.json` contains a `jetbrains-companion-*` entry.

**`agy` log shows `Connection refused` or socket errors on startup.**
The IDE (or that project) closed while `agy` was still running. Quit `agy` and
relaunch it from the toolbar button — the plugin re-registers a fresh port
each time a project opens.

**`mcp_config.json` is corrupted.**
Delete it; the plugin will recreate it on the next project open.

**Editing in the middle of the `agy` prompt garbles the text.**
Make sure *Fix prompt editing in the IDE terminal* is enabled under
**Settings → Tools → Antigravity Companion**, then open a new Antigravity tab —
the terminal name is chosen when the session starts. See below for what is
actually going on.

### Prompt editing workaround

`agy` is a charmbracelet (Bubble Tea) TUI whose renderer picks the cheapest
encoding for each cursor move: backspace for one column, `CSI n D` for a few,
and `CSI Z` — CBT, *cursor backward tabulation* — to jump back by tab stops.
JediTerm, the emulator behind **both** the classic and the reworked JetBrains
terminal, parses `CSI Z` but does not implement it: it falls through to the
default branch, logs `Unhandled Control Sequence` and does nothing. The cursor
silently stays put while `agy` believes it moved, so the next repaint — which
writes `<new char><rest of line>` and then walks the cursor back — lands several
columns too far right and paints the tail of the line over itself.

`agy`'s own buffer is never wrong, which is why the text looks correct again the
moment you submit it. Switching terminal engines in Settings does not help
(`TerminalStarterEx` extends `TerminalStarter`, whose `createEmulator()` returns
a `JediEmulator`).

You can confirm the gap in any terminal — the `*` belongs at column 9:

```bash
printf 'ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\033[17G\033[Z*\n'
```

A working terminal prints `ABCDEFGH*JKLMNOPQRSTUVWXYZ`; JetBrains prints
`ABCDEFGHIJKLMNOP*RSTUVWXYZ`.

`agy` enables the tab optimisation purely on `TERM` carrying an `xterm` prefix —
the terminfo database is never consulted, so cancelling `cbt` on an
`xterm`-named entry changes nothing. The plugin therefore launches `agy` under a
terminal name that is *not* `xterm*`: a private `agyterm-256color` entry
(literally `xterm-256color` under a different name, compiled into `~/.terminfo`
on first use) so anything `agy` spawns still gets exactly the xterm key
encodings it expects, falling back to `nsterm-256color` from the standard
ncurses database when `tic` is unavailable. The byte cost is unmeasurable, and
alt-screen, kitty keyboard and bracketed paste all still negotiate.

The `-256color` suffix is load-bearing, not cosmetic. The IDE terminal exports
`TERM=xterm-256color` and no `COLORTERM`, so `TERM` is the only signal `agy` has
for colour depth. Its detector (`charmbracelet/colorprofile`) splits `TERM` on
`-` and matches the parts against a fixed table; a bare `agyterm` matches
nothing and drops `agy` to the 16-colour ANSI profile, where its greys and
slates collapse onto the nearest ANSI colour and the whole UI turns blue. Only
the first part is tested for the `xterm` prefix, so `<name>-256color` keeps the
full palette *and* keeps `CSI Z` suppressed. If you rename the entry, keep the
suffix.

`agy`'s light/dark palette is a separate matter and is not something the plugin
sets: it comes from `colorScheme` in `~/.gemini/antigravity-cli/settings.json`,
changed via `/settings` → *Color Scheme*. JediTerm does not answer OSC 11
background-colour queries, so `agy` cannot detect your IDE theme on its own —
pick the scheme that matches it.

The real fix belongs in JediTerm, where `Tabulator.previousTab(int)` is already
implemented in `JediTerminal$DefaultTabulator` and called by nothing.

## Building from source

### Prerequisites

- Git
- JDK 17 (Temurin, Zulu, or the IDE's bundled JBR all work). `JAVA_HOME` must
  point at it.
- Internet access on first build — the IntelliJ Gradle plugin downloads a
  sandboxed IDE matching `platformVersion` in `gradle.properties`.

### Steps

```bash
git clone https://github.com/matasarei/antigravity-companion.git
cd antigravity-companion
./gradlew buildPlugin
```

The signed (unsigned in dev) plugin archive will be at:

```
build/distributions/antigravity-companion-<version>.zip
```

Install it via **Settings → Plugins → ⚙ → Install Plugin from Disk…**.

### Useful Gradle tasks

```bash
./gradlew runIde           # launch a sandbox IDE with the plugin pre-loaded
./gradlew verifyPlugin     # IntelliJ Plugin Verifier — static checks
./gradlew clean buildPlugin # full rebuild
```

`./gradlew runIde` is the fastest dev loop: it starts a fresh sandbox IDE,
side-loads the plugin, and tears down on exit. Edit code, rerun, repeat.

### Project layout

```
src/main/
├── java/dev/matasar/antigravity/bridge/
│   └── StdioBridge.java                          ← pure-Java stdio↔TCP relay
└── kotlin/dev/matasar/antigravity/
    ├── action/StartSessionAction.kt              ← toolbar button
    ├── service/AntigravityCompanionService.kt    ← MCP server + tool impls
    ├── settings/AntigravitySettings.kt           ← persistent state
    ├── settings/AntigravitySettingsConfigurable.kt ← Settings panel
    ├── terminal/TerminfoCompat.kt                 ← JediTerm CSI Z workaround
    └── startup/AntigravityStartupActivity.kt     ← eager-init on project open
src/main/resources/
├── META-INF/plugin.xml                           ← plugin manifest
└── icons/                                        ← toolbar / plugin icons
build.gradle.kts, gradle.properties, settings.gradle.kts
```

## Known limitations

- **WSL2 on Windows.** When running the JetBrains IDE natively on Windows but working on a project located in a WSL2 path (`\\wsl.localhost\<distro>\...`), the plugin cannot spawn a Linux `agy` binary directly. The only supported workaround is to **run the JetBrains IDE itself inside WSL2** (e.g. using JetBrains Gateway or WSLg) and install the plugin and `agy` inside WSL.
- **Pull-based only.** The IDE does not push selection changes to `agy`
  mid-conversation; `agy` calls back over MCP when it decides it needs IDE
  state.
- **Multi-project.** Each open project registers a separate
  `jetbrains-companion-<productCode>-<projectHash>` entry. A running `agy` in
  workspace A will also see workspace B's tools in its catalog; only the live
  project's bridge succeeds, but the duplicate tool names can confuse the
  model.

## License

MIT — see [LICENSE](LICENSE).
