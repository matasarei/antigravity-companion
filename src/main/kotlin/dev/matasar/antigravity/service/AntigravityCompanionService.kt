package dev.matasar.antigravity.service

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import dev.matasar.antigravity.bridge.StdioBridge
import dev.matasar.antigravity.settings.AntigravitySettings
import dev.matasar.antigravity.settings.AntigravitySettingsConfigurable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

@Service(Service.Level.PROJECT)
class AntigravityCompanionService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(AntigravityCompanionService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prettyJson = Json { prettyPrint = true }

    private val projectHash: String =
        Integer.toHexString((project.basePath ?: project.name).hashCode())
    private val mcpEntryName = "phpstorm-companion-$projectHash"

    var port: Int = 0
        private set

    private var serverSocket: ServerSocket? = null
    private var bridgeScript: File? = null
    private var lastSpawnTime: Long = 0

    init {
        // Each step is wrapped so that one failure cannot prevent the rest of the service —
        // including the toolbar action — from working. Errors are logged; the toolbar surfaces
        // a notification on use if the bridge is unusable.
        runStep("start MCP server") { startMcpServer() }
        runStep("write MCP bridge script") { writeBridgeScript() }
        runStep("register in mcp_config.json") { registerInMcpConfig() }
    }

    private inline fun runStep(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            log.error("AntigravityCompanionService: $label failed", e)
        }
    }

    // ---------------------------------------------------------------- Public API

    fun triggerNewTerminalSession() {
        val now = System.currentTimeMillis()
        if (now - lastSpawnTime < 1000) {
            log.info("Suppressed duplicate terminal spawn")
            return
        }
        lastSpawnTime = now

        if (bridgeScript == null) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Antigravity Companion")
                .createNotification(
                    "Antigravity Companion failed to initialise",
                    "The MCP bridge script could not be written when this project opened. See idea.log and restart the project.",
                    NotificationType.ERROR,
                )
                .notify(project)
            return
        }

        val agyPath = AntigravitySettings.getInstance().resolvedAgyPath()
        log.info("triggerNewTerminalSession: resolved agy path = '$agyPath'")
        if (agyPath.isBlank()) {
            notifyMissingAgy()
            return
        }

        ApplicationManager.getApplication().invokeLater {
            try {
                val tm = org.jetbrains.plugins.terminal.TerminalToolWindowManager.getInstance(project)
                // Non-deprecated path: configure a TerminalTabState with the working dir, tab
                // name, and the agy command, then hand it to the default terminal runner. Agy
                // becomes the tab's process directly — no intermediate shell to type into,
                // so the tab closes cleanly when the user exits agy.
                val tabState = org.jetbrains.plugins.terminal.TerminalTabState().apply {
                    myTabName = "Antigravity"
                    myWorkingDirectory = project.basePath
                    myShellCommand = listOf(agyPath)
                }
                tm.createNewSession(tm.terminalRunner, tabState)
                log.info("Spawned agy terminal session for project $projectHash (binary: $agyPath)")
            } catch (e: Exception) {
                log.error("Failed to spawn agy terminal session", e)
                notifySpawnFailure(e)
            }
        }
    }

    private fun notifySpawnFailure(e: Throwable) {
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup("Antigravity Companion")
        group.createNotification(
            "Could not open Antigravity terminal",
            "${e.javaClass.simpleName}: ${e.message ?: "(no message)"} — see idea.log for the stack trace.",
            NotificationType.ERROR,
        ).notify(project)
    }

    private fun notifyMissingAgy() {
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup("Antigravity Companion")
        val notification = group.createNotification(
            "agy executable not found",
            "Set the path to the agy CLI in Settings → Tools → Antigravity Companion.",
            NotificationType.WARNING,
        )
        notification.addAction(object : NotificationAction("Configure…") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    project, AntigravitySettingsConfigurable::class.java,
                )
                n.expire()
            }
        })
        notification.notify(project)
    }


    // ---------------------------------------------------------------- MCP TCP server

    private fun startMcpServer() {
        try {
            val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            port = ss.localPort
            log.info("MCP server listening on 127.0.0.1:$port (project $projectHash)")
            scope.launch {
                while (!ss.isClosed) {
                    val client = try {
                        ss.accept()
                    } catch (e: IOException) {
                        if (ss.isClosed) break
                        log.warn("MCP accept failed: ${e.message}")
                        continue
                    }
                    launch { handleClient(client) }
                }
            }
        } catch (e: Exception) {
            log.error("Failed to start MCP server", e)
        }
    }

    private fun handleClient(socket: Socket) {
        log.info("MCP client connected from ${socket.remoteSocketAddress}")
        socket.tcpNoDelay = true
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val rawWriter = s.getOutputStream().bufferedWriter(Charsets.UTF_8)
                val writeLock = Any()
                val send: (String) -> Unit = { json ->
                    synchronized(writeLock) {
                        rawWriter.write(json)
                        rawWriter.write("\n")
                        rawWriter.flush()
                    }
                }
                while (true) {
                    val line = try {
                        reader.readLine()
                    } catch (e: IOException) {
                        null
                    } ?: break
                    if (line.isBlank()) continue
                    val parsed = try {
                        Json.parseToJsonElement(line)
                    } catch (e: Exception) {
                        log.warn("Bad JSON from MCP client: ${e.message}")
                        continue
                    }
                    when (parsed) {
                        is JsonObject -> handleRequest(parsed, send)
                        is JsonArray -> parsed.forEach {
                            if (it is JsonObject) handleRequest(it, send)
                        }
                        else -> log.warn("Unexpected JSON-RPC payload: $parsed")
                    }
                }
            }
        } catch (e: Exception) {
            log.info("MCP client closed: ${e.message}")
        }
    }

    private fun handleRequest(req: JsonObject, send: (String) -> Unit) {
        val method = req["method"]?.jsonPrimitive?.contentOrNull
        val id = req["id"]
        val params = (req["params"] as? JsonObject) ?: JsonObject(emptyMap())
        val isNotification = id == null

        fun ok(result: JsonElement) {
            if (isNotification) return
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id!!)
                put("result", result)
            }.toString())
        }

        fun err(code: Int, message: String) {
            if (isNotification) return
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id!!)
                put("error", buildJsonObject {
                    put("code", code)
                    put("message", message)
                })
            }.toString())
        }

        try {
            when (method) {
                "initialize" -> ok(buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    put("serverInfo", buildJsonObject {
                        put("name", "Antigravity Companion (PhpStorm)")
                        put("version", PLUGIN_VERSION)
                    })
                    put("capabilities", buildJsonObject {
                        put("tools", buildJsonObject {
                            put("listChanged", false)
                        })
                    })
                    put("instructions", SERVER_INSTRUCTIONS)
                })

                "notifications/initialized",
                "notifications/cancelled",
                "notifications/progress" -> { /* notifications: never reply */ }

                "ping" -> ok(JsonObject(emptyMap()))

                "tools/list" -> ok(buildJsonObject {
                    put("tools", JsonArray(toolDefinitions()))
                })

                "tools/call" -> {
                    val name = params["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val args = (params["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
                    ok(invokeTool(name, args))
                }

                "prompts/list" -> ok(buildJsonObject {
                    put("prompts", JsonArray(emptyList()))
                })

                "resources/list" -> ok(buildJsonObject {
                    put("resources", JsonArray(emptyList()))
                })

                "resources/templates/list" -> ok(buildJsonObject {
                    put("resourceTemplates", JsonArray(emptyList()))
                })

                null -> err(-32600, "Missing method")
                else -> err(-32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            log.warn("MCP method $method failed", e)
            err(-32603, "Internal error: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- Tool catalog

    private fun toolDefinitions(): List<JsonObject> = listOf(
        buildTool(
            name = "ide_get_active_editor",
            description = """
                Returns the absolute file path, language, cursor position (1-based line/column),
                selection range, selection text, and the FULL CONTENT of the file currently focused
                in the user's JetBrains IDE.

                **Call this tool BEFORE asking the user to paste code, share a file path, or clarify
                what they mean** whenever the user's request implies on-screen context. Specifically,
                always call it when the user says any of:
                  - "the current file" / "this file" / "the file I'm in" / "what I'm looking at"
                  - "the selected code" / "the selection" / "my selection" / "this code" /
                    "this snippet" / "this block" / "highlighted code"
                  - "this function" / "this method" / "this class" / "this variable"
                  - bare requests like "explain this", "review this", "refactor this", "fix this"
                    where "this" has no other antecedent

                The returned content includes the full file text and any selection range, so you do
                NOT need to call a separate read_file tool afterwards for the same file in the same
                turn.

                If this tool returns "No active editor in PhpStorm right now.", the user has no file
                focused — only then should you ask them which file/snippet they mean.
            """.trimIndent(),
        ),
        buildTool(
            name = "ide_get_open_files",
            description = """
                Returns absolute paths of every file currently open in an editor tab in the user's
                JetBrains IDE.

                Call this whenever the user asks about "open files", "open tabs", "files I have open",
                "what I'm working on", "the files in my session", or wants to operate across multiple
                editor tabs without naming them.

                Pair with ide_get_active_editor when you need both context (active file) and breadth
                (everything else they have open).
            """.trimIndent(),
        ),
        buildTool(
            name = "ide_get_diagnostics",
            description = """
                Returns inspections, syntax errors, and warnings the JetBrains IDE currently shows
                for a file (PHPStan/Psalm/PHPCS findings, missing classes, deprecated APIs, etc.).

                Call this BEFORE running external linters, test suites, or static analyzers when the
                user asks any of:
                  - "what's wrong with this file" / "are there errors" / "any warnings"
                  - "why does the IDE flag this" / "what is this red underline"
                  - "fix the warnings" / "clean up this file"

                The IDE's diagnostics are usually faster and more accurate than re-running tooling
                from the shell, and they cover inspections specific to JetBrains (e.g. PhpStorm's
                type inference) that command-line tools miss.

                If file_path is omitted, the active editor is used. To inspect a different file, pass
                its absolute path.
            """.trimIndent(),
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("file_path", buildJsonObject {
                        put("type", "string")
                        put("description", "Absolute path of the file to inspect. Omit to use the active editor.")
                    })
                })
            },
        ),
        buildTool(
            name = "ide_open_file",
            description = """
                Opens a file in the user's JetBrains IDE, optionally scrolling to a 1-based line and
                column. The user will visually see the file appear in their editor.

                Use this whenever you want to surface a file to the user — e.g. after you discover
                the right file to look at, after generating new code, when guiding them through a
                change, or when explaining something that benefits from them seeing the file open.

                Prefer this over only quoting the path in chat: opening the file removes a manual
                navigation step for the user.
            """.trimIndent(),
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", JsonArray(listOf(JsonPrimitive("file_path"))))
                put("properties", buildJsonObject {
                    put("file_path", buildJsonObject {
                        put("type", "string")
                        put("description", "Absolute path of the file to open.")
                    })
                    put("line", buildJsonObject {
                        put("type", "integer")
                        put("description", "1-based line number to scroll to.")
                    })
                    put("column", buildJsonObject {
                        put("type", "integer")
                        put("description", "1-based column number.")
                    })
                })
            },
        ),
    )

    private fun buildTool(
        name: String,
        description: String,
        inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(emptyMap()))
        },
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", inputSchema)
    }

    private fun invokeTool(name: String, args: JsonObject): JsonObject {
        return try {
            val text = when (name) {
                "ide_get_active_editor" -> readActiveEditor()
                "ide_get_open_files" -> readOpenFiles()
                "ide_get_diagnostics" -> readDiagnostics(args["file_path"]?.jsonPrimitive?.contentOrNull)
                "ide_open_file" -> {
                    val path = args["file_path"]?.jsonPrimitive?.contentOrNull
                        ?: return toolError("file_path is required")
                    openFile(path, args["line"]?.jsonPrimitive?.intOrNull, args["column"]?.jsonPrimitive?.intOrNull)
                }
                else -> return toolError("Unknown tool: $name")
            }
            buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                })
            }
        } catch (e: Exception) {
            log.warn("Tool $name failed", e)
            toolError("Tool error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun toolError(message: String): JsonObject = buildJsonObject {
        put("isError", true)
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", message)
            })
        })
    }

    private fun <T> read(block: () -> T): T =
        ApplicationManager.getApplication().runReadAction(Computable(block))

    private fun readActiveEditor(): String = read {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
            ?: return@read "No active editor in PhpStorm right now."
        val doc = editor.document
        val file = FileDocumentManager.getInstance().getFile(doc)
        val path = file?.path ?: "(unsaved buffer)"
        val language = file?.fileType?.name ?: "Unknown"
        val pos = editor.caretModel.logicalPosition
        val sel = editor.selectionModel

        buildString {
            appendLine("file_path: $path")
            appendLine("language: $language")
            appendLine("total_lines: ${doc.lineCount}")
            appendLine("cursor_line: ${pos.line + 1}")
            appendLine("cursor_column: ${pos.column + 1}")
            if (sel.hasSelection()) {
                val startLine = doc.getLineNumber(sel.selectionStart) + 1
                val endLine = doc.getLineNumber(sel.selectionEnd) + 1
                appendLine("selection_start_line: $startLine")
                appendLine("selection_end_line: $endLine")
                appendLine("--- selection ---")
                val selText = sel.selectedText ?: ""
                append(selText)
                if (!selText.endsWith("\n")) appendLine()
                appendLine("--- end selection ---")
            } else {
                appendLine("selection: (none)")
            }
            appendLine("--- file content ---")
            append(doc.text)
        }
    }

    private fun readOpenFiles(): String = read {
        val files = FileEditorManager.getInstance(project).openFiles.map { it.path }
        if (files.isEmpty()) "No files are open in PhpStorm."
        else files.joinToString("\n")
    }

    private fun readDiagnostics(filePath: String?): String = read {
        val editor: Editor? = if (filePath == null) {
            FileEditorManager.getInstance(project).selectedTextEditor
        } else {
            val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: return@read "File not found: $filePath"
            val doc = FileDocumentManager.getInstance().getDocument(vf)
                ?: return@read "Document not loaded for: $filePath"
            EditorFactory.getInstance().getEditors(doc, project).firstOrNull()
        }
        if (editor == null) {
            return@read "No editor available for diagnostics."
        }
        val doc = editor.document
        val mm = DocumentMarkupModel.forDocument(doc, project, false)
            ?: return@read "No markup model on this document."
        val items = mm.allHighlighters.mapNotNull { h ->
            val tooltip = h.errorStripeTooltip?.toString() ?: return@mapNotNull null
            val line = try { doc.getLineNumber(h.startOffset) + 1 } catch (_: Exception) { 1 }
            val severity = when {
                tooltip.contains("error", ignoreCase = true) -> "ERROR"
                tooltip.contains("warning", ignoreCase = true) -> "WARNING"
                else -> "INFO"
            }
            "$severity\tline $line\t${tooltip.replace("\n", " ")}"
        }.take(100)
        if (items.isEmpty()) "No diagnostics." else items.joinToString("\n")
    }

    private fun openFile(path: String, line: Int?, column: Int?): String {
        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            val vf = LocalFileSystem.getInstance().findFileByPath(path)
            if (vf == null) {
                result = "File not found: $path"
                return@invokeAndWait
            }
            val descriptor = OpenFileDescriptor(
                project,
                vf,
                (line?.minus(1))?.coerceAtLeast(0) ?: 0,
                (column?.minus(1))?.coerceAtLeast(0) ?: 0,
            )
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            result = "Opened $path${line?.let { " (line $it)" }.orEmpty()}"
        }
        return result
    }

    // ---------------------------------------------------------------- Bridge script

    private fun antigravityDir(): File {
        val dir = File(System.getProperty("user.home"), ".gemini/antigravity-cli")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun resolveJavaExecutable(): File {
        val javaHome = System.getProperty("java.home")
            ?: error("java.home system property is not set")
        val name = if (AntigravitySettings.isWindows()) "java.exe" else "java"
        return File(javaHome, "bin/$name")
    }

    private fun resolveBridgeClasspath(): File {
        // IntelliJ's PluginClassLoader does not populate ProtectionDomain.codeSource for plugin
        // classes, so we use PathManager.getJarPathForClass which is the supported API.
        val path = PathManager.getJarPathForClass(StdioBridge::class.java)
            ?: error("Cannot resolve plugin JAR for ${StdioBridge::class.java.name}")
        return File(path)
    }

    private fun writeBridgeScript() {
        val isWindows = AntigravitySettings.isWindows()
        val ext = if (isWindows) "bat" else "sh"
        val script = File(antigravityDir(), "phpstorm-mcp-bridge-$projectHash.$ext")
        bridgeScript = script

        val java = resolveJavaExecutable().absolutePath
        val classpath = resolveBridgeClasspath().absolutePath

        val contents = if (isWindows) windowsScript(java, classpath, port)
        else unixScript(java, classpath, port)

        try {
            script.writeText(contents)
            if (!isWindows) {
                try {
                    Files.setPosixFilePermissions(
                        script.toPath(),
                        setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                        ),
                    )
                } catch (_: UnsupportedOperationException) {
                    // Non-POSIX FS — best effort.
                }
            }
            log.info("Wrote MCP bridge script: ${script.absolutePath}")
        } catch (e: IOException) {
            log.error("Failed to write MCP bridge script", e)
        }
    }

    private fun unixScript(java: String, classpath: String, port: Int): String = """
        |#!/bin/sh
        |# Auto-generated by the Antigravity Companion plugin.
        |# Bridges agy's stdio MCP transport to the plugin's local TCP server.
        |# Project hash: $projectHash
        |exec "$java" -cp "$classpath" ${StdioBridge::class.java.name} 127.0.0.1 $port
        |""".trimMargin()

    private fun windowsScript(java: String, classpath: String, port: Int): String = """
        |@echo off
        |rem Auto-generated by the Antigravity Companion plugin.
        |rem Bridges agy's stdio MCP transport to the plugin's local TCP server.
        |rem Project hash: $projectHash
        |"$java" -cp "$classpath" ${StdioBridge::class.java.name} 127.0.0.1 $port
        |""".trimMargin().replace("\n", "\r\n")

    // ---------------------------------------------------------------- mcp_config.json

    private fun mcpConfigFile(): File {
        val dir = File(System.getProperty("user.home"), ".gemini/config")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "mcp_config.json")
    }

    private fun readExistingMcpConfig(): JsonObject {
        val cfg = mcpConfigFile()
        if (!cfg.exists()) return JsonObject(emptyMap())
        val text = try { cfg.readText() } catch (e: IOException) {
            log.warn("Could not read mcp_config.json: ${e.message}")
            return JsonObject(emptyMap())
        }
        if (text.isBlank()) return JsonObject(emptyMap())
        return try {
            (Json.parseToJsonElement(text) as? JsonObject) ?: JsonObject(emptyMap())
        } catch (e: Exception) {
            log.warn("mcp_config.json is not a JSON object, overwriting: ${e.message}")
            JsonObject(emptyMap())
        }
    }

    private fun writeMergedMcpConfig(updated: JsonObject) {
        val cfg = mcpConfigFile()
        try {
            cfg.writeText(prettyJson.encodeToString(JsonObject.serializer(), updated))
        } catch (e: IOException) {
            log.error("Failed to write mcp_config.json", e)
        }
    }

    private fun registerInMcpConfig() {
        val script = bridgeScript ?: return
        val existing = readExistingMcpConfig()
        val existingServers = (existing["mcpServers"] as? JsonObject) ?: JsonObject(emptyMap())

        val updatedServers = buildJsonObject {
            for ((k, v) in existingServers) put(k, v)
            put(mcpEntryName, buildJsonObject {
                put("command", script.absolutePath)
                put("args", JsonArray(emptyList()))
            })
        }
        val merged = buildJsonObject {
            for ((k, v) in existing) if (k != "mcpServers") put(k, v)
            put("mcpServers", updatedServers)
        }
        writeMergedMcpConfig(merged)
        log.info("Registered MCP entry '$mcpEntryName' -> ${script.absolutePath}")
    }

    private fun unregisterFromMcpConfig() {
        val existing = readExistingMcpConfig()
        val servers = (existing["mcpServers"] as? JsonObject) ?: return
        if (!servers.containsKey(mcpEntryName)) return
        val updatedServers = buildJsonObject {
            for ((k, v) in servers) if (k != mcpEntryName) put(k, v)
        }
        val merged = buildJsonObject {
            for ((k, v) in existing) if (k != "mcpServers") put(k, v)
            put("mcpServers", updatedServers)
        }
        writeMergedMcpConfig(merged)
        log.info("Removed MCP entry '$mcpEntryName' from mcp_config.json")
    }

    // ---------------------------------------------------------------- Lifecycle

    override fun dispose() {
        log.info("Disposing AntigravityCompanionService for project $projectHash")
        try { serverSocket?.close() } catch (_: Exception) { /* ignore */ }
        try { scope.cancel() } catch (_: Exception) { /* ignore */ }
        try { unregisterFromMcpConfig() } catch (e: Exception) {
            log.warn("Failed to unregister from mcp_config.json", e)
        }
        try { bridgeScript?.takeIf { it.exists() }?.delete() } catch (_: Exception) { /* ignore */ }
    }

    companion object {
        const val PLUGIN_VERSION: String = "1.0.1"

        /**
         * Sent as the `instructions` field on the MCP `initialize` response. Clients SHOULD
         * surface this to the model as part of the system prompt (per the MCP spec). It is the
         * server's only opportunity to set ground rules about when to use the exposed tools,
         * so it must be assertive — descriptions on individual tools are not always enough.
         */
        private val SERVER_INSTRUCTIONS: String = """
            This MCP server exposes live state from the user's JetBrains IDE (PhpStorm, IntelliJ
            IDEA, WebStorm, GoLand, etc.). The user is editing code in that IDE while talking to
            you. Treat the IDE as the authoritative source of "what the user is looking at right
            now".

            Behavior rules (follow these strictly):

            1. When the user references on-screen context with words like "this code", "the
               selection", "the current file", "this function", "this class", "what I'm looking
               at", "highlighted code", or makes a bare ambiguous request ("explain this",
               "fix this", "refactor this", "review this") — IMMEDIATELY call
               `ide_get_active_editor`. Do NOT ask the user to paste the code, share a file path,
               or clarify which snippet they mean. The IDE already knows.

            2. Only fall back to asking the user when `ide_get_active_editor` returns
               "No active editor in PhpStorm right now." (meaning they truly have no file
               focused).

            3. The response from `ide_get_active_editor` already includes the full file content
               and the selected substring — you do NOT need a follow-up `read_file` call for
               the same file in the same turn.

            4. When the user asks about errors, warnings, or "what's wrong with this file",
               prefer `ide_get_diagnostics` over invoking external linters or test suites — the
               IDE's diagnostics are faster, scoped to the active file, and include
               JetBrains-specific inspections that command-line tools miss.

            5. After identifying a relevant file (especially one the user hasn't yet opened),
               call `ide_open_file` to surface it in their editor instead of only quoting the
               path. Saves them a manual navigation.

            6. If multiple files are relevant, call `ide_get_open_files` first to see what the
               user currently has in their tabs — that set is usually the right starting context.
        """.trimIndent()
    }
}
