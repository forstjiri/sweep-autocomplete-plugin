package dev.sweep.assistant.autocomplete.edit.engine

import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.CHARS_PER_TOKEN
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.NUM_LINES_AFTER
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.NUM_LINES_BEFORE
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.AUTOCOMPLETE_OUTPUT_MAX_TOKENS
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.CHARACTER_BOUND_TO_CHECK_TOKENIZATION
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.CHARACTER_BOUND_TO_SKIP_TOKENIZATION
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.MAX_INPUT_TOKENS_COUNT
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.MAX_RETRIEVAL_TOKENS_COUNT
import kotlin.math.max
import kotlin.math.min

/**
 * Constructs prompts for the NES model from editor state.
 * Ported from Python _fetch_next_edits_core() prompt construction logic.
 * No IntelliJ dependencies — fully unit-testable.
 */
object NesPromptBuilder {

    data class BlockAtCursor(
        val codeBlock: String,
        val prefix: String,
        val suffix: String,
        val blockStartIndex: Int,
    )

    data class FileChunkData(
        val filePath: String,
        val content: String,
        val startLine: Int,
        val endLine: Int,
    ) {
        fun toPromptString(): String = "<|file_sep|>$filePath\n$content\n"
    }

    data class PromptBuildResult(
        val formattedPrompt: String,
        val cleanedCodeBlock: String,
        val prefill: String,
        val forcedPrefix: String,
        val prevSections: List<String>,
        val relativeCursorPosition: Int,
        val relativeCursorLine: Int,
        val blockStartIndex: Int,
    )

    /**
     * Extract the code block surrounding the cursor position.
     * Ported from Python get_block_at_cursor().
     */
    fun getBlockAtCursor(fileContents: String, cursorPosition: Int): BlockAtCursor {
        val lines = fileContents.linesSplitKeepEnds()
        val cursorLine = NesUtils.getLineNumberFromPosition(fileContents, cursorPosition)
        val (codeBlock, prefix, suffix) = getBlockAroundCursorLine(
            lines, cursorLine, NUM_LINES_BEFORE, NUM_LINES_AFTER
        )
        val blockStartLine = max(0, cursorLine - NUM_LINES_BEFORE)
        val blockStartIndex = lines.take(blockStartLine).sumOf { it.length }

        val truncatedBlock = truncateCodeBlockByTokens(codeBlock)

        return BlockAtCursor(truncatedBlock, prefix, suffix, blockStartIndex)
    }

    private fun getBlockAroundCursorLine(
        lines: List<String>,
        cursorLine: Int,
        numLinesBefore: Int,
        numLinesAfter: Int,
    ): Triple<String, String, String> {
        var blockStart = max(0, cursorLine - numLinesBefore)
        var blockEnd = min(lines.size, cursorLine + numLinesAfter + 1)

        while (blockStart < blockEnd && lines[blockStart].trim().isEmpty()) {
            blockStart++
            if (blockEnd < lines.size) blockEnd++
        }
        while (blockEnd > blockStart && lines[blockEnd - 1].trim().isEmpty()) {
            blockEnd--
        }

        var currentBlock = lines.subList(blockStart, blockEnd).joinToString("")
        val prefixStart = max(0, blockStart - 10)
        val prefix = lines.subList(prefixStart, blockStart).joinToString("").trim('\n')
        val suffixEnd = min(lines.size, blockEnd + 10)
        val suffix = lines.subList(blockEnd, suffixEnd).joinToString("").trim('\n')

        if (currentBlock.endsWith("\n")) {
            currentBlock = currentBlock.trimEnd('\n') + "\n"
        }

        return Triple(currentBlock, prefix, suffix)
    }

    /** Public access for the engine's retrieval pass. */
    fun truncateCodeBlockByTokensPublic(
        codeBlock: String,
        maxTokenLimit: Int = AUTOCOMPLETE_OUTPUT_MAX_TOKENS / 2,
    ): String = truncateCodeBlockByTokens(codeBlock, maxTokenLimit)

