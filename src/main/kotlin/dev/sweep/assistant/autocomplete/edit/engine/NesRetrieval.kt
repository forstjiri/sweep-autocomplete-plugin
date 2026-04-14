package dev.sweep.assistant.autocomplete.edit.engine

import com.github.difflib.DiffUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * Retrieval logic for finding code blocks related to recent edits.
 * Ported from Python next_edit_autocomplete_retrieval.py.
 * No IntelliJ dependencies — fully unit-testable.
 */
object NesRetrieval {

    private val WORD_PATTERN = Pattern.compile("\\w+", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS)
    private val WORD_BOUNDARY_PATTERN = Pattern.compile("\\w+|\\s+|[^\\w\\s]")

    // Simple LRU-ish caches (bounded ConcurrentHashMaps)
    private val tokenizerCache = ConcurrentHashMap<String, List<String>>(256)
    private val tokenizerWithOffsetsCache = ConcurrentHashMap<String, List<Triple<String, Int, Int>>>(256)

    /** Extract words from text (case-insensitive). Cached. */
    fun simpleTokenizer(text: String): List<String> {
        return tokenizerCache.getOrPut(text) {
            val matcher = WORD_PATTERN.matcher(text)
            val tokens = mutableListOf<String>()
            while (matcher.find()) tokens.add(matcher.group())
            tokens
        }
    }

    /** Extract words with character offsets. Cached. */
    fun simpleTokenizerWithOffsets(text: String): List<Triple<String, Int, Int>> {
        return tokenizerWithOffsetsCache.getOrPut(text) {
            val matcher = WORD_PATTERN.matcher(text)
            val tokens = mutableListOf<Triple<String, Int, Int>>()
            while (matcher.find()) {
                tokens.add(Triple(matcher.group(), matcher.start(), matcher.end()))
            }
            tokens
        }
    }

    /** Clear caches (call between requests if memory is a concern). */
    fun clearCaches() {
        tokenizerCache.clear()
        tokenizerWithOffsetsCache.clear()
    }

    /**
     * Extract added and deleted words from a single diff hunk at the word level.
     * Uses SequenceMatcher-like diffing on word tokens.
     */
    fun extractAddedAndDeletedFromHunk(hunk: String, extension: String): Pair<List<String>, List<String>> {
        val linesWithoutFileHeader = hunk.linesSplitKeepEnds()
            .filter { !it.startsWith("File: ") }
            .joinToString("")
        val (oldCode, newCode) = NesUtils.extractDiffParts(linesWithoutFileHeader)

        val oldWords = tokenizeWithBoundaries(oldCode)
        val newWords = tokenizeWithBoundaries(newCode)

        val patch = DiffUtils.diff(oldWords, newWords)
        val originalDeletedWords = mutableListOf<String>()
        val originalAddedWords = mutableListOf<String>()

        for (delta in patch.deltas) {
            when (delta.type) {
                com.github.difflib.patch.DeltaType.DELETE -> {
                    originalDeletedWords.addAll(delta.source.lines)
                }
                com.github.difflib.patch.DeltaType.INSERT -> {
                    originalAddedWords.addAll(delta.target.lines)
                }
                com.github.difflib.patch.DeltaType.CHANGE -> {
                    originalDeletedWords.addAll(delta.source.lines)
                    originalAddedWords.addAll(delta.target.lines)
                }
                else -> {}
            }
        }

        val addedWords = originalAddedWords.filter { it.length > 1 }.toSet().toList()
        val deletedWords = originalDeletedWords.filter { it.length > 1 }.toSet().toList()
        return addedWords to deletedWords
    }

