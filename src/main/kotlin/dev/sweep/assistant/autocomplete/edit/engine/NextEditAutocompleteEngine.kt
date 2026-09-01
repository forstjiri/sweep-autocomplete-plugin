package dev.sweep.assistant.autocomplete.edit.engine

import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.autocomplete.edit.engine.NesCompletionParser.AutocompleteResult
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.MAX_RETRIEVAL_CHUNK_SIZE_LINES
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.NUM_LINES_AFTER
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.NUM_LINES_BEFORE
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.WIDER_NUM_LINES_AFTER
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.WIDER_NUM_LINES_BEFORE
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
        val steering: String? = null,
        val avoidCompletions: List<String> = emptyList(),
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
    fun fetchNextEdits(
        request: NesRequest,
        shouldAbort: () -> Boolean = { false },
    ): NesResponse {
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

        // Steering matrix: steered requests walk context variants (V1 cursor
        // block, V2/V3 retrieval/expanded blocks) at 0.35, then again at 0.8.
        // Context changes beat temperature changes, so variants come first.
        // Plain typing keeps today's behavior: cursor block, then one
        // retrieval pass, both greedy.
        val steered = request.steering != null || request.avoidCompletions.isNotEmpty()
        val rounds = if (steered) NesUtils.steeringMatrixTemperatures() else listOf(0.0f)
        val candidates =
            buildPassCandidates(request, block, cursorPosition, limitedRetrievalChunks, steered)

        val startTime = System.currentTimeMillis()
        var lastAvoided: List<AutocompleteResult>? = null

        for ((roundIndex, temperature) in rounds.withIndex()) {
            for ((variantIndex, candidate) in candidates.withIndex()) {
                if (shouldAbort()) {
                    logger.info("NES: steering matrix aborted before variant=V${variantIndex + 1}")
                    return emptyResponse(autocompleteId, System.currentTimeMillis() - startTime)
                }
                val outcome =
                    runAutocompletePass(
                        filePath = request.filePath,
                        fileContents = fileContents,
                        originalFileContents = originalFileContents,
                        recentChanges = request.recentChanges,
                        cursorPosition = candidate.cursorPosition,
                        codeBlock = candidate.codeBlock,
                        prefix = candidate.prefix,
                        suffix = candidate.suffix,
                        blockStartIndex = candidate.blockStartIndex,
                        autocompleteId = autocompleteId,
                        fileChunks = fileChunks,
                        retrievalChunks = candidate.retrievalChunks,
                        recentChangesHighRes = request.recentChangesHighRes,
                        changesAboveCursor = request.changesAboveCursor,
                        steering = request.steering,
                        avoidCompletions = request.avoidCompletions,
                        temperature = temperature,
                        forceGhostText = forceGhostText,
                    )
                when (outcome) {
                    is AttemptOutcome.Success ->
                        return NesResponse(
                            outcome.completions,
                            System.currentTimeMillis() - startTime,
                            autocompleteId,
                        )
                    is AttemptOutcome.Avoided -> lastAvoided = outcome.completions
                    is AttemptOutcome.Aborted -> {
                        logger.info("NES: steering matrix aborted during variant=V${variantIndex + 1}")
                        return emptyResponse(autocompleteId, System.currentTimeMillis() - startTime)
                    }
                    else -> {}
                }
                if (steered) {
                    logger.info(
                        "NES: steering matrix variant=V${variantIndex + 1} " +
                            "round=${roundIndex + 1}/${rounds.size} temperature=$temperature — no fresh result",
                    )
                }
            }
        }

        // The whole matrix is exhausted: hand back the last avoid-matching
        // result so the client-side dedup + cached-completion cycling can take
        // over (Python parity for the final ladder attempt).
        lastAvoided?.let {
            logger.info("NES: returning ${it.size} completions (avoided match, matrix exhausted)")
            return NesResponse(it, System.currentTimeMillis() - startTime, autocompleteId)
        }
        return emptyResponse(autocompleteId, System.currentTimeMillis() - startTime)
    }

    /** One NES inference input: a code block context plus its cursor and chunks. */
    private data class PassCandidate(
        val codeBlock: String,
        val prefix: String,
        val suffix: String,
        val cursorPosition: Int,
        val blockStartIndex: Int,
        val retrievalChunks: List<NesPromptBuilder.FileChunkData>,
    )

    /**
     * Context variants for the steering matrix:
     * - V1: block at cursor (always)
     * - V2: best retrieval match (needs recent changes)
     * - V3: second retrieval match, or a wider window around the cursor
     * Automatic requests keep V1 + V2 only.
     */
    private fun buildPassCandidates(
        request: NesRequest,
        cursorBlock: NesPromptBuilder.BlockAtCursor,
        cursorPosition: Int,
        limitedRetrievalChunks: List<NesPromptBuilder.FileChunkData>,
        steered: Boolean,
    ): List<PassCandidate> {
        val candidates =
            mutableListOf(
                PassCandidate(
                    codeBlock = cursorBlock.codeBlock,
                    prefix = cursorBlock.prefix,
                    suffix = cursorBlock.suffix,
                    cursorPosition = cursorPosition,
                    blockStartIndex = cursorBlock.blockStartIndex,
                    retrievalChunks = limitedRetrievalChunks,
                ),
            )

        if (request.recentChanges.isEmpty()) {
            if (steered) {
                buildWiderCursorCandidate(request.fileContents, cursorPosition, limitedRetrievalChunks, cursorBlock.codeBlock)
                    ?.let { candidates.add(it) }
            }
            return candidates
        }

        val maxRetrievalVariants = if (steered) 2 else 1
        val retrievalVariants =
            NesRetrieval
                .findCandidateBlocks(
                    request.fileContents,
                    request.recentChanges,
                    cursorPosition,
                    blockSize = 6,
                    editorDiagnostics = request.editorDiagnostics,
                )
                .filter { it.blockStartOffset != cursorBlock.blockStartIndex }
                .take(maxRetrievalVariants)
                .mapNotNull { buildRetrievalCandidate(request.fileContents, it, limitedRetrievalChunks) }
        candidates.addAll(retrievalVariants)

        // Not enough distinct retrieval variants — widen the window as V3.
        if (steered && candidates.size < 3) {
            buildWiderCursorCandidate(request.fileContents, cursorPosition, limitedRetrievalChunks, cursorBlock.codeBlock)
                ?.let { candidates.add(it) }
        }
        return candidates
    }

    /** Builds the prompt inputs for one retrieval-matched context variant. */
    private fun buildRetrievalCandidate(
        fileContents: String,
        retrieval: NesRetrieval.RetrievalResult,
        limitedRetrievalChunks: List<NesPromptBuilder.FileChunkData>,
    ): PassCandidate? {
        val prefixLines = fileContents.substring(0, retrieval.blockStartOffset)
            .linesSplitKeepEnds()
        val retrievedPrefix = prefixLines.takeLast(NUM_LINES_BEFORE).joinToString("")

        val numRetrievedLines = retrieval.codeBlock.lines().size
        val numSuffixLines = max(0, NUM_LINES_AFTER + 1 - numRetrievedLines)
        val afterBlock = fileContents.substring(
            min(fileContents.length, retrieval.blockStartOffset + retrieval.codeBlock.length)
        )
        val retrievedSuffix = afterBlock.linesSplitKeepEnds().take(numSuffixLines).joinToString("")

        val cursorInBlock = retrieval.blockStartOffset +
            retrieval.codeBlock.linesSplitKeepEnds().firstOrNull()?.length.let { it ?: 0 }

        val fullBlock = retrievedPrefix + NesPromptBuilder.truncateCodeBlockByTokensPublic(
            retrieval.codeBlock + retrievedSuffix
        )
        if (NesUtils.shouldDisableForCodeBlock(fullBlock)) return null

        // Add diagnostic as retrieval chunk if present
        val extraChunks =
            if (retrieval.diagnostic != null) {
                val diagLine = fileContents.lines().getOrElse(retrieval.diagnostic.lineNumber) { "" }
                listOf(
                    NesPromptBuilder.FileChunkData(
                        "diagnostics",
                        "${retrieval.diagnostic.message} at line ${retrieval.diagnostic.lineNumber}:\n$diagLine",
                        1, 2,
                    )
                ) + limitedRetrievalChunks
            } else {
                limitedRetrievalChunks
            }

        return PassCandidate(
            codeBlock = fullBlock,
            prefix = retrievedPrefix,
            suffix = retrievedSuffix,
            cursorPosition = cursorInBlock,
            blockStartIndex = retrieval.blockStartOffset,
            retrievalChunks = extraChunks,
        )
    }

    /** V3 fallback: the same edit position seen through a wider window. */
    private fun buildWiderCursorCandidate(
        fileContents: String,
        cursorPosition: Int,
        limitedRetrievalChunks: List<NesPromptBuilder.FileChunkData>,
        cursorBlockCode: String,
    ): PassCandidate? {
        val wider =
            NesPromptBuilder.getBlockAtCursor(
                fileContents,
                cursorPosition,
                numLinesBefore = WIDER_NUM_LINES_BEFORE,
                numLinesAfter = WIDER_NUM_LINES_AFTER,
            )
        if (wider.codeBlock == cursorBlockCode) return null
        if (NesUtils.shouldDisableForCodeBlock(wider.codeBlock)) return null
        return PassCandidate(
            codeBlock = wider.codeBlock,
            prefix = wider.prefix,
            suffix = wider.suffix,
            cursorPosition = cursorPosition,
            blockStartIndex = wider.blockStartIndex,
            retrievalChunks = limitedRetrievalChunks,
        )
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
        steering: String?,
        avoidCompletions: List<String>,
        temperature: Float,
        forceGhostText: Boolean,
    ): AttemptOutcome {
        if (codeBlock.isEmpty()) return AttemptOutcome.Filtered("empty_code_block")

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
            steering = steering,
            forceGhostText = forceGhostText,
            useRemoteEndpoint = false,  // local llama-server
        )

        if (promptResult.formattedPrompt.isEmpty()) return AttemptOutcome.Filtered("empty_prompt")

        // Log prompt details for debugging
        logger.info("NES: prompt length=${promptResult.formattedPrompt.length} chars, " +
            "codeBlock length=${promptResult.cleanedCodeBlock.length}, " +
            "relativeCursorPos=${promptResult.relativeCursorPosition}, " +
            "relativeCursorLine=${promptResult.relativeCursorLine}, " +
            "blockStartIndex=${promptResult.blockStartIndex}")
        // Log the last ~200 chars of the prompt (the part right before model generation)
        val promptTail = promptResult.formattedPrompt.takeLast(300)
        logger.info("NES: prompt tail: ...${promptTail.replace("\n", "\\n")}")

        // Allow output up to 2x the code block size (room for insertions) + 20 lines buffer
        val maxOutputChars = (promptResult.cleanedCodeBlock.length * 2) + (20 * 80)

        return generateAndProcessAttempt(
            promptResult = promptResult,
            fileContents = fileContents,
            cursorPosition = cursorPosition,
            autocompleteId = autocompleteId,
            maxOutputChars = maxOutputChars,
            temperature = temperature,
            avoidCompletions = avoidCompletions,
        )
    }

    /** Result of a single generation + post-processing attempt. */
    private sealed class AttemptOutcome {
        /** Newer request superseded this one or inference failed — abort the pass. */
        object Aborted : AttemptOutcome()

        /** Model produced no text — retryable. */
        data class EmptyText(val reason: String) : AttemptOutcome()

        /** Post-processing filters dropped the completion — retryable. */
        data class Filtered(val reason: String) : AttemptOutcome()

        /** Valid hunks, but the completion repeats an avoided suggestion. */
        data class Avoided(val completions: List<AutocompleteResult>) : AttemptOutcome()

        /** Valid, fresh hunks. */
        data class Success(val completions: List<AutocompleteResult>) : AttemptOutcome()
    }

    private fun generateAndProcessAttempt(
        promptResult: NesPromptBuilder.PromptBuildResult,
        fileContents: String,
        cursorPosition: Int,
        autocompleteId: String,
        maxOutputChars: Int,
        temperature: Float,
        avoidCompletions: List<String>,
    ): AttemptOutcome {
        val completionResult =
            try {
                llamaClient.generateCompletion(
                    prompt = promptResult.formattedPrompt,
                    maxOutputChars = maxOutputChars,
                    temperature = temperature,
                )
            } catch (e: LlamaServerClient.RequestCancelledException) {
                logger.info("NES request cancelled")
                return AttemptOutcome.Aborted
            } catch (e: Exception) {
                logger.warn("NES inference error: ${e.message}")
                return AttemptOutcome.Aborted
            }

        if (completionResult.text.isEmpty()) {
            logger.warn("NES: empty completion text")
            return AttemptOutcome.EmptyText("no_completion")
        }

        // Post-process completion
        var completion = promptResult.prefill + completionResult.text
        logger.info("NES: raw completion (${completionResult.text.length} chars, finish=${completionResult.finishReason}): ${completionResult.text.take(200)}")
        logger.info("NES: prefill='${promptResult.prefill.take(50)}', forcedPrefix='${promptResult.forcedPrefix.take(50)}'")

        if (completion.startsWith("<|") || completion.removePrefix(promptResult.forcedPrefix).startsWith("<|")) {
            logger.warn("NES: filtered — completion starts with special token")
            return AttemptOutcome.Filtered("special_token")
        }
        if (promptResult.forcedPrefix.isNotEmpty() && !completionResult.text.startsWith(promptResult.forcedPrefix)) {
            logger.warn("NES: filtered — forced prefix '${promptResult.forcedPrefix.take(30)}' not respected")
            return AttemptOutcome.Filtered("forced_prefix")
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
            return AttemptOutcome.Filtered("max_tokens")
        }

        // Check for pure insertion above cursor
        if (NesCompletionParser.isPureInsertionAboveCursor(
                promptResult.cleanedCodeBlock, completion, promptResult.relativeCursorPosition
            )
        ) {
            logger.warn("NES: filtered — pure insertion above cursor")
            return AttemptOutcome.Filtered("pure_insertion_above_cursor")
        }

        // Check for large diff above cursor
        if (NesUtils.isLargeDiffAboveCursor(
                promptResult.cleanedCodeBlock, completion, promptResult.relativeCursorPosition
            )
        ) {
            logger.warn("NES: filtered — large diff above cursor")
            return AttemptOutcome.Filtered("large_diff")
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
            return AttemptOutcome.Filtered("no_hunks")
        }

        // Check for reverts — but pure insertions at the cursor are ghost
        // text: re-completing text the user just deleted is exactly what
        // autocomplete is for. (The Python library's comment intended ghost
        // texts to be exempt; the port filtered everything.)
        if (!NesUtils.isGhostTextInsertionOnly(completions, cursorPosition)) {
            val codeBlockWithCompletions = NesCompletionParser.applyCompletionsToCodeBlock(
                completions, fileContents, promptResult.cleanedCodeBlock,
            )
            for (section in promptResult.prevSections) {
                if (NesUtils.isEqualIgnoringNewlines(codeBlockWithCompletions, section)) {
                    logger.warn("NES: filtered — revert detected")
                    return AttemptOutcome.Filtered("revert")
                }
            }
        }

        // Duplicate-avoidance at the sampling level (Python parity): a steered
        // generation that repeats an avoided suggestion is retried on a hotter
        // temperature instead of being shown.
        if (NesUtils.matchesAvoidedCompletion(
                continuation = completionResult.text,
                prefix = promptResult.prefill,
                avoidedCompletions = avoidCompletions,
            )
        ) {
            logger.info("NES: completion matches an avoided suggestion")
            return AttemptOutcome.Avoided(completions)
        }

        logger.info("NES: returning ${completions.size} completions successfully")
        return AttemptOutcome.Success(completions)
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
