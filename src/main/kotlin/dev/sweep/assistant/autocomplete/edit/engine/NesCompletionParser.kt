package dev.sweep.assistant.autocomplete.edit.engine

import kotlin.math.abs

/**
 * Post-processing of LLM completions into concrete edit suggestions.
 * Ported from Python next_edit_autocomplete.py (select_best_hunk_from_completion and helpers).
 * No IntelliJ dependencies — fully unit-testable.
 */
object NesCompletionParser {

    data class AutocompleteResult(
        val startIndex: Int,
        val endIndex: Int,
        val completion: String,
        val confidence: Float,
        val autocompleteId: String,
    )

    /**
     * Check if the completion is a pure ghost text insertion at the given position.
     * Returns the ghost text if found, empty string otherwise.
     */
    fun getGhostTextWithLocation(
        completion: String,
        cleanedCodeBlock: String,
        relativeCursorPosition: Int,
    ): String {
        val prefix = cleanedCodeBlock.substring(0, relativeCursorPosition)
        val suffix = cleanedCodeBlock.substring(relativeCursorPosition)
        if (completion.startsWith(prefix) && completion.endsWith(suffix)) {
            val ghostText = if (suffix.isNotEmpty()) {
                completion.substring(prefix.length, completion.length - suffix.length)
            } else {
                completion.substring(prefix.length)
            }
            if (ghostText.isNotEmpty()) return ghostText
        }
        return ""
    }

    /**
     * Find the best ghost text position by checking all possible split points.
     * Returns (ghostText, position) or ("", -1) if not found.
     */
    fun findGhostTextNonLocal(
        completion: String,
        cleanedCodeBlock: String,
        relativeCursorPosition: Int,
    ): Pair<String, Int> {
        if (cleanedCodeBlock.length > completion.length) return "" to -1

        val validPositions = mutableListOf<Pair<Int, String>>()
        for (pos in 0..cleanedCodeBlock.length) {
            val ghostText = getGhostTextWithLocation(completion, cleanedCodeBlock, pos)
            if (ghostText.isNotEmpty()) {
                validPositions.add(pos to ghostText)
            }
        }

        if (validPositions.isNotEmpty()) {
            val (bestPos, bestText) = validPositions.maxByOrNull { it.first }!!
            return bestText to bestPos
        }
        return "" to -1
    }

    /**
     * Check if the completion is a single-line ghost text at the cursor position.
     */
    fun isSingleLineGhostText(
        completion: String,
        cleanedCodeBlock: String,
        relativeCursorPosition: Int,
    ): String {
        if (cleanedCodeBlock.length < relativeCursorPosition) return ""

        val prefix = cleanedCodeBlock.substring(0, relativeCursorPosition)
        val suffix = cleanedCodeBlock.substring(relativeCursorPosition)
        if (completion.startsWith(prefix) && completion.endsWith(suffix)) {
            val ghostText = if (suffix.isNotEmpty()) {
                completion.substring(prefix.length, completion.length - suffix.length)
            } else {
                completion.substring(prefix.length)
            }
            if (ghostText.isNotEmpty() && '\n' !in ghostText) return ghostText
        }
        return ""
    }

    /**
     * Check if completion is a pure insertion above the cursor position.
     */
    fun isPureInsertionAboveCursor(
        cleanedCodeBlock: String,
        completion: String,
        relativeCursorPosition: Int,
    ): Boolean {
        val currentLineIndex = cleanedCodeBlock.substring(0, relativeCursorPosition)
            .linesSplitKeepEnds().size
        val codeBlockLines = cleanedCodeBlock.linesSplitKeepEnds()
        if (currentLineIndex < 1 || currentLineIndex > codeBlockLines.size) return false
        val cursorLine = codeBlockLines[currentLineIndex - 1]

        if (cleanedCodeBlock.trim() == completion.trim()) return false
        if (cursorLine.trim().isEmpty()) return false

        val prefixLines = codeBlockLines.take(currentLineIndex - 1)
        val prefix = prefixLines.joinToString("")
        val suffixLines = codeBlockLines.drop(currentLineIndex)
        val suffix = suffixLines.joinToString("")

        return completion.startsWith(prefix) && completion.endsWith(cursorLine + suffix)
    }