    /**
     * Extract added and deleted words from recent changes diff.
     * Returns (addedWords, deletedWords).
     */
    fun extractAddedAndDeletedCodeFromRecentChanges(
        recentChanges: String,
        fileTokensSet: Set<String>,
    ): Pair<List<String>, List<String>> {
        val hunks = NesUtils.splitIntoHunks(recentChanges)
            .filter { it.trim().lines().size > 1 }
        if (hunks.isEmpty()) return emptyList<String>() to emptyList()

        var addedWords = emptyList<String>()
        var deletedWords = emptyList<String>()

        for (hunk in hunks.reversed()) {
            val firstLine = hunk.lines().first()
            val extension = if ("." in firstLine) firstLine.substringAfter(".").lowercase() else ""
            val (added, deleted) = extractAddedAndDeletedFromHunk(hunk, extension)
            addedWords = added
            deletedWords = deleted.filter { it.length > 1 && it in fileTokensSet }
            if (deletedWords.size == 1) return addedWords to deletedWords
        }
        return addedWords to deletedWords
    }

    /**
     * Extract full deleted lines from recent changes with their line numbers.
     */
    fun extractDeletedLinesFromRecentChanges(recentChanges: String): List<Pair<String, Int>> {
        val hunks = NesUtils.splitIntoHunks(recentChanges)
        val deletedLinesWithNumbers = mutableListOf<Pair<String, Int>>()

        for (hunk in hunks) {
            if ("@@" !in hunk) continue

            val lines = hunk.linesSplitKeepEnds()
            val rest = lines.drop(1).joinToString("")
            val parsed = NesUtils.parseHunk(rest)

            var currentLine = parsed.inputStart
            for (line in parsed.inputLines) {
                val stripped = line.trim()
                if (stripped.isNotEmpty() && line !in parsed.outputLines) {
                    deletedLinesWithNumbers.add(stripped to currentLine)
                }
                currentLine++
            }
        }
        return deletedLinesWithNumbers
    }

    /**
     * Search for deleted lines in the file contents and return a code block around the match.
     */
    fun findDeletedLineMatch(
        fileContents: String,
        deletedLines: List<Pair<String, Int>>,
    ): Pair<String, Int>? {
        val fileLines = fileContents.linesSplitKeepEnds()

        for ((deletedLine, originalLineNumber) in deletedLines) {
            val lineTokens = simpleTokenizer(deletedLine)
            val distinctTerms = lineTokens.toSet()

            if (distinctTerms.size >= 3) {
                for ((lineIndex, fileLine) in fileLines.withIndex()) {
                    if (lineIndex == originalLineNumber) continue
                    if (deletedLine in fileLine.trim()) {
                        val startLine = lineIndex
                        val endLine = minOf(fileLines.size, lineIndex + 4)
                        val retrievedBlock = fileLines.subList(startLine, endLine).joinToString("")
                        val blockStartOffset = fileLines.take(lineIndex).sumOf { it.length }
                        return retrievedBlock to blockStartOffset
                    }
                }
            }
        }
        return null
    }

    data class RetrievalResult(
        val codeBlock: String,
        val blockStartOffset: Int,
        val isBlockAfterCursor: Boolean,
        val diagnostic: EditorDiagnosticData?,
    )

    data class EditorDiagnosticData(
        val line: Int,
        val lineNumber: Int,
        val startOffset: Int,
        val endOffset: Int,
        val severity: String,
        val message: String,
    )

