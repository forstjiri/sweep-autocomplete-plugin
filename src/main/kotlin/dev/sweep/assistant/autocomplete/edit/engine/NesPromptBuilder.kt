package dev.sweep.assistant.autocomplete.edit.engine

import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.CHARS_PER_TOKEN
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.NUM_LINES_AFTER
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.NUM_LINES_BEFORE
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.AUTOCOMPLETE_OUTPUT_MAX_TOKENS
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
        val blockStartIndex: Int,
    )

    private data class RawBlock(
        val codeBlock: String,
        val startLine: Int,
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
        val estimatedTokens: Int = 0,
        val contextSelection: String = "unavailable",
        val skipReason: String? = null,
        val sectionEstimates: Map<String, Int> = emptyMap(),
    )

    /**
     * Extract the code block surrounding the cursor position.
     * Ported from Python get_block_at_cursor(); window sized per the Sweep
     * blog (10 lines above / 10 below the cursor).
     */
    fun getBlockAtCursor(fileContents: String, cursorPosition: Int): BlockAtCursor {
        val lines = fileContents.linesSplitKeepEnds()
        val cursorLine = NesUtils.getLineNumberFromPosition(fileContents, cursorPosition)
        val rawBlock = getBlockAroundCursorLine(lines, cursorLine, NUM_LINES_BEFORE, NUM_LINES_AFTER)
        val blockStartIndex = lines.take(rawBlock.startLine).sumOf { it.length }

        val truncatedBlock = truncateCodeBlockByTokens(rawBlock.codeBlock)

        return BlockAtCursor(truncatedBlock, blockStartIndex)
    }

    private fun getBlockAroundCursorLine(
        lines: List<String>,
        cursorLine: Int,
        numLinesBefore: Int,
        numLinesAfter: Int,
    ): RawBlock {
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
        if (currentBlock.endsWith("\n")) {
            currentBlock = currentBlock.trimEnd('\n') + "\n"
        }

        return RawBlock(currentBlock, blockStart)
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
     * @param prefillTyping Preserve unchanged code before the typing cursor in the output prefix
     */
    fun buildPrompt(
        filePath: String,
        fileContents: String,
        originalFileContents: String,
        recentChanges: String,
        cursorPosition: Int,
        codeBlock: String,
        blockStartIndex: Int,
        fileChunks: List<FileChunkData> = emptyList(),
        retrievalChunks: List<FileChunkData> = emptyList(),
        recentChangesHighRes: String = "",
        steering: String? = null,
        prefillTyping: Boolean = false,
        policy: NesRequestPolicy = NesRequestPolicy.AUTOMATIC,
        shouldAbort: () -> Boolean = { false },
        structuralOutline: List<NesStructuralOutline.Declaration>? = null,
    ): PromptBuildResult {
        val relativeCursorPosition = cursorPosition - blockStartIndex
        if (blockStartIndex < 0 ||
            relativeCursorPosition !in 0..codeBlock.length ||
            fileContents.regionMatches(blockStartIndex, codeBlock, 0, codeBlock.length).not()
        ) {
            return PromptBuildResult(
                "", codeBlock, "", "", emptyList(),
                relativeCursorPosition, 0, blockStartIndex,
            )
        }
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
        val prefill: String
        val forcedPrefix: String

        if (prefillTyping && relativeCursorPosition > 0) {
            val prefillCandidate = cleanedCodeBlock.substring(0, relativeCursorPosition)
            val pretokens = NesUtils.pretokenize(prefillCandidate)
            val regexBasedPrefill = if (pretokens.size > 1) pretokens.dropLast(1).joinToString("") else ""
            prefill = regexBasedPrefill
            forcedPrefix = cleanedCodeBlock.substring(0, relativeCursorPosition).removePrefix(prefill)
        } else {
            prefill = ""
            forcedPrefix = ""
        }

        var formattedCodeBlock = codeBlockWithCursor
        var formattedPrevSection = prevSection
        if (formattedCodeBlock.endsWith("\n") && formattedPrevSection.endsWith("\n")) {
            formattedCodeBlock = formattedCodeBlock.removeSuffix("\n")
            formattedPrevSection = formattedPrevSection.removeSuffix("\n")
        }
        val header = "<|file_sep|>$filePath\n"
        val suffix = "\n\n<|file_sep|>original/$filePath:${relativeCursorLine + 1}:${relativeCursorLine + formattedCodeBlock.lines().size + 1}\n$formattedPrevSection" +
            "\n<|file_sep|>current/$filePath:${relativeCursorLine + 1}:${relativeCursorLine + formattedCodeBlock.lines().size + 1}\n$formattedCodeBlock" +
            "\n<|file_sep|>updated/$filePath:${relativeCursorLine + 1}:${relativeCursorLine + formattedCodeBlock.lines().size + 1}\n" +
            (steering?.let { "\n<steering>\n$it\n</steering>" } ?: "") +
            (if (prefill.isNotEmpty() && steering != null) "\n" else "") + prefill
        val maxChars = (policy.inputTokens * CHARS_PER_TOKEN).toInt()
        var remaining = maxChars - header.length - suffix.length
        var primary = ""
        var selection = "full"
        val auxiliary = StringBuilder()
        val sectionChars = linkedMapOf("mandatory" to header.length + suffix.length)
        if (remaining >= 0 && !shouldAbort()) {
            if (originalFileContents.length <= remaining) {
                primary = originalFileContents
            } else {
                selection = "window_outline"
                val lines = originalFileContents.linesSplitKeepEnds()
                val cursorLine = NesUtils.getLineNumberFromPosition(originalFileContents, cursorPosition.coerceAtMost(originalFileContents.length))
                val stride = policy.windowLines / 2
                val windowStart = ((cursorLine - stride).coerceAtLeast(0) / stride) * stride
                val windowEnd = (windowStart + policy.windowLines).coerceAtMost(lines.size)
                val windowOffset = lines.take(windowStart).sumOf { it.length }
                val windowEndOffset = lines.take(windowEnd).sumOf { it.length }
                val outlineStart = System.nanoTime()
                val outline = structuralOutline ?: if (filePath.endsWith(".php", true)) NesStructuralOutline.extract(fileContents, cursorPosition, shouldAbort) else emptyList()
                sectionChars["outlineExtractionMs"] = ((System.nanoTime() - outlineStart) / 1_000_000).toInt()
                val selected = StringBuilder()
                val outlineLimit = min((policy.outlineTokens * CHARS_PER_TOKEN).toInt(), remaining / 2)
                for (declaration in outline) {
                    if (declaration.start >= windowOffset && declaration.end <= windowEndOffset) continue
                    val entry = declaration.text + "\n"
                    if (selected.length + entry.length + 24 <= outlineLimit) selected.append(entry)
                }
                val outlineText = if (selected.isEmpty()) "" else "\n/* Structural outline */\n$selected"
                sectionChars["outline"] = outlineText.length
                val available = remaining - outlineText.length
                // Keep complete lines nearest the cursor when the nominal window is too large.
                var from = windowStart
                var to = windowEnd
                var length = lines.subList(from, to).sumOf { it.length }
                while (length > available && from < to) {
                    if (cursorLine - from > to - cursorLine - 1) length -= lines[from++].length
                    else length -= lines[--to].length
                }
                primary = lines.subList(from, to).joinToString("") + outlineText
            }
            sectionChars["primary"] = primary.length - (sectionChars["outline"] ?: 0)
            remaining -= primary.length
            // Newest changes have priority; preserve whole serialized diff chunks.
            val diffs = onlyChangedLines.split("<|file_sep|>").filter { it.isNotBlank() }.map { "<|file_sep|>$it" }
            val selectedDiffs = mutableListOf<String>()
            for (diff in diffs.asReversed()) {
                if (diff.length + 1 <= remaining) { selectedDiffs.add(diff); remaining -= diff.length + 1 }
            }
            sectionChars["recentChanges"] = selectedDiffs.sumOf { it.length + 1 }
            selectedDiffs.asReversed().forEach { auxiliary.append("\n").append(it) }
            val seen = mutableSetOf<Pair<String, String>>()
            fun pack(name: String, chunks: List<FileChunkData>, tokens: Int, count: Int) {
                val before = auxiliary.length
                var allowance = min(remaining, (tokens * CHARS_PER_TOKEN).toInt())
                var added = 0
                for (chunk in chunks) {
                    if (shouldAbort()) break
                    if (chunk.filePath == filePath || chunk.content.isBlank() || !seen.add(chunk.filePath to chunk.content)) continue
                    val serialized = "\n" + chunk.toPromptString()
                    if (serialized.length > allowance) continue
                    auxiliary.append(serialized)
                    allowance -= serialized.length
                    remaining -= serialized.length
                    if (++added >= count) break
                }
                sectionChars[name] = auxiliary.length - before
            }
            pack("retrieval", retrievalChunks, policy.retrievalTokens, policy.retrievalChunks)
            pack("other", fileChunks, policy.otherTokens, 1)
        }
        val formattedPrompt = if (remaining < 0 || shouldAbort()) "" else header + primary + auxiliary + suffix
        return PromptBuildResult(
            formattedPrompt, cleanedCodeBlock, prefill, forcedPrefix, prevSections,
            relativeCursorPosition, relativeCursorLine, blockStartIndex,
            kotlin.math.ceil(formattedPrompt.length / CHARS_PER_TOKEN).toInt(), selection,
            if (remaining < 0) "mandatory_overflow" else if (shouldAbort()) "cancelled_or_timeout" else null,
            sectionChars.mapValues { (key, value) -> if (key.endsWith("Ms")) value else kotlin.math.ceil(value / CHARS_PER_TOKEN).toInt() },
        )
    }

}