    private fun truncateCodeBlockByTokens(
        codeBlock: String,
        maxTokenLimit: Int = AUTOCOMPLETE_OUTPUT_MAX_TOKENS / 2,
    ): String {
        val codeBlockLines = codeBlock.linesSplitKeepEnds()
        val prefilledCodeBlock = codeBlockLines.take(NUM_LINES_BEFORE).joinToString("")
        val remainingCodeBlock = codeBlockLines.drop(NUM_LINES_BEFORE).joinToString("")
        val estimatedTokens = NesUtils.estimateTokenCount(remainingCodeBlock)

        if (estimatedTokens > maxTokenLimit) {
            val maxChars = (maxTokenLimit * CHARS_PER_TOKEN).toInt()
            val truncated = remainingCodeBlock.substring(0, min(maxChars, remainingCodeBlock.length))
            val truncatedLines = truncated.linesSplitKeepEnds()
            if (truncatedLines.size > 1) {
                return prefilledCodeBlock + truncatedLines.dropLast(1).joinToString("")
            }
        }
        return codeBlock
    }

    /**
     * Format recent changes into diff format and compute the previous section.
     * Ported from Python format_recent_changes_and_prev_section().
     */
    fun formatRecentChangesAndPrevSection(
        recentChanges: String,
        currentSection: String,
    ): Triple<String, String, List<String>> {
        val hunks = NesUtils.splitIntoHunks(recentChanges)
            .filter { it.trim().lines().size > 1 }
            .let { NesUtils.filterWhitespaceOnlyHunks(it) }

        var prevSection = currentSection.replace("<|cursor|>", "")
        val prevSections = mutableListOf<String>()

        if (hunks.isNotEmpty()) {
            for (hunk in hunks.reversed()) {
                val firstLine = hunk.lines().first()
                val filePath = firstLine.removePrefix("File: ").trimEnd('\n')
                val rest = hunk.linesSplitKeepEnds().drop(1).joinToString("")
                val (oldCode, newCode) = NesUtils.extractDiffParts(rest)
                val (oldCodeCtx, newCodeCtx) = NesUtils.extractDiffParts(rest, 1)
                val parsed = NesUtils.parseHunk(rest)
                val startLine = parsed.inputStart
                val endLine = startLine + parsed.inputLines.size - 1

                if (newCodeCtx.trim().isNotEmpty() && newCodeCtx in prevSection) {
                    prevSection = prevSection.replaceFirst(newCodeCtx, oldCodeCtx)
                    prevSections.add(prevSection)
                } else if (newCode.trim().isNotEmpty() && newCode in prevSection) {
                    prevSection = prevSection.replaceFirst(newCode, oldCode)
                    prevSections.add(prevSection)
                } else {
                    break
                }
            }
        }

        // Format as diff_format
        var result = ""
        for (hunk in hunks.takeLast(6)) {
            val firstLine = hunk.lines().first()
            val filePath = firstLine.removePrefix("File: ").trimEnd('\n')
            val rest = hunk.linesSplitKeepEnds().drop(1).joinToString("")
            val (oldCode, newCode) = NesUtils.extractDiffParts(rest, 1)
            val parsed = NesUtils.parseHunk(rest)
            val startLine = parsed.inputStart
            val endLine = startLine + parsed.inputLines.size - 1

            if (oldCode.trim().isNotEmpty() || newCode.trim().isNotEmpty()) {
                result += NesConstants.DIFF_FORMAT
                    .replace("{old_code}", oldCode.trim('\n'))
                    .replace("{new_code}", newCode.trim('\n'))
                    .replace("{file_path}", filePath)
                    .replace("{start_line}", startLine.toString())
                    .replace("{end_line}", endLine.toString()) + "\n"
            }
        }

        return Triple(result.trimEnd('\n'), prevSection, prevSections)
    }

