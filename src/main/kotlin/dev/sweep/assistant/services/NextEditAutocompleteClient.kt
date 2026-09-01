package dev.sweep.assistant.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteRequest
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteResponse
import dev.sweep.assistant.settings.SweepSettings

/**
 * Entry point for next-edit autocomplete requests. All prompts are built
 * in-process by [dev.sweep.assistant.autocomplete.edit.engine.NextEditAutocompleteEngine]
 * and inference runs on the local `llama-server` started by
 * [LocalAutocompleteServerManager] in the visible terminal.
 */
@Service(Service.Level.PROJECT)
class NextEditAutocompleteClient(
    @Suppress("UNUSED_PARAMETER") private val project: Project,
) {
    companion object {
        private val logger = Logger.getInstance(NextEditAutocompleteClient::class.java)

        fun getInstance(project: Project): NextEditAutocompleteClient =
            project.getService(NextEditAutocompleteClient::class.java)
    }

    @RequiresBackgroundThread
    fun cancelInFlightRequests() {
        nativeEngine?.cancelInFlightRequests()
    }

    @RequiresBackgroundThread
    fun fetchNextEditAutocomplete(
        request: NextEditAutocompleteRequest,
        shouldAbort: () -> Boolean = { false },
    ): NextEditAutocompleteResponse? {
        if (shouldAbort()) return null
        val serverManager = LocalAutocompleteServerManager.getInstance()
        if (!serverManager.isServerHealthy()) {
            logger.info("Local llama-server not healthy on request — starting in terminal")
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
            steering = request.steering,
            automaticSteering = request.automatic_steering,
            avoidCompletions = request.avoid_completions,
        )

        val result = engine.fetchNextEdits(nesRequest, shouldAbort)

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
        )
    }

    @Volatile
    private var nativeEngine: dev.sweep.assistant.autocomplete.edit.engine.NextEditAutocompleteEngine? = null

    private fun getOrCreateNativeEngine(): dev.sweep.assistant.autocomplete.edit.engine.NextEditAutocompleteEngine {
        nativeEngine?.let { return it }
        val port = SweepSettings.getInstance().autocompleteLocalPort
        val client = dev.sweep.assistant.autocomplete.edit.engine.LlamaServerClient(
            baseUrl = "http://localhost:$port",
        )
        val engine = dev.sweep.assistant.autocomplete.edit.engine.NextEditAutocompleteEngine(client)
        nativeEngine = engine
        return engine
    }
}
