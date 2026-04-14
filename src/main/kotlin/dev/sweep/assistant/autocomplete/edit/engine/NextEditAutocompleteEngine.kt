package dev.sweep.assistant.autocomplete.edit.engine

import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.autocomplete.edit.engine.NesCompletionParser.AutocompleteResult
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.MAX_RETRIEVAL_CHUNK_SIZE_LINES
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.NUM_LINES_AFTER
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.NUM_LINES_BEFORE
import java.util.UUID
import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

/**
 * Top-level orchestrator for the NES engine.
 * Coordinates prompt building, LLM inference, and completion parsing.
 *
 * This replaces the Python sweep-autocomplete server — all logic runs
 * in the JVM, calling llama-server's /v1/completions endpoint for inference.
 */
class NextEditAutocompleteEngine(
    private val llamaClient: LlamaServerClient,
) {
    private val logger = Logger.getInstance(NextEditAutocompleteEngine::class.java)

    data class NesRequest(
        val filePath: String,
        val fileContents: String,
        val originalFileContents: String?,
        val recentChanges: String,
        val cursorPosition: Int,
        val fileChunks: List<NesPromptBuilder.FileChunkData> = emptyList(),
        val retrievalChunks: List<NesPromptBuilder.FileChunkData> = emptyList(),
        val recentUserActions: List<UserAction> = emptyList(),
        val recentChangesHighRes: String = "",
        val changesAboveCursor: Boolean = false,
        val editorDiagnostics: List<NesRetrieval.EditorDiagnosticData>? = null,
    )

    data class UserAction(
        val actionType: String,
        val lineNumber: Int,
        val offset: Int,
        val filePath: String,
        val timestamp: Long = 0,
    )

    data class NesResponse(
        val completions: List<AutocompleteResult>,
        val elapsedMs: Long,
        val autocompleteId: String,
    )

    /**
     * Main entry point: generate next-edit suggestions for the given request.
     * Ported from Python fetch_next_edits() + _fetch_next_edits_core().
     */
    fun fetchNextEdits(request: NesRequest): NesResponse {
        val autocompleteId = UUID.randomUUID().toString().replace("-", "")
        val fileContents = request.fileContents
        val originalFileContents = request.originalFileContents ?: fileContents
        val cursorPosition = request.cursorPosition

        // Check if autocomplete should be disabled for this file
        if (shouldDisableAutocomplete(fileContents)) {
            return emptyResponse(autocompleteId)
        }

        // Extract code block around cursor
        val block = NesPromptBuilder.getBlockAtCursor(fileContents, cursorPosition)

        if (NesUtils.shouldDisableForCodeBlock(block.codeBlock)) {
            return emptyResponse(autocompleteId)
        }

        // Truncate retrieval chunks
        val retrievalChunks = request.retrievalChunks.map { chunk ->
            chunk.copy(
                content = chunk.content.linesSplitKeepEnds()
                    .take(MAX_RETRIEVAL_CHUNK_SIZE_LINES)
                    .joinToString("")
            )
        }

        // Limit chunks for local model
        val fileChunks = request.fileChunks.take(1)
        val limitedRetrievalChunks = retrievalChunks.take(1)

        // Determine if ghost text should be forced
        val forceGhostText = request.recentUserActions.isEmpty() ||
            request.recentUserActions.lastOrNull()?.actionType == "INSERT_CHAR"

        // First pass: autocomplete at cursor
        val startTime = System.currentTimeMillis()
        val firstPassResult = runAutocompletePass(
            filePath = request.filePath,
            fileContents = fileContents,
            originalFileContents = originalFileContents,
            recentChanges = request.recentChanges,
            cursorPosition = cursorPosition,
            codeBlock = block.codeBlock,
            prefix = block.prefix,
            suffix = block.suffix,
            blockStartIndex = block.blockStartIndex,
            autocompleteId = autocompleteId,
            fileChunks = fileChunks,
            retrievalChunks = limitedRetrievalChunks,
            recentChangesHighRes = request.recentChangesHighRes,
            changesAboveCursor = request.changesAboveCursor,
            forceGhostText = forceGhostText,
        )

        if (firstPassResult != null && firstPassResult.isNotEmpty() &&
            firstPassResult.any { it.completion.trim('\n').isNotEmpty() || it.startIndex != it.endIndex }
        ) {
            val elapsed = System.currentTimeMillis() - startTime
            return NesResponse(firstPassResult, elapsed, autocompleteId)
        }

        // Second pass: retrieval-based autocomplete
        if (request.recentChanges.isNotEmpty()) {
            val retrievalResult = NesRetrieval.findBestMatchingBlock(
                fileContents, request.recentChanges, cursorPosition,
                blockSize = 6, editorDiagnostics = request.editorDiagnostics,
            )

            if (retrievalResult.codeBlock.isNotEmpty()) {
                val prefixLines = fileContents.substring(0, retrievalResult.blockStartOffset)
                    .linesSplitKeepEnds()
                val retrievedPrefix = prefixLines.takeLast(NUM_LINES_BEFORE).joinToString("")

                val numRetrievedLines = retrievalResult.codeBlock.lines().size
                val numSuffixLines = max(0, NUM_LINES_AFTER + 1 - numRetrievedLines)
                val afterBlock = fileContents.substring(
                    min(fileContents.length, retrievalResult.blockStartOffset + retrievalResult.codeBlock.length)
                )
                val retrievedSuffix = afterBlock.linesSplitKeepEnds().take(numSuffixLines).joinToString("")

                val cursorInBlock = retrievalResult.blockStartOffset +
                    retrievalResult.codeBlock.linesSplitKeepEnds().firstOrNull()?.length.let { it ?: 0 }

                val fullBlock = retrievedPrefix + NesPromptBuilder.truncateCodeBlockByTokensPublic(
                    retrievalResult.codeBlock + retrievedSuffix
                )

                if (!NesUtils.shouldDisableForCodeBlock(fullBlock)) {
                    // Add diagnostic as retrieval chunk if present
                    val extraChunks = if (retrievalResult.diagnostic != null) {
                        val diagLine = fileContents.lines().getOrElse(retrievalResult.diagnostic.lineNumber) { "" }
                        listOf(
                            NesPromptBuilder.FileChunkData(
                                "diagnostics",
                                "${retrievalResult.diagnostic.message} at line ${retrievalResult.diagnostic.lineNumber}:\n$diagLine",
                                1, 2,
                            )
                        ) + limitedRetrievalChunks
                    } else {
                        limitedRetrievalChunks
                    }

                    val secondPassResult = runAutocompletePass(
                        filePath = request.filePath,
                        fileContents = fileContents,
                        originalFileContents = originalFileContents,
                        recentChanges = request.recentChanges,
                        cursorPosition = cursorInBlock,
                        codeBlock = fullBlock,
                        prefix = retrievedPrefix,
                        suffix = retrievedSuffix,
                        blockStartIndex = retrievalResult.blockStartOffset,
                        autocompleteId = autocompleteId,
                        fileChunks = fileChunks,
                        retrievalChunks = extraChunks,
                        recentChangesHighRes = request.recentChangesHighRes,
                        changesAboveCursor = request.changesAboveCursor,
                        forceGhostText = forceGhostText,
                    )

                    if (secondPassResult != null && secondPassResult.isNotEmpty() &&
                        request.recentChanges.isNotEmpty() &&
                        secondPassResult.first().completion.trim().isNotEmpty()
                    ) {
                        val elapsed = System.currentTimeMillis() - startTime
                        return NesResponse(secondPassResult, elapsed, autocompleteId)
                    }
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        return emptyResponse(autocompleteId, elapsed)
    }

    private fun runAutocompletePass(
        filePath: String,
        fileContents: String,
        originalFileContents: String,
        recentChanges: String,
        cursorPosition: Int,
        codeBlock: String,
        prefix: String,
        suffix: String,
        blockStartIndex: Int,
        autocompleteId: String,
        fileChunks: List<NesPromptBuilder.FileChunkData>,
        retrievalChunks: List<NesPromptBuilder.FileChunkData>,
        recentChangesHighRes: String,
        changesAboveCursor: Boolean,
        forceGhostText: Boolean,
    ): List<AutocompleteResult>? {
        if (codeBlock.isEmpty()) return null

        val promptResult = NesPromptBuilder.buildPrompt(
            filePath = filePath,
            fileContents = fileContents,
            originalFileContents = originalFileContents,
            recentChanges = recentChanges,
            cursorPosition = cursorPosition,
            codeBlock = codeBlock,
            prefix = prefix,
            suffix = suffix,
            blockStartIndex = blockStartIndex,
            fileChunks = fileChunks,
            retrievalChunks = retrievalChunks,
            recentChangesHighRes = recentChangesHighRes,
            changesAboveCursor = changesAboveCursor,
            forceGhostText = forceGhostText,
            useRemoteEndpoint = false,  // local llama-server
        )

        if (promptResult.formattedPrompt.isEmpty()) return null

        // Log prompt details for debugging
        logger.info("NES: prompt length=${promptResult.formattedPrompt.length} chars, " +
            "codeBlock length=${promptResult.cleanedCodeBlock.length}, " +
            "relativeCursorPos=${promptResult.relativeCursorPosition}, " +
            "relativeCursorLine=${promptResult.relativeCursorLine}, " +
            "blockStartIndex=${promptResult.blockStartIndex}")
        // Log the last ~200 chars of the prompt (the part right before model generation)
        val promptTail = promptResult.formattedPrompt.takeLast(300)
        logger.info("NES: prompt tail: ...${promptTail.replace("\n", "\\n")}")

        // Call llama-server
        // Allow output up to 2x the code block size (room for insertions) + 20 lines buffer
        val maxOutputChars = (promptResult.cleanedCodeBlock.length * 2) + (20 * 80)
        val completionResult = try {
            llamaClient.generateCompletion(
                prompt = promptResult.formattedPrompt,
                maxOutputChars = maxOutputChars,
            )
        } catch (e: LlamaServerClient.RequestCancelledException) {
            logger.info("NES request cancelled")
            return null
        } catch (e: Exception) {
            logger.warn("NES inference error: ${e.message}")
            return null
        }

        if (completionResult.text.isEmpty()) {
            logger.warn("NES: empty completion text")
            return null
        }

        // Post-process completion
        var completion = promptResult.prefill + completionResult.text
        logger.info("NES: raw completion (${completionResult.text.length} chars, finish=${completionResult.finishReason}): ${completionResult.text.take(200)}")
        logger.info("NES: prefill='${promptResult.prefill.take(50)}', forcedPrefix='${promptResult.forcedPrefix.take(50)}'")

        if (completion.startsWith("<|") || completion.removePrefix(promptResult.forcedPrefix).startsWith("<|")) {
            logger.warn("NES: filtered — completion starts with special token")
            return null
        }
        if (promptResult.forcedPrefix.isNotEmpty() && !completionResult.text.startsWith(promptResult.forcedPrefix)) {
            logger.warn("NES: filtered — forced prefix '${promptResult.forcedPrefix.take(30)}' not respected")
            return null
        }

        // Clean up completion
        if (completion.trimEnd('\n').endsWith(" No newline at end of file")) {
            completion = completion.substringBefore(" No newline at end of file")
        }
        completion = NesUtils.stripLeadingEmptyNewlines(completion).removeSuffix("<|file_sep|>")
            .ifEmpty { promptResult.cleanedCodeBlock }
        if ("<|cursor|>" !in promptResult.cleanedCodeBlock) {
            completion = completion.replace("<|cursor|>", "")
        }

        // Check max tokens
        if (completionResult.finishReason == "length") {
            logger.warn("NES: filtered — hit max tokens")
            return null
        }

        // Check for pure insertion above cursor
        if (NesCompletionParser.isPureInsertionAboveCursor(
                promptResult.cleanedCodeBlock, completion, promptResult.relativeCursorPosition
            )
        ) {
            logger.warn("NES: filtered — pure insertion above cursor")
            return null
        }

        // Check for large diff above cursor
        if (NesUtils.isLargeDiffAboveCursor(
                promptResult.cleanedCodeBlock, completion, promptResult.relativeCursorPosition
            )
        ) {
            logger.warn("NES: filtered — large diff above cursor")
            return null
        }

        // Select best hunks
        val completions = NesCompletionParser.selectBestHunkFromCompletion(
            completion, promptResult.cleanedCodeBlock, fileContents, cursorPosition, autocompleteId,
        )

        logger.info("NES: selectBestHunk returned ${completions.size} completions")
        completions.forEachIndexed { i, c ->
            logger.info("NES:   [$i] start=${c.startIndex} end=${c.endIndex} text='${c.completion.take(60)}'")
        }

        if (completions.isEmpty()) {
            logger.warn("NES: filtered — no hunks selected from completion")
            logger.warn("NES: cleanedCodeBlock (${promptResult.cleanedCodeBlock.length} chars): '${promptResult.cleanedCodeBlock.take(150).replace("\n", "\\n")}'")
            logger.warn("NES: completion after cleanup (${completion.length} chars): '${completion.take(150).replace("\n", "\\n")}'")
            logger.warn("NES: completion == cleanedCodeBlock? ${completion == promptResult.cleanedCodeBlock}")
            return null
        }

        // Check for reverts
        val codeBlockWithCompletions = NesCompletionParser.applyCompletionsToCodeBlock(
            completions, fileContents, promptResult.cleanedCodeBlock,
        )
        for (section in promptResult.prevSections) {
            if (NesUtils.isEqualIgnoringNewlines(codeBlockWithCompletions, section)) {
                logger.warn("NES: filtered — revert detected")
                return null
            }
        }

        logger.info("NES: returning ${completions.size} completions successfully")
        return completions
    }

    private fun shouldDisableAutocomplete(fileContents: String): Boolean {
        if (fileContents.isEmpty()) return false
        if (fileContents.length > 10_000_000) return true
        val lines = fileContents.lines()
        if (lines.size > 50_000) return true
        val avgLineLength = fileContents.length.toDouble() / lines.size
        if (avgLineLength > 240) return true
        return false
    }

    private fun emptyResponse(autocompleteId: String, elapsedMs: Long = 0) = NesResponse(
        emptyList(), elapsedMs, autocompleteId,
    )
}
