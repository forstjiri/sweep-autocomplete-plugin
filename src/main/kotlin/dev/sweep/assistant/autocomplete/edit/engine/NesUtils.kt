package dev.sweep.assistant.autocomplete.edit.engine

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.AUTOCOMPLETE_MAXIMUM_LINE_LENGTH
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.AUTOCOMPLETE_OUTPUT_MAX_TOKENS
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.AUTOCOMPLETE_TRUNCATION_LINE_LENGTH
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.CHARS_PER_TOKEN
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.CHUNK_SIZE
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.CHUNK_STRIDE
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.LIMIT_TO_CHUNK
import dev.sweep.assistant.autocomplete.edit.engine.NesConstants.NUM_LINES_BEFORE
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure utility functions for the NES engine.
 * Ported from Python next_edit_autocomplete_utils.py.
 * No IntelliJ dependencies — fully unit-testable.
 */
object NesUtils {

    /** Estimate token count using character-based approximation. */
    fun estimateTokenCount(text: String): Int = (text.length / CHARS_PER_TOKEN).toInt()

    /**
     * Convert a character position to a 0-indexed line number.
     * Equivalent to Python: file_contents[:position].count("\n")
     */
    fun getLineNumberFromPosition(fileContents: String, position: Int): Int {
        if (position <= 0) return 0
        if (position >= fileContents.length) {
            return fileContents.lines().size - 1
        }
        return fileContents.substring(0, position).count { it == '\n' }
    }

    /**
     * Return a chunk of the file centered around the cursor position.
     * For files <= LIMIT_TO_CHUNK lines, returns the full file.
     * Ported from Python get_lines_around_cursor().
     */
    fun getLinesAroundCursor(fileContents: String, cursorPosition: Int): String {
        val lines = fileContents.split("\n")

        if (lines.size <= LIMIT_TO_CHUNK) return fileContents

        val cursorLine = getLineNumberFromPosition(fileContents, cursorPosition)
        val idealStart = cursorLine - CHUNK_SIZE / 2
        val chunkIndex = max(0, (idealStart.toDouble() / CHUNK_STRIDE).roundToInt())
        val startLine = chunkIndex * CHUNK_STRIDE
        val endLine = min(lines.size, startLine + CHUNK_SIZE)

        return lines.subList(startLine, endLine).joinToString("\n")
    }

    /**
     * Extract old and new code from a unified diff hunk.
     * @param hunk The diff hunk string (may start with @@ header)
     * @param numContextLines Number of context lines to keep (0 = changed lines only, -1 = all)
     * @return Pair of (oldCode, newCode)
     */
    fun extractDiffParts(hunk: String, numContextLines: Int = 0): Pair<String, String> {
        // Use splitlines-keep-ends to match Python's splitlines(True)
        val allLines = hunk.linesSplitKeepEnds()
        val contentLines = allLines.filter { !it.startsWith("@@") }

        if (numContextLines == -1) {
            val oldCode = mutableListOf<String>()
            val newCode = mutableListOf<String>()
            for (line in contentLines) {
                when {
                    line.startsWith("-") -> oldCode.add(line.substring(1))
                    line.startsWith("+") -> newCode.add(line.substring(1))
                    line.isNotEmpty() -> {
                        val content = if (line.startsWith(" ")) line.substring(1) else line
                        oldCode.add(content)
                        newCode.add(content)
                    }
                }
            }
            return oldCode.joinToString("") to newCode.joinToString("")
        }

        val changedIndices = contentLines.indices.filter {
            contentLines[it].startsWith("-") || contentLines[it].startsWith("+")
        }

        if (changedIndices.isEmpty()) return "" to ""

        val startChange = changedIndices.min()
        val endChange = changedIndices.max()
        val startIdx = max(0, startChange - numContextLines)
        val endIdx = min(contentLines.size, endChange + numContextLines + 1)

        val relevantLines = contentLines.subList(startIdx, endIdx)
        val oldCode = mutableListOf<String>()
        val newCode = mutableListOf<String>()

        for (line in relevantLines) {
            when {
                line.startsWith("-") -> oldCode.add(line.substring(1))
                line.startsWith("+") -> newCode.add(line.substring(1))
                line.isNotEmpty() -> {
                    val content = if (line.startsWith(" ")) line.substring(1) else line
                    oldCode.add(content)
                    newCode.add(content)
                }
            }
        }

        return oldCode.joinToString("") to newCode.joinToString("")
    }

    /**
     * Filter out hunks that only contain whitespace changes.
     */
    fun filterWhitespaceOnlyHunks(hunks: List<String>): List<String> {
        return hunks.filter { hunk ->
            val lines = hunk.lines()
            val rest = lines.drop(1).joinToString("\n")
            val (oldCode, newCode) = extractDiffParts(rest)
            oldCode.trim() != newCode.trim()
        }
    }

