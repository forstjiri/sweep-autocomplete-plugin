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
import java.net.InetSocketAddress
import java.net.Socket

@Service(Service.Level.APP)
class LocalAutocompleteServerManager : Disposable {
    companion object {
        private val logger = Logger.getInstance(LocalAutocompleteServerManager::class.java)
        private const val DEFAULT_PORT = 8081
        private const val HEALTH_CHECK_TIMEOUT_MS = 3000L
        private const val SERVER_START_TIMEOUT_MS = 30000L
        private const val SERVER_POLL_INTERVAL_MS = 500L
        private const val HEALTH_CHECK_INTERVAL_MS = 10_000L
        private const val TERMINAL_TAB_NAME = "Sweep Autocomplete Server"
        private const val LLAMA_CPP_VULKAN_INDEX = "https://abetlen.github.io/llama-cpp-python/whl/vulkan"
        private const val DEFAULT_MODEL_REPO = "sweepai/sweep-next-edit-0.5B"
        private const val DEFAULT_MODEL_FILENAME = "sweep-next-edit-0.5b.q8_0.gguf"
        private const val MODEL_15B_REPO = "sweepai/sweep-next-edit-1.5B"
        private const val MODEL_15B_FILENAME = "sweep-next-edit-1.5b.q8_0.v2.gguf"

        fun getInstance(): LocalAutocompleteServerManager =
            ApplicationManager.getApplication().getService(LocalAutocompleteServerManager::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Legacy background-start state is retained only for source compatibility;
    // no request path invokes the background launcher anymore.
    private var serverProcess: Process? = null
    @Volatile
    private var isStarting = false
    init {
        scope.launch {
            while (isActive) {
                val healthy = isServerHealthy()
                logger.debug("Local autocomplete terminal server health: $healthy")
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
        // TCP-level probe: just check that something is accepting connections on the port.
        // Avoids the HTTP `GET / 404` line the FastAPI server logs for every probe.
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", getPort()), HEALTH_CHECK_TIMEOUT_MS.toInt())
                true
            }
        } catch (e: Exception) {
            false
        }

    @Synchronized
    private fun startServer(onStatus: ((String) -> Unit)? = null) {
        if (isStarting) return
        if (isServerHealthy()) return
        isStarting = true

        try {
            onStatus?.invoke("Looking for uvx on PATH...")
            val uvxPath = resolveUvx()
            if (uvxPath == null) {
                logger.info("uvx not found, attempting to install uv")
                onStatus?.invoke("uvx not found. Installing uv...")
                installUv()
                onStatus?.invoke("Checking for uvx after install...")
                val uvxAfterInstall = resolveUvx()
                if (uvxAfterInstall == null) {
                    val msg = "Failed to find uvx after installing uv. Please install uv manually."
                    onStatus?.invoke(msg)
                    showNotification(
                        "Failed to find uvx after installing uv. Please install uv manually: https://docs.astral.sh/uv/",
                        NotificationType.ERROR,
                    )
                    return
                }
                onStatus?.invoke("Found uvx at $uvxAfterInstall. Starting server...")
                startServerProcess(uvxAfterInstall, onStatus)
            } else {
                onStatus?.invoke("Found uvx at $uvxPath. Starting server...")
                startServerProcess(uvxPath, onStatus)
            }
        } finally {
            isStarting = false
        }
    }

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private fun buildUvxCommand(uvxPath: String, port: Int): List<String> =
        if (isWindows) {
            listOf(
                uvxPath,
                "--python", "3.12",
                "--extra-index-url", "https://abetlen.github.io/llama-cpp-python/whl/cpu",
                "sweep-autocomplete",
                "--port", port.toString(),
            )
        } else {
            listOf(
                uvxPath,
                "--index", LLAMA_CPP_VULKAN_INDEX,
                "--index-strategy", "unsafe-best-match",
                "sweep-autocomplete",
                "--port", port.toString(),
            )
        }

    private fun startServerProcess(uvxPath: String, onStatus: ((String) -> Unit)? = null) {
        val port = getPort()
        val model = getModelConfiguration() ?: return
        val command = buildUvxCommand(uvxPath, port)
        val pb = ProcessBuilder(command)

        // Load environment using EnvironmentUtil pattern
        try {
            val env = com.intellij.util.EnvironmentUtil.getEnvironmentMap()
            if (env.isNotEmpty()) {
                pb.environment().apply {
                    clear()
                    putAll(env)
                }
            }
            pb.environment()["MODEL_REPO"] = model.first
            pb.environment()["MODEL_FILENAME"] = model.second
        } catch (_: Throwable) {
            // Fall back to default environment
        }

        // Redirect stdout to /dev/null — the server communicates via HTTP, not stdout.
        // llama_cpp calls print() during generation which causes BrokenPipeError if stdout is a pipe.
        pb.redirectOutput(ProcessBuilder.Redirect.to(File(if (isWindows) "NUL" else "/dev/null")))

        try {
            serverProcess = pb.start()
            logger.info("Started local autocomplete server with: ${command.joinToString(" ")}")

            // Consume stderr in background for logging
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    serverProcess?.errorStream?.bufferedReader()?.useLines { lines ->
                        lines.forEach { line ->
                            logger.info("Local autocomplete server: $line")
                        }
                    }
                } catch (_: Exception) {
                    // Process may have been closed
                }
            }

            // Poll for health check up to 30 seconds
            onStatus?.invoke("Waiting for server to become healthy...")
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < SERVER_START_TIMEOUT_MS) {
                if (isServerHealthy()) {
                    logger.info("Local autocomplete server is healthy")
                    onStatus?.invoke("Server is running on localhost:$port")
                    showNotification("Local autocomplete server started successfully.", NotificationType.INFORMATION)
                    return
                }
                // Check if process died
                serverProcess?.let { proc ->
                    if (!proc.isAlive) {
                        logger.warn("Local autocomplete server process exited with code: ${proc.exitValue()}")
                        val msg = "Server process exited with code ${proc.exitValue()}"
                        onStatus?.invoke(msg)
                        showNotification(
                            "Local autocomplete server failed to start (exit code ${proc.exitValue()}).",
                            NotificationType.ERROR,
                        )
                        serverProcess = null
                        return
                    }
                }
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                onStatus?.invoke("Waiting for server to become healthy... (${elapsed}s)")
                Thread.sleep(SERVER_POLL_INTERVAL_MS)
            }

            logger.warn("Local autocomplete server did not become healthy within ${SERVER_START_TIMEOUT_MS}ms")
            onStatus?.invoke("Server did not start within 30 seconds.")
            showNotification(
                "Local autocomplete server did not start within 30 seconds.",
                NotificationType.WARNING,
            )
        } catch (e: Exception) {
            logger.warn("Failed to start local autocomplete server: ${e.message}")
            onStatus?.invoke("Failed to start server: ${e.message}")
            showNotification(
                "Failed to start local autocomplete server: ${e.message}",
                NotificationType.ERROR,
            )
        }
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
        stopServer()
        ApplicationManager.getApplication().invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
            val content = toolWindow?.contentManager?.findContent(TERMINAL_TAB_NAME)
            val widget = content?.let { TerminalToolWindowManager.findWidgetByContent(it) }
            widget?.ttyConnector?.write("\u0003")
        }
        scope.launch {
            repeat(10) {
                if (!isServerHealthy()) {
                    startServerInTerminal(project)
                    return@launch
                }
                delay(500)
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
        val command = buildUvxCommand(uvxPath, getPort())
        if (isWindows) return command.joinToString(" ") { shellQuote(it) }
        return "MODEL_REPO=${shellQuote(model.first)} MODEL_FILENAME=${shellQuote(model.second)} " +
            command.joinToString(" ") { shellQuote(it) }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun addExitStatusNotice(command: String): String =
        if (isWindows) {
            "$command & set EXIT_CODE=%ERRORLEVEL% & if not %EXIT_CODE%==0 echo [Sweep Autocomplete] Server exited with code %EXIT_CODE%. Check the terminal output."
        } else {
            "$command; exit_code=\$?; if [ \"\$exit_code\" -ne 0 ]; then printf '\\n[Sweep Autocomplete] Server exited with code %s. Check Vulkan/AMDGPU logs if this was a GPU crash.\\n' \"\$exit_code\"; fi"
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
