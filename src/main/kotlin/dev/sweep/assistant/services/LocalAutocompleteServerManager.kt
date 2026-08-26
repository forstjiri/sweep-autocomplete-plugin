package dev.sweep.assistant.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.sweep.assistant.settings.SweepSettings
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import kotlinx.coroutines.*
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Service(Service.Level.APP)
class LocalAutocompleteServerManager : Disposable {
    companion object {
        private val logger = Logger.getInstance(LocalAutocompleteServerManager::class.java)
        private const val DEFAULT_PORT = 8081
        private const val HEALTH_CHECK_TIMEOUT_MS = 3000L
        private const val HEALTH_CHECK_INTERVAL_MS = 10_000L
        private const val TERMINAL_START_COOLDOWN_MS = 30_000L
        private const val TERMINAL_TAB_NAME = "Vulcan Sweep Server"

        fun getInstance(): LocalAutocompleteServerManager =
            ApplicationManager.getApplication().getService(LocalAutocompleteServerManager::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val healthClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(HEALTH_CHECK_TIMEOUT_MS))
        .build()

    @Volatile
    private var lastKnownHealthy = false

    @Volatile
    private var terminalStartInFlightUntil = 0L

    @Volatile
    private var firstStartNoticeShown = false

    init {
        scope.launch {
            while (isActive) {
                val healthy = isServerHealthy()
                if (lastKnownHealthy && !healthy) {
                    logger.warn("Local autocomplete terminal server became unhealthy")
                    showNotification(
                        "Local autocomplete server stopped. Check the Vulcan Sweep Server terminal tab.",
                        NotificationType.WARNING,
                    )
                }
                lastKnownHealthy = healthy
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun getPort(): Int =
        try {
            SweepSettings.getInstance().autocompleteLocalPort
        } catch (_: Exception) {
            DEFAULT_PORT
        }

    fun getServerUrl(): String = "http://localhost:${getPort()}"

    fun isServerHealthy(): Boolean =
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${getServerUrl()}/health"))
                .timeout(Duration.ofMillis(HEALTH_CHECK_TIMEOUT_MS))
                .GET()
                .build()
            healthClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
        } catch (e: Exception) {
            false
        }

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun restartServerInTerminal(project: Project) {
        // Send Ctrl+C first and let the poll coroutine run afterwards, so the stop
        // signal can never race against the newly started command.
        ApplicationManager.getApplication().invokeLater {
            try {
                val toolWindow = ToolWindowManager.getInstance(project)
                    .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
                val content = toolWindow?.contentManager?.findContent(TERMINAL_TAB_NAME)
                val widget = content?.let { TerminalToolWindowManager.findWidgetByContent(it) }
                widget?.ttyConnector?.write("\u0003")
            } catch (e: Exception) {
                logger.warn("Failed to send Ctrl+C to terminal server tab: ${e.message}")
            }
        }
        scope.launch {
            repeat(10) {
                delay(500)
                if (!isServerHealthy()) {
                    startServerInTerminal(project)
                    return@launch
                }
            }
            logger.warn("Terminal autocomplete server did not stop after restart request")
        }
    }

    // --- llama-server support for native engine ---

    private fun getSelectedModel(): dev.sweep.assistant.autocomplete.edit.engine.NesModel {
        val modelId = SweepSettings.getInstance().autocompleteLocalModel
        return dev.sweep.assistant.autocomplete.edit.engine.NesModelConfig.getModel(modelId)
    }

    /**
     * Resolve llama-server binary on PATH.
     */
    private fun resolveLlamaServer(): String? {
        val envPath = try {
            val env = com.intellij.util.EnvironmentUtil.getEnvironmentMap()
            if (env.isNotEmpty()) env["PATH"] else System.getenv("PATH")
        } catch (_: Throwable) {
            System.getenv("PATH")
        }

        val exeName = if (isWindows) "llama-server.exe" else "llama-server"
        if (!envPath.isNullOrEmpty()) {
            for (dir in envPath.split(File.pathSeparatorChar)) {
                if (dir.isEmpty()) continue
                val cand = File(dir, exeName)
                if (cand.isFile && cand.canExecute()) return cand.absolutePath
            }
        }

        // Check common locations
        val commonPaths = listOf(
            "/opt/homebrew/bin/llama-server",
            "/usr/local/bin/llama-server",
            System.getProperty("user.home") + "/.local/bin/llama-server",
        )
        for (path in commonPaths) {
            val f = File(path)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }

        return null
    }

    /**
     * Resolve the GGUF model path.
     * Checks: 1) HuggingFace cache, 2) Sweep models cache (~/.cache/sweep/models/).
     * Returns the path to the .gguf file, or null if not cached yet.
     */
    private fun resolveModelPath(): String? {
        val model = getSelectedModel()

        // Check HuggingFace cache
        val hfCacheBase = File(System.getProperty("user.home"), ".cache/huggingface/hub")
        val modelDir = File(hfCacheBase, "models--${model.repo.replace("/", "--")}")
        if (modelDir.isDirectory) {
            val snapshotsDir = File(modelDir, "snapshots")
            if (snapshotsDir.isDirectory) {
                val snapshots = snapshotsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                for (snapshot in snapshots) {
                    val gguf = File(snapshot, model.filename)
                    if (gguf.isFile) return gguf.absolutePath
                }
            }
        }

        // Check Sweep models cache
        val sweepCache = File(System.getProperty("user.home"), ".cache/sweep/models/${model.filename}")
        if (sweepCache.isFile) return sweepCache.absolutePath

        return null
    }

    private val SWEEP_MODELS_DIR = System.getProperty("user.home") + "/.cache/sweep/models"

    /**
     * Build a shell command to download the model.
     * Tries hf first, falls back to curl.
     */
    private fun buildModelDownloadCommand(): String {
        val model = getSelectedModel()
        val hfCliCmd = "hf download ${model.repo} ${model.filename}"
        val url = "https://huggingface.co/${model.repo}/resolve/main/${model.filename}"
        val destDir = SWEEP_MODELS_DIR
        val destFile = "$destDir/${model.filename}"
        val curlCmd = "mkdir -p $destDir && curl -L -o \"$destFile\" \"$url\""

        // Shell one-liner: try hf, fall back to curl
        return "if command -v hf >/dev/null 2>&1; then $hfCliCmd; else echo 'hf not found, downloading with curl...' && $curlCmd; fi"
    }

    /**
     * Build the llama-server command with speculative decoding flags.
     */
    private fun buildLlamaServerCommand(llamaServerPath: String, modelPath: String, port: Int): List<String> {
        return listOf(
            llamaServerPath,
            "-m", modelPath,
            "--port", port.toString(),
            "-ngl", "-1",
            "--flash-attn", "auto",
            "--spec-type", "ngram-mod",
            "--spec-ngram-size-n", "24",
            "--draft-min", "48",
            "--draft-max", "64",
        )
    }

    /**
     * Builds the full command string for starting the server.
     * Builds the full llama-server command for the visible terminal.
     */
    fun getServerCommand(): String? {
        val port = getPort()

        val llamaPath = resolveLlamaServer()
        if (llamaPath == null) {
            showNotification(
                "llama-server not found on PATH. Install llama.cpp (e.g. 'brew install llama.cpp', " +
                    "a distro/conda package, or build it with -DGGML_VULKAN=ON) so 'llama-server' is available.",
                NotificationType.ERROR,
            )
            return null
        }

        val modelPath = resolveModelPath()
        if (modelPath != null) {
            return buildLlamaServerCommand(llamaPath, modelPath, port).joinToString(" ") { arg ->
                if (arg.contains(" ")) "\"$arg\"" else arg
            }
        }

        // Model not downloaded yet — return a command that downloads first, then starts
        val model = getSelectedModel()
        val downloadCmd = buildModelDownloadCommand()
        val repoDirName = model.repo.replace("/", "--")
        val sweepCachePath = "$SWEEP_MODELS_DIR/${model.filename}"
        val serverCmd = buildLlamaServerCommand(llamaPath, "\$MODEL_PATH", port)
            .joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it }

        // After download, find the model in either HF cache or Sweep cache
        val findModel = "MODEL_PATH=\$(find ~/.cache/huggingface/hub/models--$repoDirName -name '${model.filename}' 2>/dev/null | head -1); " +
            "[ -z \"\$MODEL_PATH\" ] && MODEL_PATH=\"$sweepCachePath\""

        return "$downloadCmd && $findModel && $serverCmd"
    }

    private fun addExitStatusNotice(command: String): String =
        // Exit codes 130 (SIGINT) and 143 (SIGTERM) are intentional stops (e.g. Ctrl+C
        // from the restart action), not crashes — don't report those.
        if (isWindows) {
            // The IDE terminal defaults to PowerShell on Windows.
            "$command; if (\$LASTEXITCODE -ne 0 -and \$LASTEXITCODE -ne 130 -and \$LASTEXITCODE -ne 143) " +
                "{ Write-Host \"[Vulcan Sweep] Server exited with code \$LASTEXITCODE. Check the terminal output.\" }"
        } else {
            "$command; exit_code=\$?; if [ \"\$exit_code\" -ne 0 ] && [ \"\$exit_code\" -ne 130 ] && [ \"\$exit_code\" -ne 143 ]; " +
                "then printf '\\n[Vulcan Sweep] Server exited with code %s. Check Vulkan/AMDGPU logs if this was a GPU crash.\\n' \"\$exit_code\"; fi"
        }

    /**
     * Starts the local autocomplete server in a visible IDE terminal tab.
     * If the server is already healthy, does nothing.
     */
    fun startServerInTerminal(project: Project) {
        if (isServerHealthy()) {
            logger.info("Local autocomplete server is already running")
            return
        }

        // Guard against concurrent starts (IDE startup + status bar click): a second
        // command typed into the tab would land inside the running llama-server process.
        val now = System.currentTimeMillis()
        if (now < terminalStartInFlightUntil) {
            logger.info("Terminal server start already in flight; skipping duplicate start")
            return
        }
        terminalStartInFlightUntil = now + TERMINAL_START_COOLDOWN_MS

        val command = getServerCommand()?.let(::addExitStatusNotice) ?: return

        if (!firstStartNoticeShown) {
            firstStartNoticeShown = true
            showNotification(
                "Starting the local autocomplete server in the Terminal. The first start downloads uv, the server, and the selected model.",
                NotificationType.INFORMATION,
            )
        }

        ApplicationManager.getApplication().invokeLater {
            try {
                val toolWindow = ToolWindowManager.getInstance(project)
                    .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID) ?: return@invokeLater

                // Reuse existing terminal tab if one exists, otherwise create a new one
                val existingContent = toolWindow.contentManager.findContent(TERMINAL_TAB_NAME)
                val widget = if (existingContent != null) {
                    TerminalToolWindowManager.findWidgetByContent(existingContent)
                } else {
                    null
                }

                if (widget == null) {
                    val workDir = project.basePath ?: System.getProperty("user.home")
                    TerminalToolWindowManager
                        .getInstance(project)
                        .createShellWidget(workDir, TERMINAL_TAB_NAME, true, true)
                }

                // Wait for the terminal shell/TTY to finish initializing before sending the command.
                ApplicationManager.getApplication().executeOnPooledThread {
                    var attempts = 0
                    while (attempts++ < 20) {
                        Thread.sleep(500)
                        val readyWidget = toolWindow.contentManager.findContent(TERMINAL_TAB_NAME)?.let {
                            TerminalToolWindowManager.findWidgetByContent(it)
                        } ?: continue
                        if (readyWidget is ShellTerminalWidget || readyWidget.ttyConnector != null) {
                            ApplicationManager.getApplication().invokeLater {
                                try {
                                    val shellWidget = readyWidget as? ShellTerminalWidget
                                    if (shellWidget != null) {
                                        shellWidget.executeCommand(command)
                                    } else {
                                        readyWidget.ttyConnector?.write(command + "\n")
                                    }
                                } catch (e: Throwable) {
                                    logger.warn("Failed to send command to terminal widget", e)
                                }
                            }
                            break
                        }
                    }
                }
                logger.info("Started local autocomplete server in terminal: $command")
            } catch (e: Exception) {
                logger.warn("Failed to start local autocomplete server in terminal: ${e.message}")
                showNotification(
                    "Failed to open terminal for local autocomplete server: ${e.message}",
                    NotificationType.ERROR,
                )
            }
        }
    }

    private fun showNotification(
        content: String,
        type: NotificationType,
    ) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val notificationGroup =
                    NotificationGroupManager
                        .getInstance()
                        .getNotificationGroup("Vulcan Sweep")

                notificationGroup?.createNotification("Vulcan Sweep", content, type)?.notify(null)
            } catch (e: Exception) {
                logger.warn("Failed to show notification: ${e.message}")
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
