package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteRequest
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteResponse
import dev.sweep.assistant.utils.CompressionUtils
import dev.sweep.assistant.utils.defaultJson
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Client for the local `sweep-autocomplete` server. The cloud branch has been removed:
 * every request goes to `127.0.0.1:<autocompleteLocalPort>` managed by
 * [LocalAutocompleteServerManager].
 */
@Service(Service.Level.PROJECT)
class AutocompleteIpResolverService(
    @Suppress("UNUSED_PARAMETER") private val project: Project,
) : Disposable {
    companion object {
        private val logger = Logger.getInstance(AutocompleteIpResolverService::class.java)

        fun getInstance(project: Project): AutocompleteIpResolverService =
            project.getService(AutocompleteIpResolverService::class.java)

        private const val READ_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build()

    fun getSharedHttpClient(): HttpClient = httpClient

    fun getBaseUrl(): String = LocalAutocompleteServerManager.getInstance().getServerUrl()

    fun getLastLatencyMs(): Long = -1L

    fun updateLastUserActionTimestamp() {
        // no-op (no remote DNS / health-check loop in local-only mode)
    }

    @RequiresBackgroundThread
    suspend fun fetchNextEditAutocomplete(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? {
        // Native engine path: prompt construction in-process + llama-server direct.
        if (dev.sweep.assistant.settings.SweepSettings.getInstance().autocompleteLocalNativeEngine) {
            return fetchViaNativeEngine(request)
        }

        return try {
            if (!LocalAutocompleteServerManager.getInstance().ensureServerRunning()) return null

            val postData = defaultJson.encodeToString(NextEditAutocompleteRequest.serializer(), request)
            val postDataBytes = postData.toByteArray(Charsets.UTF_8)

            val (finalData, useCompression) =
                if (CompressionUtils.isBrotliAvailable()) {
                    val compressed = CompressionUtils.compress(postDataBytes, CompressionUtils.CompressionType.BROTLI)
                    if (compressed.size < postDataBytes.size) Pair(compressed, true) else Pair(postDataBytes, false)
                } else {
                    Pair(postDataBytes, false)
                }

            val builder =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("${getBaseUrl()}/backend/next_edit_autocomplete"))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .header("Content-Type", "application/json")

            if (useCompression) {
                builder.header("Content-Encoding", CompressionUtils.CompressionType.BROTLI.encoding)
            }

            val httpRequest = builder.POST(HttpRequest.BodyPublishers.ofByteArray(finalData)).build()

            val response =
                httpClient
                    .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .await()

            if (response.statusCode() !in 200..299) {
                LocalAutocompleteServerManager.getInstance().reportFailure()
                logger.warn("Local autocomplete server returned ${response.statusCode()}")
                return null
            }

            var result: NextEditAutocompleteResponse? = null
            try {
                response.body().bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        if (l.isBlank()) continue
                        try {
                            val element = defaultJson.parseToJsonElement(l)
                            if (element is JsonObject && element.containsKey("status")) {
                                val status = element["status"]?.jsonPrimitive?.contentOrNull
                                if (status == "error") {
                                    val errorMsg = element["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                                    logger.warn("Local autocomplete server error: $errorMsg")
                                    continue
                                }
                            }
                            result = defaultJson.decodeFromString(NextEditAutocompleteResponse.serializer(), l)
                        } catch (e: Exception) {
                            logger.warn("Error parsing local server response: ${e.message}")
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                logger.info("Local server stream closed: ${e.message}")
            }

            if (result != null) {
                LocalAutocompleteServerManager.getInstance().reportSuccess()
            } else {
                LocalAutocompleteServerManager.getInstance().reportFailure()
            }

            result
        } catch (e: Exception) {
            logger.warn("Error fetching next edit autocomplete: ${e.message}")
            LocalAutocompleteServerManager.getInstance().reportFailure()
            throw e
        }
    }

    private var nativeEngine: dev.sweep.assistant.autocomplete.edit.engine.NextEditAutocompleteEngine? = null

    private fun getOrCreateNativeEngine(): dev.sweep.assistant.autocomplete.edit.engine.NextEditAutocompleteEngine {
        nativeEngine?.let { return it }
        val port = dev.sweep.assistant.settings.SweepSettings.getInstance().autocompleteLocalPort
        val client = dev.sweep.assistant.autocomplete.edit.engine.LlamaServerClient(
            baseUrl = "http://localhost:$port",
        )
        val engine = dev.sweep.assistant.autocomplete.edit.engine.NextEditAutocompleteEngine(client)
        nativeEngine = engine
        return engine
    }

    private fun fetchViaNativeEngine(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? {
        // Ensure the local llama-server is running before attempting an autocomplete.
        // The startup activity also tries to start it on project open, but this is a
        // safety net for cases where startup didn't fire or the server died.
        val serverManager = LocalAutocompleteServerManager.getInstance()
        if (!serverManager.isServerHealthy()) {
            logger.info("Local llama-server not healthy on first request — starting in terminal")
            serverManager.startServerInTerminal(project)
            // Server takes several seconds to load; skip this autocomplete request rather
            // than block. Subsequent requests will succeed once the server is up.
            return null
        }
        val engine = getOrCreateNativeEngine()

        val nesRequest = dev.sweep.assistant.autocomplete.edit.engine.NextEditAutocompleteEngine.NesRequest(
            filePath = request.file_path,
            fileContents = request.file_contents,
            originalFileContents = request.original_file_contents,
            recentChanges = request.recent_changes,
            cursorPosition = request.cursor_position,
            fileChunks = request.file_chunks.map {
                dev.sweep.assistant.autocomplete.edit.engine.NesPromptBuilder.FileChunkData(
                    filePath = it.file_path,
                    content = it.content,
                    startLine = it.start_line,
                    endLine = it.end_line,
                )
            },
            retrievalChunks = request.retrieval_chunks.map {
                dev.sweep.assistant.autocomplete.edit.engine.NesPromptBuilder.FileChunkData(
                    filePath = it.file_path,
                    content = it.content,
                    startLine = it.start_line,
                    endLine = it.end_line,
                )
            },
            recentUserActions = request.recent_user_actions.map {
                dev.sweep.assistant.autocomplete.edit.engine.NextEditAutocompleteEngine.UserAction(
                    actionType = it.action_type.name,
                    lineNumber = it.line_number,
                    offset = it.offset,
                    filePath = it.file_path,
                    timestamp = it.timestamp,
                )
            },
            recentChangesHighRes = request.recent_changes_high_res,
            changesAboveCursor = request.changes_above_cursor,
            editorDiagnostics = request.editor_diagnostics.map {
                dev.sweep.assistant.autocomplete.edit.engine.NesRetrieval.EditorDiagnosticData(
                    line = it.line,
                    lineNumber = it.line - 1,
                    startOffset = it.start_offset,
                    endOffset = it.end_offset,
                    severity = it.severity,
                    message = it.message,
                )
            },
        )

        val result = engine.fetchNextEdits(nesRequest)

        if (result.completions.isEmpty()) return null

        val first = result.completions.first()
        return NextEditAutocompleteResponse(
            start_index = first.startIndex,
            end_index = first.endIndex,
            completion = first.completion,
            confidence = first.confidence,
            autocomplete_id = result.autocompleteId,
            elapsed_time_ms = result.elapsedMs,
            completions = result.completions.map {
                dev.sweep.assistant.autocomplete.edit.NextEditAutocompletion(
                    start_index = it.startIndex,
                    end_index = it.endIndex,
                    completion = it.completion,
                    confidence = it.confidence,
                    autocomplete_id = it.autocompleteId,
                )
            },
            nativeIndices = true, // indices are already JVM-native, skip Python→JVM conversion
        )
    }

    override fun dispose() {
        scope.cancel()
    }
}
