# Antigravity Companion for JetBrains IDEs

A JetBrains plugin that bridges the [Antigravity](https://antigravity.google) CLI
(`agy`) with PhpStorm (and other IntelliJ-based IDEs) so the agent can see your
active file, selection, open tabs, and inspection diagnostics on demand.

## Features

The plugin exposes four MCP tools to `agy`:

| Tool | What it returns |
| --- | --- |
| `ide_get_active_editor` | File path, language, cursor (1-based), selection range and text, full file content |
| `ide_get_open_files` | Absolute paths of every file currently open in an editor tab |
| `ide_get_diagnostics` | Inspections, syntax errors, and warnings for a file (or the active editor) |
| `ide_open_file` | Open a file at an optional line/column |

When you click the **Start Antigravity Session** toolbar button, `agy` launches
in an IDE-embedded terminal already wired to the current project — no manual
configuration steps.

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

No other configuration is required. The plugin manages
`~/.gemini/config/mcp_config.json` on its own and cleans up after itself when
projects close.

## Usage

1. Open a project and the file you want `agy` to see.
2. Click the lightning ⚡ toolbar button (top-right) or invoke
   **Start Antigravity Session** from *Find Action* (`⇧⌘A` / `Ctrl+Shift+A`).
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
| `~/.gemini/config/mcp_config.json` | The plugin merges/removes its `jetbrains-companion-<productCode>-<projectHash>` entry here. Other entries are preserved. Stale `phpstorm-companion-*` keys from old plugin versions are swept on register. |
| `~/.gemini/antigravity-cli/jetbrains-mcp-bridge-<productCode>-<projectHash>.{sh,bat}` | Auto-generated bridge script; deleted on project close. Do not edit by hand. |
| `~/.gemini/antigravity-cli/cli.log` *(symlink to latest run)* | `agy`'s log — useful when MCP traffic looks wrong. |
| IDE `idea.log` | Plugin logs. Look for `AntigravityCompanionService`: `MCP server listening on 127.0.0.1:<port>`, `MCP client connected from …`. |

## Troubleshooting

**The "Start Antigravity Session" button shows a notification "agy executable
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
    └── startup/AntigravityStartupActivity.kt     ← eager-init on project open
src/main/resources/
├── META-INF/plugin.xml                           ← plugin manifest
└── icons/                                        ← toolbar / plugin icons
build.gradle.kts, gradle.properties, settings.gradle.kts
```

## Known limitations

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