    /**
     * Build the full prompt for the NES model.
     *
     * @param filePath The file path
     * @param fileContents Current file contents
     * @param originalFileContents Original file contents (before recent edits)
     * @param recentChanges Recent diff changes
     * @param cursorPosition Cursor position in the file
     * @param codeBlock Code block around cursor (from getBlockAtCursor)
     * @param blockStartIndex Start index of the code block in the file
     * @param fileChunks Additional file chunks for context
     * @param retrievalChunks Retrieval chunks from similar code
     * @param recentChangesHighRes High-resolution recent changes
     * @param changesAboveCursor Whether recent changes are above the cursor
     * @param forceGhostText Whether to force ghost text mode
     * @param useRemoteEndpoint Whether a remote endpoint is being used (affects prefill logic)
     */
    fun buildPrompt(
        filePath: String,
        fileContents: String,
        originalFileContents: String,
        recentChanges: String,
        cursorPosition: Int,
        codeBlock: String,
        prefix: String,
        suffix: String,
        blockStartIndex: Int,
        fileChunks: List<FileChunkData> = emptyList(),
        retrievalChunks: List<FileChunkData> = emptyList(),
        recentChangesHighRes: String = "",
        changesAboveCursor: Boolean = false,
        forceGhostText: Boolean = false,
        useRemoteEndpoint: Boolean = false,
    ): PromptBuildResult {
        val relativeCursorPosition = cursorPosition - fileContents.indexOf(codeBlock)
        var cleanedCodeBlock = codeBlock
        val relativeCursorLine = NesUtils.getLineNumberFromPosition(codeBlock, relativeCursorPosition)

        // Insert cursor marker
        val codeBlockWithCursor = codeBlock.substring(0, relativeCursorPosition) +
            "<|cursor|>" +
            codeBlock.substring(relativeCursorPosition)

        val (onlyChangedLines, prevSection, prevSections0) =
            formatRecentChangesAndPrevSection(recentChanges, codeBlockWithCursor)

        val prevSections = if (recentChangesHighRes.isNotEmpty()) {
            formatRecentChangesAndPrevSection(recentChangesHighRes, codeBlockWithCursor).third
        } else {
            prevSections0
        }

        // Compute prefill and forced prefix
        val isAtEof = relativeCursorPosition == cleanedCodeBlock.length
        val prefill: String
        val forcedPrefix: String

        if (forceGhostText && !isAtEof && useRemoteEndpoint) {
            val prefillCandidate = cleanedCodeBlock.substring(0, relativeCursorPosition)
            val pretokens = NesUtils.pretokenize(prefillCandidate)
            val regexBasedPrefill = if (pretokens.size > 1) pretokens.dropLast(1).joinToString("") else ""
            prefill = regexBasedPrefill
            forcedPrefix = cleanedCodeBlock.substring(0, relativeCursorPosition).removePrefix(prefill)
        } else if (changesAboveCursor) {
            forcedPrefix = ""
            val prefillFull = cleanedCodeBlock.substring(0, relativeCursorPosition)
            val prefillLines = prefillFull.linesSplitKeepEnds()
            val numLinesAbove = 1
            var beforeSplit = prefillLines.take(numLinesAbove).joinToString("")
            val afterSplit = prefillLines.drop(numLinesAbove).joinToString("")
            for (char in afterSplit) {
                if (char == '\n') beforeSplit += "\n" else break
            }
            prefill = beforeSplit
        } else {
            prefill = ""
            forcedPrefix = ""
        }

        // Pack retrieval chunks
        val packedRetrievalChunks = NesUtils.packItemsForPrompt(
            retrievalChunks,
            { it.toPromptString() },
            MAX_RETRIEVAL_TOKENS_COUNT,
            truncateFromEnd = false,
        )
        val retrievalResults = packedRetrievalChunks.joinToString("") { "\n${it.toPromptString()}" }

        // Format code block and prev section
        var formattedCodeBlock = codeBlockWithCursor
        var formattedPrevSection = prevSection
        if (formattedCodeBlock.endsWith("\n") && formattedPrevSection.endsWith("\n")) {
            formattedCodeBlock = formattedCodeBlock.removeSuffix("\n")
            formattedPrevSection = formattedPrevSection.removeSuffix("\n")
        }

        val initialFile = NesUtils.getLinesAroundCursor(originalFileContents, cursorPosition)

        var formattedPrompt = NesConstants.PROMPT_TEMPLATE
            .replace("{file_path}", filePath)
            .replace("{recent_changes}", onlyChangedLines)
            .replace("{prev_section}", formattedPrevSection)
            .replace("{code_block}", formattedCodeBlock)
            .replace("{retrieval_results}", retrievalResults)
            .replace("{initial_file}", initialFile)
            .replace("{start_line}", (relativeCursorLine + 1).toString())
            .replace("{end_line}", (relativeCursorLine + formattedCodeBlock.lines().size + 1).toString()) +
            "\n$prefill"

        // Truncation logic
        val formattedFileChunks = fileChunks.joinToString("") { it.toPromptString() }

        if (formattedPrompt.length + formattedFileChunks.length > CHARACTER_BOUND_TO_CHECK_TOKENIZATION) {
            formattedPrompt = truncatePrompt(
                filePath, onlyChangedLines, formattedPrevSection, formattedCodeBlock,
                retrievalResults, initialFile, prefill, fileChunks,
                relativeCursorLine,
            ) ?: return PromptBuildResult(
                "", cleanedCodeBlock, prefill, forcedPrefix, prevSections,
                relativeCursorPosition, relativeCursorLine, blockStartIndex,
            )
        } else {
            formattedPrompt = formattedFileChunks + formattedPrompt
        }

        // Truncate long lines
        formattedPrompt = NesUtils.truncateLongLines(formattedPrompt)

        return PromptBuildResult(
            formattedPrompt, cleanedCodeBlock, prefill, forcedPrefix, prevSections,
            relativeCursorPosition, relativeCursorLine, blockStartIndex,
        )
    }