    /**
     * Find the best matching code block for retrieval-based autocomplete.
     * Ported from Python find_best_matching_block().
     */
    fun findBestMatchingBlock(
        fileContents: String,
        recentChanges: String,
        cursorPosition: Int,
        blockSize: Int = 6,
        editorDiagnostics: List<EditorDiagnosticData>? = null,
    ): RetrievalResult {
        // Try deleted line match first
        val deletedLines = extractDeletedLinesFromRecentChanges(recentChanges)
        val deletedLineMatch = findDeletedLineMatch(fileContents, deletedLines)
        if (deletedLineMatch != null) {
            return RetrievalResult(deletedLineMatch.first, deletedLineMatch.second, false, null)
        }

        // Tokenize file
        val fileTokensWithOffsets = simpleTokenizerWithOffsets(fileContents)
        val fileTokens = fileTokensWithOffsets.map { it.first }

        val (addedWords, deletedWords) = extractAddedAndDeletedCodeFromRecentChanges(
            recentChanges, fileTokens.toSet()
        )

        val currentCursorLineNumber = NesUtils.getLineNumberFromPosition(fileContents, cursorPosition)

        // Determine query token
        val queryToken: String? = if (deletedWords.size == 1) {
            deletedWords[0]
        } else {
            addedWords.firstOrNull { word ->
                val lineNumbers = fileTokensWithOffsets
                    .filter { it.first == word }
                    .map { NesUtils.getLineNumberFromPosition(fileContents, it.second) }
                word in fileTokens &&
                    fileTokens.count { it == word } in 2..5 &&
                    lineNumbers.any { abs(it - currentCursorLineNumber) > 10 }
            }
        }

        // Find offsets for query token (far from cursor)
        val queryTokenOffsets = fileTokensWithOffsets
            .filter {
                it.first == queryToken &&
                    abs(NesUtils.getLineNumberFromPosition(fileContents, it.second) - currentCursorLineNumber) > 10
            }
            .map { it.second }

        // Check for closest error diagnostic
        var closestError: EditorDiagnosticData? = null
        if (editorDiagnostics != null) {
            val filteredErrors = editorDiagnostics.filter {
                it.severity == "ERROR" && abs(currentCursorLineNumber - it.lineNumber) > 10
            }
            closestError = filteredErrors.minByOrNull { abs(cursorPosition - it.startOffset) }
        }

        val lines = fileContents.linesSplitKeepEnds()

        if (closestError != null) {
            var cumOffset = 0
            var startLine = 0
            for ((i, line) in lines.withIndex()) {
                if (cumOffset + line.length > closestError.startOffset) {
                    startLine = i
                    break
                }
                cumOffset += line.length
            }
            val endLine = minOf(lines.size, startLine + 1)
            val block = lines.subList(startLine, endLine).joinToString("")
            val offset = lines.take(startLine).sumOf { it.length }
            return RetrievalResult(block, offset, false, closestError)
        } else if (queryTokenOffsets.isNotEmpty()) {
            val closestOffset = queryTokenOffsets.minByOrNull { abs(cursorPosition - it) }!!

            var lineIndex = 0
            var cumOffset = 0
            for ((i, line) in lines.withIndex()) {
                if (cumOffset + line.length > closestOffset) {
                    lineIndex = i
                    break
                }
                cumOffset += line.length
            }
            val endLine = minOf(lines.size, lineIndex + 1)
            val block = lines.subList(lineIndex, endLine).joinToString("")
            val offset = lines.take(lineIndex).sumOf { it.length }
            return RetrievalResult(block, offset, false, null)
        } else {
            // Fallback: block after cursor
            var suffix = fileContents.substring(cursorPosition)
            val suffixStart = suffix.indexOf('\n')
            var adjustedCursorPosition = cursorPosition
            if (suffixStart != -1) {
                suffix = suffix.substring(suffixStart + 1)
                adjustedCursorPosition += suffixStart + 1
            }
            val suffixLines = suffix.linesSplitKeepEnds()
            adjustedCursorPosition += suffixLines.take(blockSize).sumOf { it.length }
            val remainingLines = suffixLines.drop(blockSize)
            val fallbackBlock = remainingLines.take(blockSize).joinToString("")

            return RetrievalResult(fallbackBlock, adjustedCursorPosition, true, null)
        }
    }

    /** Split text into word and non-word tokens (preserving whitespace/punctuation). */
    private fun tokenizeWithBoundaries(text: String): List<String> {
        val matcher = WORD_BOUNDARY_PATTERN.matcher(text)
        val tokens = mutableListOf<String>()
        while (matcher.find()) tokens.add(matcher.group())
        return tokens
    }
}