    /**
     * Apply completions to the code block and return the modified version.
     */
    fun applyCompletionsToCodeBlock(
        completions: List<AutocompleteResult>,
        fileContents: String,
        cleanedCodeBlock: String,
    ): String {
        if (completions.isEmpty()) return cleanedCodeBlock

        val cleanedCodeStartIndex = fileContents.indexOf(cleanedCodeBlock)
        if (cleanedCodeStartIndex == -1) return cleanedCodeBlock

        var modifiedCodeBlock = cleanedCodeBlock
        val sorted = completions.sortedByDescending { it.startIndex }

        for (comp in sorted) {
            val relativeStart = comp.startIndex - cleanedCodeStartIndex
            val relativeEnd = comp.endIndex - cleanedCodeStartIndex
            if (relativeStart >= 0 && relativeStart <= cleanedCodeBlock.length && relativeEnd <= cleanedCodeBlock.length) {
                modifiedCodeBlock = modifiedCodeBlock.substring(0, relativeStart) +
                    comp.completion +
                    modifiedCodeBlock.substring(relativeEnd)
            }
        }
        return modifiedCodeBlock
    }

    /**
     * Find the best hunk from the completion to suggest as an edit.
     * This is the main post-processing function.
     *
     * Ported from Python select_best_hunk_from_completion().
     */
    fun selectBestHunkFromCompletion(
        completionRaw: String,
        cleanedCodeBlockRaw: String,
        fileContents: String,
        cursorPosition: Int,
        autocompleteId: String,
    ): List<AutocompleteResult> {
        val completion = NesUtils.stripLeadingEmptyNewlines(completionRaw)
        val cleanedCodeBlock = NesUtils.stripLeadingEmptyNewlines(cleanedCodeBlockRaw)

        if (completion == cleanedCodeBlock) return emptyList()

        val blockStartOffset = fileContents.indexOf(cleanedCodeBlock)
        if (blockStartOffset == -1) return emptyList()

        val relativeCursorPosition = cursorPosition - blockStartOffset

        // Check for ghost text
        val (ghostText, ghostTextPosition) = findGhostTextNonLocal(
            completion, cleanedCodeBlock, relativeCursorPosition
        )
        if (ghostText.isNotEmpty()) {
            val isInsertNextLine = ghostTextPosition == relativeCursorPosition + 1 &&
                cleanedCodeBlock[ghostTextPosition - 1] == '\n'
            val insertionStartsWithNewline = ghostText.startsWith("\n") &&
                ghostTextPosition == relativeCursorPosition

            if (isInsertNextLine || insertionStartsWithNewline) {
                return listOf(
                    AutocompleteResult(
                        ghostTextPosition + blockStartOffset,
                        ghostTextPosition + blockStartOffset,
                        ghostText, 1.0f, "$autocompleteId-0"
                    )
                )
            }

            val ghostLines = ghostText.linesSplitKeepEnds()
            val firstLine = ghostLines.first()
            val remainingGhostText = ghostLines.drop(1).joinToString("")

            if (remainingGhostText.isNotEmpty()) {
                val trimmedRemaining = remainingGhostText.trimEnd()
                if (ghostTextPosition < cleanedCodeBlock.length &&
                    cleanedCodeBlock[ghostTextPosition] == '\n'
                ) {
                    val trailingLen = firstLine.length - firstLine.trimEnd('\n').length
                    val trailing = if (trailingLen > 0) firstLine.takeLast(trailingLen) else ""
                    val firstLineClean = firstLine.trimEnd('\n')
                    val fullRemaining = trailing + trimmedRemaining

                    return listOf(
                        AutocompleteResult(
                            ghostTextPosition + blockStartOffset,
                            ghostTextPosition + blockStartOffset,
                            firstLineClean, 1.0f, "$autocompleteId-0"
                        ),
                        AutocompleteResult(
                            ghostTextPosition + blockStartOffset,
                            ghostTextPosition + blockStartOffset,
                            fullRemaining, 1.0f, "$autocompleteId-1"
                        ),
                    )
                }
            }

            return listOf(
                AutocompleteResult(
                    ghostTextPosition + blockStartOffset,
                    ghostTextPosition + blockStartOffset,
                    ghostText, 1.0f, "$autocompleteId-0"
                )
            )
        }

        // Fall through to diff-based hunk selection
        val hunks = NesUtils.splitIntoDiffHunks(cleanedCodeBlock, completion)
        if (hunks.isEmpty()) return emptyList()

        // Process each hunk to get absolute positions
        val originalLines = cleanedCodeBlock.linesSplitKeepEnds()
        val processedHunks = mutableListOf<Triple<Int, Int, String>>()

        for (hunk in hunks) {
            val startLineIdx = hunk.inputStart - 1
            var startOffset = blockStartOffset
            for (i in 0 until startLineIdx) {
                if (i < originalLines.size) startOffset += originalLines[i].length
            }

            var endOffset = startOffset
            for (i in 0 until hunk.inputLines.size) {
                val lineIdx = startLineIdx + i
                if (lineIdx < originalLines.size) endOffset += originalLines[lineIdx].length
            }

            var newText = hunk.outputLines.joinToString("")

            // Hack for end of file
            if (startOffset == fileContents.length && fileContents[startOffset - 1] != '\n') {
                newText = "\n$newText"
            }

            if (fileContents.substring(startOffset, endOffset) != newText) {
                processedHunks.add(Triple(startOffset, endOffset, newText))
            }
        }

        val hunksAfterCursor = processedHunks.filter { it.second >= cursorPosition }
            .sortedBy { it.first }
        val hunksBeforeCursor = processedHunks.filter { it.second < cursorPosition }
            .sortedBy { it.first }

        // Handle hunks after cursor
        if (hunksAfterCursor.isNotEmpty()) {
            val (startOffset, endOffset, newText) = hunksAfterCursor.first()
            val restHunks = hunksAfterCursor.drop(1)
            val startLinePosition = fileContents.substring(0, cursorPosition).lastIndexOf('\n') + 1

            val results = mutableListOf<AutocompleteResult>()

            val shouldSplit = startLinePosition == startOffset &&
                startOffset <= cursorPosition && cursorPosition < endOffset &&
                newText.count { it == '\n' } > 0

            if (shouldSplit) {
                val newTextLines = newText.linesSplitKeepEnds()
                val firstLine = newTextLines.first()
                val remainingNewText = newTextLines.drop(1).joinToString("")

                val originalTextSection = fileContents.substring(cursorPosition, endOffset)
                val firstNewlinePos = originalTextSection.indexOf('\n')
                val firstLineEnd = if (firstNewlinePos != -1) cursorPosition + firstNewlinePos + 1 else endOffset

                val endLinePosition = fileContents.indexOf('\n', cursorPosition).let { if (it == -1) fileContents.length else it }
                val currentCursorLineContents = fileContents.substring(startLinePosition, endLinePosition)

                if (firstLine.startsWith(currentCursorLineContents)) {
                    results.add(AutocompleteResult(startOffset, firstLineEnd, firstLine, 1.0f, "$autocompleteId-0"))
                    if (remainingNewText.isNotEmpty()) {
                        results.add(AutocompleteResult(firstLineEnd, endOffset, remainingNewText, 1.0f, "$autocompleteId-1"))
                    }
                } else {
                    results.add(AutocompleteResult(startOffset, endOffset, newText, 1.0f, "$autocompleteId-0"))
                }
            } else {
                results.add(AutocompleteResult(startOffset, endOffset, newText, 1.0f, "$autocompleteId-0"))
            }

            val maxId = restHunks.size
            results.addAll(restHunks.mapIndexed { i, (s, e, t) ->
                AutocompleteResult(s, e, t, 1.0f, "$autocompleteId-${maxId + i}")
            })
            return results
        }

        // Handle hunks before cursor — fuse nearby hunks
        if (hunksBeforeCursor.isNotEmpty()) {
            val fusedGroups = mutableListOf(mutableListOf(hunksBeforeCursor.first()))

            for (i in 1 until hunksBeforeCursor.size) {
                val prevHunk = fusedGroups.last().last()
                val currHunk = hunksBeforeCursor[i]
                val prevEndLine = NesUtils.getLineNumberFromPosition(fileContents, prevHunk.second)
                val currStartLine = NesUtils.getLineNumberFromPosition(fileContents, currHunk.first)

                if (currStartLine - prevEndLine <= 2) {
                    fusedGroups.last().add(currHunk)
                } else {
                    fusedGroups.add(mutableListOf(currHunk))
                }
            }

            val results = fusedGroups.mapIndexed { groupIdx, group ->
                val firstStart = group.first().first
                val lastEnd = group.last().second

                val combinedParts = mutableListOf<String>()
                var currentOffset = firstStart
                for ((s, e, t) in group) {
                    if (currentOffset < s) combinedParts.add(fileContents.substring(currentOffset, s))
                    combinedParts.add(t)
                    currentOffset = e
                }

                AutocompleteResult(
                    firstStart, lastEnd, combinedParts.joinToString(""),
                    1.0f, "$autocompleteId-$groupIdx"
                )
            }.sortedBy { abs(it.startIndex - cursorPosition) }

            return results
        }

        return emptyList()
    }
}
