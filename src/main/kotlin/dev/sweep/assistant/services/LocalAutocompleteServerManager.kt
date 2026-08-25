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
        private const val TERMINAL_TAB_NAME = "Sweep Autocomplete Server"
        private const val LLAMA_CPP_VULKAN_INDEX = "https://abetlen.github.io/llama-cpp-python/whl/vulkan"
        private const val SERVER_WHEEL_RELEASE_URL =
            "https://github.com/forstjiri/sweep-autocomplete/releases/download/v0.1.2/sweep_autocomplete-0.1.2-py3-none-any.whl"
        private const val DEFAULT_MODEL_REPO = "sweepai/sweep-next-edit-0.5B"
        private const val DEFAULT_MODEL_FILENAME = "sweep-next-edit-0.5b.q8_0.gguf"
        private const val MODEL_15B_REPO = "sweepai/sweep-next-edit-1.5B"
        private const val MODEL_15B_FILENAME = "sweep-next-edit-1.5b.q8_0.v2.gguf"

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

    init {
        scope.launch {
            while (isActive) {
                val healthy = isServerHealthy()
                if (lastKnownHealthy && !healthy) {
                    logger.warn("Local autocomplete terminal server became unhealthy")
                    showNotification(
                        "Local autocomplete server stopped. Check the Sweep Autocomplete Server terminal tab.",
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

    private fun getModelConfiguration(): Pair<String, String>? {
        val settings = SweepSettings.getInstance()
        return when (settings.autocompleteModel) {
            SweepSettings.MODEL_05B -> DEFAULT_MODEL_REPO to DEFAULT_MODEL_FILENAME
            SweepSettings.MODEL_15B -> MODEL_15B_REPO to MODEL_15B_FILENAME
            SweepSettings.MODEL_CUSTOM -> {
                val repo = settings.customModelRepo.trim()
                val filename = settings.customModelFilename.trim()
                if (repo.isEmpty() || filename.isEmpty()) null else repo to filename
            }
            else -> DEFAULT_MODEL_REPO to DEFAULT_MODEL_FILENAME
        }
    }

    fun getServerUrl(): String = "http://localhost:${getPort()}"

    fun ensureServerRunning(): Boolean {
        return ensureServerRunning(null)
    }

    fun ensureServerRunning(onStatus: ((String) -> Unit)?): Boolean {
        onStatus?.invoke("Checking if server is already running...")
        if (isServerHealthy()) {
            onStatus?.invoke("Server is already running.")
            return true
        }
        // The server is owned by the visible terminal. Never start a hidden
        // ProcessBuilder instance from an autocomplete request.
        onStatus?.invoke("Local autocomplete server is not available.")
        return false
    }

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

    private fun getPatchedServerWheel(): File? {
        val configured = System.getenv("SWEEP_AUTOCOMPLETE_WHEEL")?.trim()?.takeIf { it.isNotEmpty() }
        if (configured != null) return File(configured).takeIf { it.isFile }
        val distRoot = File(System.getProperty("user.home"), "test/sweep-autocomplete")
        return distRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.flatMap { directory ->
                (directory.resolve("dist").listFiles() ?: emptyArray()).asSequence()
            }
            ?.filter { it.isFile && it.name.startsWith("sweep_autocomplete-") && it.name.endsWith("-py3-none-any.whl") }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun buildUvxCommand(uvxPath: String, port: Int): List<String> =
        getPatchedServerWheel()?.let { wheel ->
            logger.info("Using patched local sweep-autocomplete wheel: ${wheel.absolutePath}")
            buildList {
                add(uvxPath)
                if (!isWindows) {
                    add("--index")
                    add(LLAMA_CPP_VULKAN_INDEX)
                    add("--index-strategy")
                    add("unsafe-best-match")
                }
                add("--from")
                add(wheel.absolutePath)
                add("sweep-autocomplete")
                add("--gpu-profile")
                add("auto")
                add("--port")
                add(port.toString())
            }
        } ?: if (isWindows) {
            listOf(
                uvxPath,
                "--python", "3.12",
                "--extra-index-url", "https://abetlen.github.io/llama-cpp-python/whl/cpu",
                "--from", SERVER_WHEEL_RELEASE_URL,
                "sweep-autocomplete",
                "--gpu-profile", "auto",
                "--port", port.toString(),
            )
        } else {
            listOf(
                uvxPath,
                "--index", LLAMA_CPP_VULKAN_INDEX,
                "--index-strategy", "unsafe-best-match",
                "--from", SERVER_WHEEL_RELEASE_URL,
                "sweep-autocomplete",
                "--gpu-profile", "auto",
                "--port", port.toString(),
            )
        }

    private fun resolveUvx(): String? {
        // Load environment for PATH resolution
        val envPath =
            try {
                val env = com.intellij.util.EnvironmentUtil.getEnvironmentMap()
                if (env.isNotEmpty()) env["PATH"] else System.getenv("PATH")
            } catch (_: Throwable) {
                System.getenv("PATH")
            }

        val exeName = if (isWindows) "uvx.exe" else "uvx"

        // Search PATH
        if (!envPath.isNullOrEmpty()) {
            for (dir in envPath.split(File.pathSeparatorChar)) {
                if (dir.isEmpty()) continue
                val cand = File(dir, exeName)
                if (cand.isFile && cand.canExecute()) {
                    return cand.absolutePath
                }
            }
        }

        // Check common locations
        val home = System.getProperty("user.home")
        val commonLocations =
            listOf(
                "$home/.local/bin/$exeName",
                "$home/.cargo/bin/$exeName",
            )
        for (loc in commonLocations) {
            val f = File(loc)
            if (f.isFile && f.canExecute()) {
                return f.absolutePath
            }
        }

        return null
    }

    private fun installUv() {

        try {
            val process =
                if (isWindows) {
                    ProcessBuilder(
                        "powershell",
                        "-ExecutionPolicy",
                        "ByPass",
                        "-c",
                        "irm https://astral.sh/uv/install.ps1 | iex",
                    ).redirectErrorStream(true).start()
                } else {
                    ProcessBuilder(
                        "/bin/sh",
                        "-c",
                        "curl -LsSf https://astral.sh/uv/install.sh | sh",
                    ).redirectErrorStream(true).start()
                }

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                logger.info("Successfully installed uv")
                showNotification("Successfully installed uv for local autocomplete.", NotificationType.INFORMATION)
            } else {
                logger.warn("Failed to install uv (exit code $exitCode): $output")
                showNotification("Failed to install uv (exit code $exitCode).", NotificationType.ERROR)
            }
        } catch (e: Exception) {
            logger.warn("Error installing uv: ${e.message}")
            showNotification("Error installing uv: ${e.message}", NotificationType.ERROR)
        }
    }

    fun reportSuccess() {
        // Kept for the HTTP client lifecycle; restarts are terminal-only.
    }

    fun reportFailure() {
        logger.debug("Local autocomplete terminal server request failed")
    }

    fun restartServer() {
        logger.info("Ignoring background server restart request; server is terminal-owned")
    }

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

    /**
     * Builds the full command string for starting the server.
     * Returns null if uvx cannot be found (and uv install also fails).
     */
    fun getServerCommand(): String? {
        var uvxPath = resolveUvx()
        if (uvxPath == null) {
            logger.info("uvx not found, attempting to install uv")
            installUv()
            uvxPath = resolveUvx()
            if (uvxPath == null) {
                showNotification(
                    "Failed to find uvx after installing uv. Please install uv manually: https://docs.astral.sh/uv/",
                    NotificationType.ERROR,
                )
                return null
            }
        }
        val model = getModelConfiguration() ?: return null
        val localWheel = getPatchedServerWheel()
        val command = buildUvxCommand(uvxPath, getPort())
        if (isWindows) return command.joinToString(" ") { shellQuote(it) }
        if (localWheel != null) {
            logger.info("Using local patched autocomplete server wheel: ${localWheel.absolutePath}")
        } else {
            logger.info("Using GitHub release autocomplete server wheel: $SERVER_WHEEL_RELEASE_URL")
        }
        return "SWEEP_GPU_PROFILE=auto " +
            "MODEL_REPO=${shellQuote(model.first)} MODEL_FILENAME=${shellQuote(model.second)} " +
            command.joinToString(" ") { shellQuote(it) }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun addExitStatusNotice(command: String): String =
        // Exit codes 130 (SIGINT) and 143 (SIGTERM) are intentional stops (e.g. Ctrl+C
        // from the restart action), not crashes — don't report those.
        if (isWindows) {
            // The IDE terminal defaults to PowerShell on Windows.
            "$command; if (\$LASTEXITCODE -ne 0 -and \$LASTEXITCODE -ne 130 -and \$LASTEXITCODE -ne 143) " +
                "{ Write-Host \"[Sweep Autocomplete] Server exited with code \$LASTEXITCODE. Check the terminal output.\" }"
        } else {
            "$command; exit_code=\$?; if [ \"\$exit_code\" -ne 0 ] && [ \"\$exit_code\" -ne 130 ] && [ \"\$exit_code\" -ne 143 ]; " +
                "then printf '\\n[Sweep Autocomplete] Server exited with code %s. Check Vulkan/AMDGPU logs if this was a GPU crash.\\n' \"\$exit_code\"; fi"
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
        // command typed into the tab would land inside the running uvx process.
        val now = System.currentTimeMillis()
        if (now < terminalStartInFlightUntil) {
            logger.info("Terminal server start already in flight; skipping duplicate start")
            return
        }
        terminalStartInFlightUntil = now + TERMINAL_START_COOLDOWN_MS

        val command = getServerCommand()?.let(::addExitStatusNotice) ?: return

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

    fun stopServer() {
        // The server is intentionally owned by the visible terminal process.
        logger.debug("Skipping server stop; terminal owns the autocomplete process")
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
                        .getNotificationGroup("Sweep Autocomplete")

                notificationGroup?.createNotification("Sweep Autocomplete", content, type)?.notify(null)
            } catch (e: Exception) {
                logger.warn("Failed to show notification: ${e.message}")
            }
        }
    }

    override fun dispose() {
        stopServer()
        scope.cancel()
    }
}
