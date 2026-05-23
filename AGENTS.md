# AGENTS.md

Guidance for coding agents (and humans) working on this repository.

## What this is

A JetBrains plugin that bridges the [Antigravity](https://antigravity.google) CLI
(`agy`) with IntelliJ-based IDEs (PhpStorm primarily, but anything 2023.2+)
through the **Model Context Protocol (MCP)**.

- The plugin runs a local TCP MCP server inside the IDE process.
- A tiny pure-Java stdio↔TCP relay (`StdioBridge`) is invoked from a
  per-project script that the plugin generates.
- The plugin merges itself into `~/.gemini/config/mcp_config.json` so `agy`
  discovers it automatically.
- Four tools are exposed: `ide_get_active_editor`, `ide_get_open_files`,
  `ide_get_diagnostics`, `ide_open_file`.

Plugin id: `dev.matasar.antigravity-companion`. License: MIT.
End-user docs are in [`README.md`](README.md).

## Repository layout

```
src/main/
├── java/dev/matasar/antigravity/bridge/
│   └── StdioBridge.java                            ← pure-Java stdio↔TCP relay
└── kotlin/dev/matasar/antigravity/
    ├── action/StartSessionAction.kt                ← toolbar button
    ├── service/AntigravityCompanionService.kt      ← MCP server + tool implementations
    ├── settings/AntigravitySettings.kt             ← persistent app-level state (agy path)
    ├── settings/AntigravitySettingsConfigurable.kt ← Settings → Tools → Antigravity Companion
    └── startup/AntigravityStartupActivity.kt       ← eager-init the service on project open
src/main/resources/
├── META-INF/plugin.xml                             ← manifest, services, actions, change notes
└── icons/, pluginIcon.svg                          ← toolbar + plugin icons
build.gradle.kts, gradle.properties, settings.gradle.kts
LICENSE  README.md  AGENTS.md
```

There is intentionally no `agy-companion/` Go subproject, no `EditorContextListener`,
no `DiffViewerHandler`, no `ToolWindowFactory`, no Ktor. If you find yourself
adding any of these, stop and re-read the principles below.

## Core principles

1. **MCP, not ExtensionServerService.** The Codeium/Jetski "extension server"
   path in the `agy` binary is only initialized when `agy` is spawned as a
   subagent of the Antigravity desktop IDE (it reads proto-encoded init
   metadata from stdin). The standalone CLI returns
   `extension server client not configured` to any `ReconnectExtensionServer`
   call. **Do not try to revive that path.** All IDE↔agy communication runs
   over MCP.

2. **Pull-based context.** `agy` decides when it needs IDE state and calls
   our tools. The IDE does NOT push state changes. Resist adding editor
   listeners that try to push — that was the original (now removed) design and
   it doesn't fit MCP's request/response shape.

3. **Cross-platform via JBR.** The stdio bridge runs under the IDE's bundled
   JBR (`PathManager.getJarPathForClass` → `java -cp <jar> StdioBridge`). No
   `nc`, no Python, no platform binaries. `StdioBridge.java` must remain
   pure `java.io` + `java.net` — zero dependencies — so it can run with just
   the plugin's own JAR on the classpath.

4. **Fail visibly, never silently.** Every entry point that can fail must
   either succeed or surface a notification with a real cause. Specifically:
   - Each step in `AntigravityCompanionService.init {}` is wrapped in
     `runStep("label") { ... }` so a single failure cannot prevent the rest
     of the service (including the toolbar action) from working.
   - The toolbar action distinguishes three failure modes: bridge not
     written, `agy` not found, and spawn exception — each gets its own
     notification.
   - Tool implementations wrap exceptions in `toolError(...)` so MCP errors
     reach the model instead of dropping the connection.

5. **Use IntelliJ-provided APIs for paths, threading, and config.**
   - Plugin JAR location: `PathManager.getJarPathForClass(KClass.java)`.
     Never use `Class.getProtectionDomain().getCodeSource().getLocation()` —
     it returns null under `PluginClassLoader` and the failure mode is a
     silent no-op at runtime.
   - Editor reads: `ApplicationManager.runReadAction { ... }`.
   - Editor mutations / opens / dialogs: `ApplicationManager.invokeAndWait { ... }`
     on the EDT.
   - Terminal: `org.jetbrains.plugins.terminal.TerminalToolWindowManager`
     (the deprecated `TerminalView` was removed in newer platforms).
   - Settings persistence: `PersistentStateComponent` with `@State` and
     `@Storage`. Don't write our own XML/JSON to a custom file.

6. **mcp_config.json is shared state.** Always read-modify-write. Preserve
   every key under `mcpServers` that isn't ours. Use a unique entry name per
   (IDE, project) pair —
   `jetbrains-companion-<productCode>-<projectHash>` — so opening the same
   project in two JetBrains IDEs at once doesn't have them stomp each
   other's entry. Sweep legacy formats (`phpstorm-companion-*`,
   `jetbrains-companion-<projectHash>` without a productCode) on register so
   upgraders don't accumulate orphans. Clean up our key in `dispose()`.