    /**
     * Split a diff string into individual hunks by "File: " markers.
     */
    fun splitIntoHunks(diff: String): List<String> {
        val hunks = mutableListOf<String>()
        val currentHunk = mutableListOf<String>()

        for (line in diff.lines()) {
            if (line.startsWith("File: ") && currentHunk.isNotEmpty()) {
                hunks.add(currentHunk.joinToString("\n"))
                currentHunk.clear()
            }
            currentHunk.add(line)
        }
        if (currentHunk.isNotEmpty()) {
            hunks.add(currentHunk.joinToString("\n"))
        }
        return hunks
    }

    /** Strip leading empty lines from a completion string. */
    fun stripLeadingEmptyNewlines(completion: String): String {
        val lines = completion.split("\n")
        val startIndex = lines.indexOfFirst { it.trim().isNotEmpty() }
        return if (startIndex >= 0) lines.drop(startIndex).joinToString("\n") else completion
    }

    data class HunkParseResult(
        val inputStart: Int,
        val inputLines: List<String>,
        val outputStart: Int,
        val outputLines: List<String>,
    )

    /**
     * Parse a single diff hunk and return input/output line numbers and content.
     * Ported from Python parse_hunk().
     */
    fun parseHunk(hunk: String): HunkParseResult {
        val lines = hunk.lines()
        val hunkHeader = lines[0]
        val diffLines = if (lines.size > 2) lines.drop(2) else emptyList()

        val parts = hunkHeader.split(" ")
        val inputRange = parts[1].trimStart('-')
        val outputRange = parts[2].trimStart('+')

        val inputParts = inputRange.split(",")
        val outputParts = outputRange.split(",")

        var inputStart = inputParts[0].toInt()
        val outputStart = outputParts[0].toInt()

        val inputLinesList = mutableListOf<String>()
        val outputLinesList = mutableListOf<String>()

        for (line in diffLines) {
            when {
                line.startsWith("-") -> inputLinesList.add(line.substring(1))
                line.startsWith("+") -> outputLinesList.add(line.substring(1))
                line.isNotEmpty() -> {
                    val content = if (line.startsWith(" ")) line.substring(1) else line
                    inputLinesList.add(content)
                    outputLinesList.add(content)
                }
            }
        }

        // Edge case: Python's difflib has an off-by-one when input_lines is empty
        if (inputLinesList.isEmpty()) {
            inputStart += 1
        }

        return HunkParseResult(inputStart, inputLinesList, outputStart, outputLinesList)
    }

    /**
     * Split two strings into diff hunks using java-diff-utils.
     * Equivalent to Python split_into_diff_hunks() with difflib.unified_diff(n=0).
     *
     * Uses the Patch deltas directly rather than parsing unified diff text,
     * to avoid line-number format differences between java-diff-utils and Python difflib.
     */
    fun splitIntoDiffHunks(inputContent: String, outputContent: String): List<HunkParseResult> {
        val inputLines = inputContent.linesSplitKeepEnds()
        val outputLines = outputContent.linesSplitKeepEnds()

        val patch = DiffUtils.diff(inputLines, outputLines)
        if (patch.deltas.isEmpty()) return emptyList()

        return patch.deltas.map { delta ->
            // java-diff-utils uses 0-based positions; Python difflib uses 1-based
            var inputStart = delta.source.position + 1
            val inputLinesList = delta.source.lines.map { it }
            val outputStart = delta.target.position + 1
            val outputLinesList = delta.target.lines.map { it }

            // Match Python's off-by-one edge case when input_lines is empty
            if (inputLinesList.isEmpty()) {
                inputStart += 1
            }

            HunkParseResult(inputStart, inputLinesList, outputStart, outputLinesList)
        }
    }

    /**
     * Check if the completion has a large diff above the cursor position.
     * Large = more than 5 lines added AND more than 5 lines deleted.
     */
    fun isLargeDiffAboveCursor(
        original: String,
        completion: String,
        relativeCursorPosition: Int,
    ): Boolean {
        if (original == completion) return false

        val originalAbove = original.substring(0, min(relativeCursorPosition, original.length))
        val completionAbove = completion.substring(0, min(relativeCursorPosition, completion.length))

        if (originalAbove == completionAbove) return false

        val patch = DiffUtils.diff(
            original.lines(),
            completion.lines()
        )

        var additions = 0
        var deletions = 0
        for (delta in patch.deltas) {
            additions += delta.target.lines.size
            deletions += delta.source.lines.size
        }

        return additions > 5 && deletions > 5
    }