    private fun truncatePrompt(
        filePath: String,
        recentChanges: String,
        prevSection: String,
        codeBlock: String,
        retrievalResults: String,
        initialFile: String,
        prefill: String,
        fileChunks: List<FileChunkData>,
        relativeCursorLine: Int,
    ): String? {
        val minimalPrompt = NesConstants.PROMPT_TEMPLATE
            .replace("{file_path}", filePath)
            .replace("{recent_changes}", recentChanges)
            .replace("{prev_section}", prevSection)
            .replace("{code_block}", codeBlock)
            .replace("{retrieval_results}", "")
            .replace("{initial_file}", initialFile)
            .replace("{start_line}", (relativeCursorLine + 1).toString())
            .replace("{end_line}", (relativeCursorLine + codeBlock.lines().size + 1).toString()) +
            "\n$prefill"

        if (minimalPrompt.length > CHARACTER_BOUND_TO_SKIP_TOKENIZATION) return null

        val minimalTokens = NesUtils.estimateTokenCount(minimalPrompt)
        val retrievalTokens = NesUtils.estimateTokenCount(retrievalResults)
        val chunkTokens = fileChunks.map { NesUtils.estimateTokenCount(it.content) }

        // Case 1: everything fits
        if (minimalTokens + retrievalTokens + chunkTokens.sum() <= MAX_INPUT_TOKENS_COUNT) {
            val fullPrompt = NesConstants.PROMPT_TEMPLATE
                .replace("{file_path}", filePath)
                .replace("{recent_changes}", recentChanges)
                .replace("{prev_section}", prevSection)
                .replace("{code_block}", codeBlock)
                .replace("{retrieval_results}", retrievalResults)
                .replace("{initial_file}", initialFile)
                .replace("{start_line}", (relativeCursorLine + 1).toString())
                .replace("{end_line}", (relativeCursorLine + codeBlock.lines().size + 1).toString()) +
                "\n$prefill"
            return fileChunks.joinToString("") { it.toPromptString() } + fullPrompt
        }

        // Case 2: minimal prompt too long
        if (minimalTokens > MAX_INPUT_TOKENS_COUNT) return null

        // Case 3: drop all file chunks
        if (minimalTokens + retrievalTokens > MAX_INPUT_TOKENS_COUNT) return minimalPrompt

        // Case 4: fit as many file chunks as possible
        val promptWithRetrieval = NesConstants.PROMPT_TEMPLATE
            .replace("{file_path}", filePath)
            .replace("{recent_changes}", recentChanges)
            .replace("{prev_section}", prevSection)
            .replace("{code_block}", codeBlock)
            .replace("{retrieval_results}", retrievalResults)
            .replace("{initial_file}", initialFile)
            .replace("{start_line}", (relativeCursorLine + 1).toString())
            .replace("{end_line}", (relativeCursorLine + codeBlock.lines().size + 1).toString()) +
            "\n$prefill"

        var currentTokenCount = minimalTokens + retrievalTokens
        val partialChunks = StringBuilder()
        for ((chunk, tokens) in fileChunks.zip(chunkTokens)) {
            if (currentTokenCount + tokens >= MAX_INPUT_TOKENS_COUNT) break
            partialChunks.append(chunk.toPromptString())
            currentTokenCount += tokens
        }

        return partialChunks.toString() + promptWithRetrieval
    }
}