7. **Keep the artifact small.** The IntelliJ Platform already ships
   kotlin-stdlib and most of what we need. Only `kotlinx-serialization-json`
   is bundled. `gradle.properties` has `kotlin.stdlib.default.dependency=false`
   to prevent the Kotlin Gradle plugin from auto-adding stdlib. Don't add
   dependencies casually — the slim 2.2 MB ZIP is a feature.

8. **Tool descriptions and `instructions` are LLM-facing prompts.** This is
   the part that's easy to underestimate. The model only knows when to call
   our tools because of the text we put in:
   - the `description` field on each `buildTool(...)` entry, and
   - the `instructions` field on the `initialize` response
     (`SERVER_INSTRUCTIONS` constant in the service's companion object).

   Write both assertively. A description that *reads* like reference
   documentation ("Returns the file path, language, …") gets ignored. A
   description that *directs* ("Call this BEFORE asking the user to paste
   code, whenever the user says any of: 'this code', 'my selection', …")
   actually changes behavior. If a user reports "the agent didn't call my
   tool when it obviously should have", the fix is almost always in these
   strings — not the wire protocol.

## Where to make common changes

### Add an MCP tool

1. In `AntigravityCompanionService.kt`:
   - Append a `buildTool(...)` entry to `toolDefinitions()`. The description
     must be **directive**, not reference-style — list the exact user phrases
     that should trigger the tool, and tell the model when NOT to fall back
     to asking the user. See the existing tools as templates. (See principle
     8 above.)
   - Add a branch in `invokeTool(name, args)` that dispatches to a private
     `read*` or mutate function.
   - Reads: use `read { ... }` (which wraps `runReadAction`). Mutations:
     use `ApplicationManager.invokeAndWait`.
   - If the new tool changes the "what should the agent prefer" balance,
     update `SERVER_INSTRUCTIONS` accordingly.
2. No plugin.xml change needed — tools are discovered dynamically by the
   client.
3. Smoke-test by running `./gradlew runIde`, opening a project, launching
   `agy`, and asking it to call the new tool by name and also via a natural-
   language prompt that *implies* the tool. Both should work.

### Add a setting

1. Add a field to `AntigravitySettings.State`.
2. Add a getter/setter on `AntigravitySettings`.
3. In `AntigravitySettingsConfigurable.createComponent()` add the UI row, and
   wire `isModified() / apply() / reset()`.
4. Read from `AntigravitySettings.getInstance().<field>` in the service.

### Add a top-level action (toolbar / menu)

1. Create a class in `dev.matasar.antigravity.action` extending `AnAction`.
2. Register it in `plugin.xml` under `<actions>` with an `<add-to-group>`.
3. Look up services with `project.getService(...)`. Guard against nulls — a
   service may have failed to initialize.

## Build & test loop

| Command | What it does |
| --- | --- |
| `./gradlew buildPlugin` | Produces `build/distributions/antigravity-companion-<version>.zip` |
| `./gradlew runIde` | Launches a sandbox IDE with the plugin pre-loaded — fastest dev loop |
| `./gradlew verifyPlugin` | IntelliJ Plugin Verifier — static checks against the configured platform versions |
| `./gradlew clean buildPlugin` | Full rebuild |
| `./gradlew signPlugin` | Signs the ZIP (needs `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` env vars) |

For end-to-end testing without rebuilding the IDE plugin every cycle, you can
script the same MCP wire as the plugin by running a small Python mock server,
generating a bridge script that invokes the plugin's `StdioBridge` (via
`PathManager` resolution against the built JAR), registering it in
`mcp_config.json`, and running `agy -p "..."`. The plugin was developed this
way; see the smoke-test snippets in git history if you need a template.

## Updating for a new IDE version

Whenever JetBrains releases a new major (e.g. 2025.3 → build 253, or 2026.1 →
build 261), do this:

### 1. Pick the new target version

JetBrains build numbers map predictably: `<year-last-two><major>` plus minor.
2025.3 → `253`. 2026.1 → `261`.

The plugin currently supports:

```properties
# gradle.properties
platformVersion = 2023.2.5
```

```xml
<!-- plugin.xml -->
<idea-version since-build="232" until-build="262.*"/>
```

### 2. Bump the compile-time platform

Edit `gradle.properties`:

```properties
platformVersion = 2025.3      # or any later release you want APIs from
```

This is the IDE the IntelliJ Gradle plugin will download for the build. Picking
a newer one gives you newer APIs at compile time but does NOT change which
IDEs the plugin actually installs in — that's `since-build` / `until-build`.

You usually want `platformVersion` to be the **oldest** version whose APIs
you still need, so the plugin compiles against the smallest viable surface.
Only bump it when you reach for an API that doesn't exist in the older one.

### 3. Bump `until-build` if needed

`until-build` is the highest IDE build the plugin is *certified* against. If
the value already covers the new major (e.g. `262.*` covers everything up to
2026.2), no change is needed.

If you need to widen it, edit `plugin.xml`:

```xml
<idea-version since-build="232" until-build="272.*"/>
```

`272.*` would cover everything up to and including 2027.2.

### 4. Bump `since-build` only if dropping support

If you intentionally drop a JetBrains major (e.g. stop supporting 2023.2),
move `since-build` up to the next major's number (e.g. `233`). Most version
bumps don't need this.

### 5. Update the plugin version

`gradle.properties` → `pluginVersion = 1.1.0` (or whatever bump is
appropriate). Use semver. Update `<change-notes>` in `plugin.xml` with a new
top entry describing what changed.

### 6. Run the verifier against the new and old targets

```bash
./gradlew verifyPlugin
```

The verifier catches:
- Removed/renamed APIs on the new IDE.
- Internal APIs you accidentally depend on.
- Plugin descriptor problems.

Fix every reported error, then re-run. Warnings are usually fine but worth
reading.

### 7. Rebuild and smoke-test

```bash
./gradlew clean buildPlugin
```

Install the new ZIP into the new IDE major. Run `agy` once and ask it
*"call ide_get_active_editor"* to confirm the full path works end-to-end.
If you see "agy executable not found", check `Settings → Tools → Antigravity
Companion`. If the button does nothing, check `idea.log` for an
`AntigravityCompanionService` stack — every recoverable failure now logs there.

### Quick checklist

```
[ ] gradle.properties:   bump pluginVersion (always)
[ ] gradle.properties:   bump platformVersion if you need newer APIs
[ ] plugin.xml:          bump until-build if new major exceeds it
[ ] plugin.xml:          add <change-notes> entry
[ ] ./gradlew verifyPlugin — all errors resolved
[ ] ./gradlew clean buildPlugin
[ ] Manual smoke: install in target IDE, run agy, call a tool
[ ] Tag the release in git
```

## Known constraints

- **Pull-based only.** Don't add editor/selection listeners to push state to
  `agy` mid-conversation. MCP doesn't have a server-push primitive that the
  Antigravity CLI consumes for this.
- **Multi-project caveat.** Each open project registers a unique
  `jetbrains-companion-<productCode>-<projectHash>` entry. An `agy` instance
  in workspace A will see workspace B's bridge command in its tool list too;
  the script fails for the dead one but the duplicate tool names can confuse
  the model.
  Acceptable for v1; if it becomes annoying, switch to a single shared MCP
  entry whose target is chosen at connect time.
- **No marketplace.** The plugin is not currently published to JetBrains
  Marketplace. Don't add publish-flow code (`publishPlugin` task,
  marketplace metadata fields) unless the project changes course.