    /** Truncate lines exceeding AUTOCOMPLETE_TRUNCATION_LINE_LENGTH. */
    fun truncateLongLines(content: String): String {
        return content.lines().joinToString("\n") { line ->
            if (line.length > AUTOCOMPLETE_TRUNCATION_LINE_LENGTH) {
                line.substring(0, AUTOCOMPLETE_TRUNCATION_LINE_LENGTH) + "..."
            } else {
                line
            }
        }
    }

    /** Check if any line in the code block exceeds AUTOCOMPLETE_MAXIMUM_LINE_LENGTH. */
    fun shouldDisableForCodeBlock(codeBlock: String): Boolean {
        return codeBlock.lines().any { it.length > AUTOCOMPLETE_MAXIMUM_LINE_LENGTH }
    }

    /** Normalize consecutive newlines to single newlines. */
    fun normalizeNewlines(text: String): String {
        return text.replace(Regex("\\n+"), "\n")
    }

    /** Compare two strings ignoring differences in consecutive newlines. */
    fun isEqualIgnoringNewlines(text1: String, text2: String): Boolean {
        return normalizeNewlines(text1) == normalizeNewlines(text2)
    }

    /**
     * Pack items from an iterable into a list, respecting a token limit.
     * Ported from Python pack_items_for_prompt().
     */
    fun <T> packItemsForPrompt(
        items: List<T>,
        stringFunction: (T) -> String,
        tokenLimit: Int,
        charTokenRatio: Double = 3.5,
        truncateFromEnd: Boolean = true,
    ): List<T> {
        val charLimit = (tokenLimit * charTokenRatio).toInt()
        val packed = mutableListOf<T>()
        var currentLen = 0

        val orderedItems = if (truncateFromEnd) items else items.reversed()

        for (item in orderedItems) {
            val itemStr = stringFunction(item)
            if (currentLen + itemStr.length <= charLimit) {
                if (truncateFromEnd) packed.add(item) else packed.add(0, item)
                currentLen += itemStr.length
            } else {
                break
            }
        }
        return packed
    }

    /**
     * Check if completion length suggests it hit max_tokens.
     */
    fun isCompletionMaxTokens(completion: String): Boolean {
        val tokenCount = (completion.length / CHARS_PER_TOKEN).toInt()
        return tokenCount >= AUTOCOMPLETE_OUTPUT_MAX_TOKENS
    }

    /**
     * Extract minimal diff between original and new code.
     * Returns (minimalNew, startOffset, endOffset).
     */
    fun extractMinimalDiff(originalCode: String, newCode: String): Triple<String, Int, Int> {
        val originalLines = originalCode.linesSplitKeepEnds()
        val newLines = newCode.linesSplitKeepEnds()

        var startDiff = 0
        while (startDiff < min(originalLines.size, newLines.size) &&
            originalLines[startDiff] == newLines[startDiff]
        ) {
            startDiff++
        }

        var endDiffOrig = originalLines.size - 1
        var endDiffNew = newLines.size - 1
        while (endDiffOrig >= startDiff && endDiffNew >= startDiff &&
            endDiffOrig >= 0 && endDiffNew >= 0 &&
            originalLines[endDiffOrig] == newLines[endDiffNew]
        ) {
            endDiffOrig--
            endDiffNew--
        }

        val startContext = max(0, startDiff - 1)
        val endContextOrig = min(originalLines.size - 1, endDiffOrig + 1)
        val endContextNew = min(newLines.size - 1, endDiffNew + 1)

        var startOffset = originalLines.take(startContext).sumOf { it.length }
        val endOffset = originalLines.take(endContextOrig + 1).sumOf { it.length }

        var minimalNew = newLines.subList(startContext, endContextNew + 1).joinToString("")
        if (minimalNew.startsWith("\n")) {
            minimalNew = minimalNew.substring(1)
            startOffset += 1
        }

        return Triple(minimalNew, startOffset, endOffset)
    }

    // Pretokenizer regex compiled once
    private val pretokenizePattern: Pattern by lazy {
        Pattern.compile(NesConstants.PRETOKENIZE_REGEX, Pattern.UNICODE_CHARACTER_CLASS)
    }

    /**
     * Tokenize text using the Qwen2 pretokenizer regex.
     * Returns the list of pretokens.
     */
    fun pretokenize(text: String): List<String> {
        val matcher = pretokenizePattern.matcher(text)
        val tokens = mutableListOf<String>()
        while (matcher.find()) {
            tokens.add(matcher.group())
        }
        return tokens
    }
}

/**
 * Split a string into lines, keeping line endings (like Python's splitlines(True)).
 */
fun String.linesSplitKeepEnds(): List<String> {
    if (this.isEmpty()) return listOf("")
    val result = mutableListOf<String>()
    var start = 0
    for (i in indices) {
        if (this[i] == '\n') {
            result.add(this.substring(start, i + 1))
            start = i + 1
        }
    }
    if (start < length) {
        result.add(this.substring(start))
    }
    return result
}
