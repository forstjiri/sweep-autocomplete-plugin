package dev.sweep.assistant.autocomplete.edit

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sweep.assistant.utils.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import dev.sweep.assistant.autocomplete.edit.engine.NesDeadline

class EntityUsageSearchService(
    private val project: Project,
) {
    companion object {
        private val logger = Logger.getInstance(EntityUsageSearchService::class.java)
        const val MAX_SEARCH_TIMEOUT_MS = 30L
        const val MAX_DEFINITION_RESOLUTION_TIMEOUT_MS = 500L // 500ms timeout for resolving definitions
        const val ENTITY_USAGE_CONTEXT_LINES_ABOVE = 9
        const val ENTITY_USAGE_CONTEXT_LINES_BELOW = 9
        const val MAX_SEARCH_RESULTS_PER_TERM = 100
        const val MAX_TERMS_TO_SEARCH = 5
        const val LINES_TO_SEARCH = 3
        const val CACHE_TTL_MS = 30_000L // 30 seconds
        const val CACHE_MAX_SIZE = 128
        const val MAX_DROPDOWN_ITEMS = 10
        const val MAX_DROPDOWN_TIMEOUT_MS = 30L
    }

    private val numDefinitionsToFetch = 6

    private val numUsagesToFetch = 6

    // Cache for individual term results - key is single term, value is the found occurrences for that term
    private val termCache =
        LRUCache<String, MutableMap<String, MutableList<Int>>>(
            maxSize = CACHE_MAX_SIZE,
            ttlMs = CACHE_TTL_MS,
        )

    private data class FoundOccurrence(
        val filePath: String,
        val lineNumbers: List<Int>,
        val lastUpdateTime: Long,
        val fileType: FileType,
    )

    private enum class FileType(
        val priority: Int,
    ) {
        PROJECT(1),
        TEST(2),
        EXCLUDED(3),
        EXTERNAL(4),
    }

    private fun processElementAtOffset(
        targetOffset: Int,
        psiFile: com.intellij.psi.PsiFile,
        processedElements: MutableSet<String>,
        fileChunks: MutableList<FileChunk>,
        currentPath: String,
        maxChars: Int,
    ): Boolean {
        return try {
            val elementAtCursor = psiFile.findElementAt(targetOffset) ?: return false

            // Skip if the element text is a language keyword
            val elementText = elementAtCursor.text?.trim()
            if (elementText != null && isLanguageKeyword(elementText, psiFile)) {
                logger.debug("Skipping language keyword: $elementText")
                return false
            }

            val reference = elementAtCursor.reference ?: elementAtCursor.parent?.reference
            val targetElement =
                try {
                    reference?.resolve()
                } catch (t: Throwable) {
                    // Fail silently to avoid surfacing resolver exceptions from language plugins (e.g., TS)
                    // Log at debug level only - these are expected errors from language plugins with stale indices
                    logger.debug("Failed to resolve reference at offset $targetOffset in ${psiFile.virtualFile?.path}", t)
                    return false
                } ?: return false

            val elementKey = "${targetElement.containingFile?.virtualFile?.path}:${System.identityHashCode(targetElement)}"
            if (processedElements.contains(elementKey)) return false

            processedElements.add(elementKey)

            val targetFile = targetElement.containingFile
            val filePath =
                relativePath(project, targetFile?.virtualFile?.path ?: "")
                    ?: targetFile?.virtualFile?.path ?: "unknown"

            if (filePath == (relativePath(project, currentPath) ?: currentPath)) return false

            // Computing the actual lines here is very slow, so we just use the lines count

//        val targetDocument =
//            targetFile?.virtualFile?.let {
//                FileDocumentManager.getInstance().getDocument(it)
//            }
//        val startLine =
//            if (targetDocument != null) {
//                targetDocument.getLineNumber(targetElement.textOffset) + 1
//            } else {
//                1
//            }

            // Safely get the element text, catching any potential errors
            val definitionText =
                try {
                    targetElement.text
                } catch (e: Throwable) {
                    // If getting text fails, skip this element
                    return false
                }

            if (definitionText.isEmpty()) return false
            var used = filePath.length + 20
            val lines = definitionText.lines().take(25).takeWhile { used += it.length + 1; used <= maxChars }
            if (lines.joinToString("\n").isBlank()) return false
//        val endLine = startLine + maxOf(0, lines.size - 1)

            fileChunks.add(
                FileChunk(
                    file_path = filePath,
                    start_line = 1,
                    end_line = lines.size,
                    content = lines.joinToString("\n"),
                    timestamp = System.currentTimeMillis(),
                ),
            )
            true
        } catch (t: Throwable) {
            // Any unexpected resolver/PSI error should be ignored to keep autocomplete robust
            false
        }
    }

    /**
     * Gets the definition text of the past n elements before the cursor position.
     * Uses IntelliJ's PSI APIs for maximum compatibility across all languages.
     */
    fun getDefinitionsBeforeCursor(currentEditorState: EditorState, deadline: NesDeadline = NesDeadline.after(MAX_DEFINITION_RESOLUTION_TIMEOUT_MS), shouldAbort: () -> Boolean = { false }, limit: Int = numDefinitionsToFetch, maxChars: Int = 1792): List<FileChunk> =
        runCatching {
            // Cache the feature flag value once at the start to avoid repeated lookups
            val maxDefinitions = limit

            val future: Future<List<FileChunk>> =
                AppExecutorUtil.getAppExecutorService().submit<List<FileChunk>> {
                    ReadAction.computeCancellable<List<FileChunk>, Exception> {
                        if (deadline.expired() || shouldAbort()) return@computeCancellable emptyList()
                        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(currentEditorState.filePath)
                            ?: return@computeCancellable emptyList()
                        val document = FileDocumentManager.getInstance().getDocument(file) ?: return@computeCancellable emptyList()
                        if (document.text != currentEditorState.documentText) return@computeCancellable emptyList()
                        val psiFile =
                            PsiDocumentManager.getInstance(project).getPsiFile(document)
                                ?: return@computeCancellable emptyList()

                        val fileChunks = mutableListOf<FileChunk>()
                        val processedElements = mutableSetOf<String>()
                        val cancelled = { deadline.expired() || shouldAbort() }
                        for (offset in definitionOffsetsInPriorityOrder(currentEditorState.documentText, currentEditorState.cursorOffset, cancelled)) {
                            if (fileChunks.size >= maxDefinitions || cancelled()) break
                            processElementAtOffset(offset, psiFile, processedElements, fileChunks, currentEditorState.filePath, maxChars)
                        }

                        fileChunks
                    }
                }

            // Wait for the result with timeout
            try {
                future.get(deadline.remainingMs(), TimeUnit.MILLISECONDS)
            } catch (e: Throwable) {
                // Timeout or other error - cancel the future and return empty list
                // Use cancel(false) to avoid interrupting the thread during PSI operations/index updates
                future.cancel(false)
                emptyList()
            }
        }.getOrDefault(emptyList())

    /**
     * Finds occurrences of text from the current line where the cursor is positioned.
     * This provides additional context for autocomplete by including relevant code references.
     */
    fun getCurrentLineEntityUsages(currentEditorState: EditorState, deadline: NesDeadline = NesDeadline.after(MAX_SEARCH_TIMEOUT_MS), shouldAbort: () -> Boolean = { false }, maxResults: Int = numUsagesToFetch, maxChars: Int = 1792): List<FileChunk> {
        if (deadline.expired() || shouldAbort()) return emptyList()
        val e2eStartTime = System.currentTimeMillis()
        val currentFilePath = relativePath(project, currentEditorState.filePath) ?: currentEditorState.filePath

        try {
            val usageChunks = mutableListOf<FileChunk>()

            val textBeforeCursor = currentEditorState.documentText.take(currentEditorState.cursorOffset)
            val currentLineNumber = textBeforeCursor.count { it == '\n' }
            val searchText = currentEditorState.documentText.lines().take(currentLineNumber + 1).takeLast(LINES_TO_SEARCH).joinToString(" ")

            val lineText =
                textBeforeCursor
                    .trimEnd()
                    .lines()
                    .lastOrNull()
                    ?.trim() ?: ""

            // Determine appropriate keywords based on current file extension
            val currentFileExtension = currentEditorState.filePath.substringAfterLast('.', "")
            val language = SweepConstants.EXTENSION_TO_LANGUAGE[currentFileExtension]
            val relevantKeywords = language?.let { SweepConstants.LANGUAGE_KEYWORDS[it] } ?: emptyList()

            val candidateTerms =
                searchText
                    .replace(Regex(SweepConstants.COMMON_SYMBOLS_REGEX), " ") // Remove common symbols
                    .split("\\s+".toRegex())
                    .filter { term ->
                        term.length >= 3 &&
                            !term.matches(Regex("\\d+")) &&
                            // Skip pure numbers
                            !relevantKeywords.contains(term.lowercase())
                    }.distinct()
                    .takeLast(MAX_TERMS_TO_SEARCH * 3) // 15 terms

            if (candidateTerms.isEmpty()) return emptyList()

            // Prioritize rare/specific terms over common ones using codebase-level frequency analysis
            val searchTerms =
                try {
                    // Sort by complexity score
                    sortByTermComplexity(candidateTerms).take(MAX_TERMS_TO_SEARCH)
                } catch (e: Exception) {
                    // Fallback to original behavior if frequency analysis fails
                    logger.warn("Failed to analyze term frequencies, using fallback", e)
                    candidateTerms.takeLast(MAX_TERMS_TO_SEARCH)
                }

            if (searchTerms.isEmpty()) return emptyList()

            val foundOccurrences =
                runCatching {
                    val cancelled = AtomicBoolean(false)
                    val partialResults = ConcurrentHashMap<String, MutableList<Int>>()

                    val searchFuture: Future<MutableMap<String, MutableList<Int>>> =
                        AppExecutorUtil.getAppExecutorService().submit<MutableMap<String, MutableList<Int>>> {
                            ReadAction.computeCancellable<MutableMap<String, MutableList<Int>>, Exception> {
                                val searchHelper = PsiSearchHelper.getInstance(project)
                                val scope = GlobalSearchScope.projectScope(project)

                                val reversedSearchTerms = searchTerms.reversed()

                                for (searchTerm in reversedSearchTerms) {
                                    // Check cache first for this term
                                    if (cancelled.get() || deadline.expired() || shouldAbort()) {
                                        break
                                    }

                                    val cachedResults = termCache.get(searchTerm)
                                    if (cachedResults != null) {
                                        for ((filePath, lineNumbers) in cachedResults) {
                                            partialResults.getOrPut(filePath) { mutableListOf() }.addAll(lineNumbers)
                                        }
                                        continue
                                    }

                                    var filesProcessed = 0
                                    var termResultCount = 0
                                    val termOccurrences =
                                        mutableMapOf<String, MutableList<Int>>() // Temporary storage for this term

                                    try {
                                        searchHelper.processAllFilesWithWord(
                                            searchTerm,
                                            scope,
                                            { psiFile ->
                                                if (cancelled.get() || deadline.expired() || shouldAbort()) {
                                                    return@processAllFilesWithWord false
                                                }

                                                filesProcessed++
                                                if (usageChunks.size >= 3) {
                                                    return@processAllFilesWithWord false // Limit total chunks
                                                }

                                                val fileVirtualFile =
                                                    psiFile.virtualFile ?: return@processAllFilesWithWord true
                                                val fileRelativePath =
                                                    relativePath(project, fileVirtualFile.path)
                                                        ?: fileVirtualFile.path

                                                // Skip current file
                                                if (fileRelativePath == currentFilePath) return@processAllFilesWithWord true

                                                // Filter for same file extension
                                                val currentFileExtension = currentFilePath.substringAfterLast('.', "")
                                                val fileExtension = fileRelativePath.substringAfterLast('.', "")
                                                if (currentFileExtension.isNotEmpty() && fileExtension != currentFileExtension) {
                                                    return@processAllFilesWithWord true
                                                }

                                                val fileDocument =
                                                    FileDocumentManager
                                                        .getInstance()
                                                        .getDocument(fileVirtualFile)
                                                        ?: return@processAllFilesWithWord true
                                                val fileText = fileDocument.text

                                                // Find line numbers containing this search term
                                                val lines = fileText.lines()
                                                var matchesInFile = 0
                                                for ((lineIndex, line) in lines.withIndex()) {
                                                    if (line.contains(searchTerm, ignoreCase = false)) {
                                                        termOccurrences
                                                            .getOrPut(fileRelativePath) { mutableListOf() }
                                                            .add(lineIndex + 1) // Convert to 1-based
                                                        matchesInFile++
                                                        termResultCount++
                                                    }
                                                }

                                                if (termResultCount >= MAX_SEARCH_RESULTS_PER_TERM) {
                                                    return@processAllFilesWithWord false
                                                }
                                                true
                                            },
                                            true,
                                        )
                                    } catch (t: Throwable) {
                                        cancelled.set(true)
                                        return@computeCancellable partialResults
                                    }

                                    // Always add occurrences, but limit to first MAX_SEARCH_RESULTS_PER_TERM results
                                    val limitedOccurrences = mutableMapOf<String, MutableList<Int>>()
                                    var totalAdded = 0

                                    for ((filePath, lineNumbers) in termOccurrences) {
                                        if (totalAdded >= MAX_SEARCH_RESULTS_PER_TERM) break

                                        val remainingSlots = MAX_SEARCH_RESULTS_PER_TERM - totalAdded
                                        val linesToAdd = lineNumbers.take(remainingSlots)

                                        if (linesToAdd.isNotEmpty()) {
                                            limitedOccurrences.getOrPut(filePath) { mutableListOf() }.addAll(linesToAdd)
                                            totalAdded += linesToAdd.size
                                        }
                                    }

                                    for ((filePath, lineNumbers) in limitedOccurrences) {
                                        partialResults.getOrPut(filePath) { mutableListOf() }.addAll(lineNumbers)
                                    }

                                    // Cache individual term results
                                    if (limitedOccurrences.isNotEmpty()) {
                                        termCache.put(searchTerm, limitedOccurrences.toMutableMap())
                                    }
                                }

                                partialResults
                            }
                        }

                    // Poll for completion or timeout
                    val result =
                        try {
                            searchFuture.get(deadline.remainingMs(), TimeUnit.MILLISECONDS)
                        } catch (e: Throwable) {
                            cancelled.set(true)
                            searchFuture.cancel(false)
                            partialResults
                        }

                    result
                }.getOrDefault(mutableMapOf())

            val processingStartTime = System.currentTimeMillis()
            val foundOccurrencesList =
                foundOccurrences.map { (fileRelativePath, lineNumbers) ->
                    val lastUpdateTime = getFileLastUpdateTime(fileRelativePath)
                    FoundOccurrence(
                        filePath = fileRelativePath,
                        lineNumbers = lineNumbers.distinct().sorted(),
                        lastUpdateTime = lastUpdateTime,
                        fileType = FileType.PROJECT,
                    )
                }

            val sortedOccurrences =
                foundOccurrencesList
                    .sortedWith(
                        compareBy<FoundOccurrence> { it.fileType.priority }
                            .thenByDescending { it.lastUpdateTime },
                    ).take(10)

            val bannedLinesByFile = mutableMapOf<String, MutableSet<Int>>()

            occurrenceLoop@ for (occurrence in sortedOccurrences) {
                if (deadline.expired() || shouldAbort()) break
                val fileContent = readFile(project, occurrence.filePath) ?: continue
                val lines = fileContent.lines()
                val bannedLines = bannedLinesByFile.getOrPut(occurrence.filePath) { mutableSetOf() }

                for (lineNum in occurrence.lineNumbers) {
                    if (bannedLines.contains(lineNum)) continue

                    val startLine = maxOf(1, lineNum - ENTITY_USAGE_CONTEXT_LINES_ABOVE)
                    val endLine = minOf(lines.size, lineNum + ENTITY_USAGE_CONTEXT_LINES_BELOW)

                    val chunkLines = mutableListOf<String>()
                    for (contextLine in startLine..endLine) {
                        chunkLines.add(lines[contextLine - 1])
                    }
                    var used = occurrence.filePath.length + 20
                    val chunkContent = chunkLines.takeWhile { used += it.length + 1; used <= maxChars }.joinToString("\n")
                    if (chunkContent.isBlank()) continue

                    usageChunks.add(
                        FileChunk(
                            file_path = occurrence.filePath,
                            start_line = startLine,
                            end_line = endLine,
                            content = chunkContent,
                            timestamp = System.currentTimeMillis(),
                        ),
                    )

                    if (usageChunks.size >= maxResults) break@occurrenceLoop

                    // Ban all lines within this context window to prevent overlaps
                    for (contextLine in startLine..endLine) {
                        bannedLines.add(contextLine)
                    }
                }
            }

            val sortedUsageChunks =
                usageChunks.sortedBy { chunk ->
                    val chunkLines = chunk.content.lines()
                    val mainLineIndex = ENTITY_USAGE_CONTEXT_LINES_ABOVE.coerceAtMost(chunkLines.size - 1)
                    val mainLine =
                        if (chunkLines.isNotEmpty() && mainLineIndex < chunkLines.size) {
                            chunkLines[mainLineIndex].trim()
                        } else {
                            chunk.content.trim()
                        }
                    StringDistance.levenshteinDistance(lineText, mainLine)
                }

            // Cache the feature flag value to avoid repeated lookups
            val maxUsages = maxResults
            return sortedUsageChunks.take(maxUsages)
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun StringBuilder.appendLineText(
        document: com.intellij.openapi.editor.Document,
        lineNumber: Int,
    ) {
        val lineStartOffset = document.getLineStartOffset(lineNumber)
        val lineEndOffset = document.getLineEndOffset(lineNumber)
        val lineText =
            document
                .getText(
                    com.intellij.openapi.util
                        .TextRange(lineStartOffset, lineEndOffset),
                ).trim()
        if (lineText.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append(lineText)
        }
    }

    private fun getFileLastUpdateTime(filePath: String): Long =
        try {
            val virtualFile =
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(
                    if (filePath.startsWith("/")) filePath else "${project.basePath}/$filePath",
                )
            virtualFile?.timeStamp ?: 0L
        } catch (e: Exception) {
            0L
        }

    /**
     * Gets the current dropdown/completion contents if any are active.
     *
     * @return Lookup strings, or null if no dropdown is active or collection is cancelled
     */
    fun getCurrentDropdownContents(deadline: NesDeadline = NesDeadline.after(MAX_DROPDOWN_TIMEOUT_MS), shouldAbort: () -> Boolean = { false }): String? {
        if (deadline.expired() || shouldAbort()) return null
        return try {
            val lookupManager = LookupManager.getInstance(project)
            val activeLookup = lookupManager.activeLookup ?: return null

            // activeLookup might return a component instead of Lookup, so we need to get the actual Lookup
            val lookup = activeLookup as? LookupImpl ?: return null

            val future =
                AppExecutorUtil.getAppExecutorService().submit<String?> {
                    // Poll for items outside ReadAction to avoid holding read lock while sleeping
                    var allItems =
                        ReadAction.computeCancellable<List<LookupElement>, Exception> {
                            lookup.items.toList()
                        }
                    var attempts = 0
                    val maxAttempts = 3
                    val pollDelayMs = 10L

                    while (allItems.isEmpty() && attempts < maxAttempts && !deadline.expired() && !shouldAbort()) {
                        Thread.sleep(pollDelayMs)
                        allItems =
                            ReadAction.computeCancellable<List<LookupElement>, Exception> {
                                lookup.items.toList()
                            }
                        attempts++
                    }

                    if (allItems.isEmpty()) {
                        return@submit null
                    }

                    // Now process items in a ReadAction
                    ReadAction.computeCancellable<String?, Exception> {
                        if (deadline.expired() || shouldAbort()) return@computeCancellable null
                        // Read lookup strings only: rendering/pattern resolution can invoke language analysis.
                        allItems.take(MAX_DROPDOWN_ITEMS).joinToString("\n") { it.lookupString }
                    }
                }

            try {
                future.get(deadline.remainingMs(), TimeUnit.MILLISECONDS)
            } catch (e: Throwable) {
                future.cancel(false)
                null
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun sortByTermComplexity(terms: List<String>): List<String> =
        terms.sortedByDescending { term ->
            val underscoreCount = term.count { it == '_' }.toDouble()
            // pascal case is if the entire term is not uppercase letters and how many uppercase letters it contains
            val pascalCaseCount = if (term.all { it.isUpperCase() || !it.isLetter() }) 0.0 else term.count { it.isUpperCase() }.toDouble()

            // take the terms with highest underscore or pascal case count, then the longest
            // Use a composite score: primary sort by complexity, secondary by length
            maxOf(underscoreCount, pascalCaseCount) * 5 + term.length
        }

    /**
     * Checks if the given text is a language keyword based on the file's language.
     * This helps filter out common language keywords from search results.
     */
    private fun isLanguageKeyword(
        text: String,
        psiFile: com.intellij.psi.PsiFile,
    ): Boolean {
        // Get the file extension to determine the language
        val fileExtension = psiFile.virtualFile?.extension ?: return false

        // Map the file extension to a language
        val language = SweepConstants.EXTENSION_TO_LANGUAGE[fileExtension] ?: return false

        // Get the keywords for this language
        val keywords = SweepConstants.LANGUAGE_KEYWORDS[language] ?: return false

        // Check if the text (case-insensitive) is in the keyword list
        return keywords.contains(text.lowercase())
    }
}

/** Definition priority: current line backwards, current line forwards, then six preceding code lines. */
internal fun definitionOffsetsInPriorityOrder(text: String, cursor: Int, cancelled: () -> Boolean = { false }): Sequence<Int> = sequence {
    require(cursor in 0..text.length)
    fun separator(char: Char) = char.isWhitespace() || char in "(){}[]<>,.;:=+-*/%!&|^~?"
    fun walk(start: Int, boundary: Int, step: Int): Sequence<Int> = sequence {
        var offset = start
        fun inRange() = if (step < 0) offset >= boundary else offset < boundary
        while (inRange() && !cancelled()) {
            if (separator(text[offset])) { offset += step; continue }
            yield(offset)
            do { offset += step } while (inRange() && !separator(text[offset]) && !cancelled())
        }
    }
    var lineStart = text.lastIndexOf('\n', cursor - 1) + 1
    val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
    yieldAll(walk(cursor - 1, lineStart, -1))
    yieldAll(walk(cursor, lineEnd, 1))
    var codeLines = 0
    while (lineStart > 0 && codeLines < 6 && !cancelled()) {
        val previousEnd = lineStart - 1
        lineStart = text.lastIndexOf('\n', previousEnd - 1) + 1
        var visited = false
        for (offset in walk(lineStart, previousEnd, 1)) { visited = true; yield(offset) }
        if (visited) codeLines++
    }
}
