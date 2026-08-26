package dev.sweep.assistant.autocomplete.edit

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import dev.sweep.assistant.utils.relativePath
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Detects the "typing a new method whose name collides with an existing one" scenario,
 * e.g. typing `public async findBy` right below `findByProductIds` in a facade class.
 *
 * When detected, the tracker enriches the autocomplete request with:
 * - the resolved entity class as a retrieval chunk (so the model sees its field names),
 * - an explicit steering prompt naming the sibling methods and the entity fields
 *   that no existing method covers yet.
 *
 * Detection is text-based (cheap brace scanning, language-agnostic). PSI resolution
 * runs only after a duplicate-risk match is found and is guarded by a timeout.
 */
class NewMethodContextService(
    private val project: Project,
) {
    companion object {
        private val logger = Logger.getInstance(NewMethodContextService::class.java)
        const val MAX_RESOLUTION_TIMEOUT_MS = 400L
        const val MIN_TYPED_NAME_LENGTH = 3
        const val MAX_SIBLINGS_IN_STEERING = 3
        const val MAX_UNCOVERED_FIELDS_IN_STEERING = 3
        const val MAX_ENTITY_CHUNK_CHARS = 4000

        private val MODIFIER_KEYWORDS =
            setOf(
                "public", "private", "protected", "internal", "static", "final", "abstract",
                "override", "open", "suspend", "async", "export", "fun", "function",
                "default", "declare", "inline", "operator", "external", "data",
            )

        private val METHOD_KEYWORDS =
            setOf(
                "if", "for", "while", "switch", "catch", "return", "new", "throw", "await",
                "else", "try", "do", "constructor", "super", "this", "typeof", "delete", "get", "set",
            )

        // Well-known generic/library type names that never resolve to a useful project class
        private val TYPE_BLOCKLIST =
            setOf(
                "Promise", "Map", "Set", "Array", "Record", "Partial", "Pick", "Omit",
                "Object", "String", "Number", "Boolean", "Symbol", "WeakMap", "ReadonlyArray",
                "Iterable", "Iterator", "Generator", "Function", "Date", "RegExp", "Error",
                "Repository", "Inject", "Injectable", "Entity", "Column", "Index",
                "PrimaryGeneratedColumn", "ManyToOne", "OneToMany", "OneToOne", "JoinColumn",
                "In", "Between", "LessThan", "MoreThan", "Not", "Type", "Module",
            )

        private val CAPITALIZED_REGEX = Regex("\\b([A-Z][A-Za-z0-9_]*)\\b")
        private val IDENTIFIER_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val METHOD_DECL_REGEX =
            Regex(
                "(?m)^\\s*(?:(?:public|private|protected|internal|static|final|abstract|override|open|suspend|async)\\s+)*" +
                    "(?:fun\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(",
            )
        private val FIELD_DECL_REGEX =
            Regex(
                "(?m)^\\s*(?:@[A-Za-z]+(?:\\([^)]*\\))?\\s+)*(?:(?:public|private|protected|readonly)\\s+)*" +
                    "([a-z_][A-Za-z0-9_]*)\\s*[!?]?\\s*:",
            )

        /**
         * Member-level typed declarations: class fields and constructor/method parameters
         * such as `private readonly repo: Repository<ProductEntity>` or `field: Type`.
         * Group 2 (the type) may reference the entity class we want to resolve.
         */
        private val MEMBER_TYPED_DECL_REGEX =
            Regex(
                "(?m)^\\s*(?:@[A-Za-z]+(?:\\([^)]*\\))?\\s+)*(?:(?:public|private|protected|readonly|internal|static|final)\\s+)*" +
                    "([a-z_][A-Za-z0-9_]*)\\s*[!?]?\\s*:\\s*([A-Z][A-Za-z0-9_<>\\[\\],.|\\s]*)",
            )

        /** A method signature at the start of a line, ignoring leading modifiers. */
        private val DECL_NAME_REGEX =
            Regex(
                "(?m)^\\s*(?:(?:public|private|protected|internal|static|final|abstract|override|open|suspend|async|export)\\s+)*" +
                    "(?:fun\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(",
            )
        private val CLASS_HEADER_REGEX = Regex("\\b(class|interface|struct|object)\\s+[A-Za-z_]")
    }

    data class NewMethodContext(
        val typedName: String,
        val siblingMethodNames: List<String>,
        /** Every declared method of the enclosing class, used for coverage checks. */
        val allMethodNames: List<String> = emptyList(),
        val entityChunk: FileChunk? = null,
        val uncoveredEntityFields: List<String> = emptyList(),
    )

    /** A `{...}` block with its header text, absolute offsets, and body text. */
    private data class Block(
        val bodyStart: Int,
        val bodyEnd: Int, // inclusive: index of the closing `}`, or document end
        val header: String,
        val text: String,
    )

    /**
     * Returns a context when the caret sits on an incomplete method signature whose
     * typed name is a prefix of an existing method in the same class; null otherwise.
     */
    fun detect(editorState: EditorState): NewMethodContext? {
        val context = detectTextOnly(editorState) ?: return null

        val document = editorState.documentText
        val cursor = editorState.cursorOffset.coerceIn(0, document.length)
        val classBody = findDirectlyEnclosingClassBody(document, cursor) ?: return context
        val resolved =
            runCatching { resolveEntityContext(document, cursor, classBody) }
                .getOrNull() ?: return context

        val methods = context.allMethodNames
        val uncovered =
            resolved.second.filter { field ->
                // The field is not yet covered by any existing method (word-level), and
                // the derived method name does not collide with an existing one.
                methods.none { method -> fieldCoveredByMethod(field, method) } &&
                    !nameCollides(deriveMethodName(context.typedName, field), methods)
            }

        return context.copy(
            entityChunk = resolved.first,
            uncoveredEntityFields = uncovered,
        )
    }

    /** Derives e.g. `findByBanRoles` from prefix `findBy` and field `banRoles`. */
    internal fun deriveMethodName(
        prefix: String,
        field: String,
    ): String = prefix + field.replaceFirstChar { it.uppercaseChar() }

    /** Pure text-based duplicate-risk detection; no PSI, no project access. */
    internal fun detectTextOnly(editorState: EditorState): NewMethodContext? {
        val typedName = extractTypedMethodName(editorState.currentLinePrefix) ?: return null
        if (typedName.length < MIN_TYPED_NAME_LENGTH) return null

        val document = editorState.documentText
        val cursor = editorState.cursorOffset.coerceIn(0, document.length)

        // A new method name is being typed at the end of the line: nothing but
        // whitespace may follow the caret (otherwise it is an existing declaration).
        val lineSuffix = document.substring(cursor).substringBefore('\n')
        if (lineSuffix.isNotBlank()) return null

        val classBody = findDirectlyEnclosingClassBody(document, cursor) ?: return null

        if (!isAtMemberLevel(classBody.textBefore(cursor))) return null

        val siblingNames =
            METHOD_DECL_REGEX.findAll(classBody.text)
                .map { it.groupValues[1] }
                .filter { it !in METHOD_KEYWORDS && it != typedName && it.startsWith(typedName) }
                .distinct()
                .toList()

        if (siblingNames.isEmpty()) return null

        val allMethodNames =
            METHOD_DECL_REGEX.findAll(classBody.text)
                .map { it.groupValues[1] }
                .filter { it !in METHOD_KEYWORDS && it != typedName }
                .distinct()
                .toList()

        return NewMethodContext(
            typedName = typedName,
            siblingMethodNames = siblingNames,
            allMethodNames = allMethodNames,
        )
    }

    fun buildSteering(context: NewMethodContext): String {
        val siblings = context.siblingMethodNames.take(MAX_SIBLINGS_IN_STEERING).joinToString(", ")
        val base = "The class already has $siblings."
        val fields = context.uncoveredEntityFields.take(MAX_UNCOVERED_FIELDS_IN_STEERING)
        return if (fields.isNotEmpty()) {
            // The local model needs an explicit derived method name; a generic
            // "use field X" phrasing still produces the duplicate (verified live).
            val suggestions =
                fields.joinToString(", or ") { field ->
                    val methodName = context.typedName + field.replaceFirstChar { it.uppercaseChar() }
                    "$methodName using the $field entity field"
                }
            "$base Suggest $suggestions."
        } else {
            "$base Suggest a new method that is not a near-duplicate of the existing ones" +
                " and uses a different entity field."
        }
    }

    /** Last identifier token of the line prefix, when the line looks like an unfinished method signature. */
    private fun extractTypedMethodName(linePrefix: String): String? {
        if (linePrefix.contains("//") || linePrefix.contains("/*")) return null
        val tokens = linePrefix.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        // All tokens but the last must be known modifiers or type-like identifiers
        for (token in tokens.dropLast(1)) {
            if (token in MODIFIER_KEYWORDS) continue
            if (!Regex("^[A-Za-z_<][A-Za-z0-9_<>\\[\\],.?|]*$").matches(token)) return null
        }

        val last = tokens.last()
        // The name is still being typed: bare identifier, no parens/colons/generics yet
        if (!Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(last)) return null
        if (last in MODIFIER_KEYWORDS || last in METHOD_KEYWORDS) return null
        return last
    }

    private fun Block.textBefore(cursor: Int): String = text.substring(0, (cursor - bodyStart).coerceIn(0, text.length))

    /**
     * Single brace-aware pass over the document. Returns the innermost class-like body
     * that directly contains [cursor] (i.e. the caret is at member level, not inside a
     * nested method body), or null.
     */
    private fun findDirectlyEnclosingClassBody(
        document: String,
        cursor: Int,
    ): Block? {
        var headerStart = 0
        var inString: Char? = null
        val openOffsets = ArrayDeque<Int>()
        val headers = ArrayDeque<String>()

        var i = 0
        while (i < document.length) {
            val c = document[i]
            when {
                inString != null -> if (c == inString && document.getOrNull(i - 1) != '\\') inString = null
                c == '"' || c == '\'' || c == '`' -> inString = c
                c == '{' -> {
                    openOffsets.addLast(i)
                    headers.addLast(document.substring(headerStart, i))
                    headerStart = i + 1
                }
                c == '}' -> {
                    if (openOffsets.isNotEmpty()) {
                        val open = openOffsets.removeLast()
                        val header = headers.removeLast()
                        headerStart = i + 1
                        if (cursor > open && cursor <= i &&
                            CLASS_HEADER_REGEX.containsMatchIn(header.trim().takeLast(200))
                        ) {
                            val block =
                                Block(
                                    bodyStart = open + 1,
                                    bodyEnd = i,
                                    header = header,
                                    text = document.substring(open + 1, i),
                                )
                            if (isDirectlyInside(block, cursor, document)) return block
                        }
                    } else {
                        headerStart = i + 1
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * True when the net brace depth at [cursor] is zero relative to the block body:
     * every block opened earlier (e.g. a closed constructor) is also closed by the cursor.
     */
    private fun isDirectlyInside(
        block: Block,
        cursor: Int,
        document: String,
    ): Boolean {
        var depth = 0
        var inString: Char? = null
        for (i in block.bodyStart until cursor.coerceAtMost(block.bodyEnd + 1)) {
            val ch = document[i]
            when {
                inString != null -> if (ch == inString && document.getOrNull(i - 1) != '\\') inString = null
                ch == '"' || ch == '\'' || ch == '`' -> inString = ch
                ch == '{' -> depth++
                ch == '}' -> depth--
                else -> {}
            }
            if (depth < 0) return false
        }
        return depth == 0
    }

    /** The previous non-blank line above the caret should close a member (or open the class). */
    private fun isAtMemberLevel(classBodyBeforeCursor: String): Boolean {
        val lines = classBodyBeforeCursor.lines().dropLast(1) // drop the current partial line
        val prev = lines.asReversed().firstOrNull { it.isNotBlank() } ?: return true
        val trimmed = prev.trimEnd()
        return trimmed.endsWith("}") || trimmed.endsWith("};") || trimmed.endsWith("{")
    }

    /**
     * Resolves the entity class referenced by the enclosing class — from typed member
     * declarations (constructor parameters, fields) — and returns it as a retrieval
     * chunk together with its field names. Coverage filtering happens in [detect].
     */
    private fun resolveEntityContext(
        document: String,
        cursor: Int,
        classBody: Block,
    ): Pair<FileChunk, List<String>>? {
        val candidates = extractTypeCandidates(classBody.text)
        if (candidates.isEmpty()) return null

        val future: Future<Pair<FileChunk, List<String>>?> =
            com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService()
                .submit<Pair<FileChunk, List<String>>?> {
                    ReadAction.computeCancellable<Pair<FileChunk, List<String>>?, Exception> {
                        try {
                            resolveCandidates(document, cursor, candidates)
                        } catch (t: Throwable) {
                            logger.debug("New-method entity resolution failed: ${t.message}")
                            null
                        }
                    }
                }
        return try {
            future.get(MAX_RESOLUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            future.cancel(true)
            null
        }
    }

    /**
     * Type candidates for entity resolution. Types from typed member declarations
     * (constructor params, fields) come first — they identify the entity backing the
     * class — followed by any other capitalized identifier as a fallback.
     */
    internal fun extractTypeCandidates(classText: String): List<String> {
        val preferred =
            MEMBER_TYPED_DECL_REGEX.findAll(classText)
                .flatMap { r -> CAPITALIZED_REGEX.findAll(r.groupValues[2]) }
                .map { it.value }
                .filter { it !in TYPE_BLOCKLIST }
        val rest =
            CAPITALIZED_REGEX.findAll(classText)
                .map { it.value }
                .filter { it !in TYPE_BLOCKLIST }
        return (preferred + rest).distinct().take(10).toList()
    }

    private fun resolveCandidates(
        document: String,
        cursor: Int,
        candidates: List<String>,
    ): Pair<FileChunk, List<String>>? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
        val currentPath = psiFile.virtualFile?.path
        val searchable = document.substring(0, minOf(document.length, cursor))

        for (name in candidates) {
            val offset = IDENTIFIER_REGEX.findAll(searchable).firstOrNull { it.value == name }?.range?.first ?: continue
            val leaf = psiFile.findElementAt(offset) ?: continue
            val reference = leaf.reference ?: leaf.parent?.reference ?: continue
            val target =
                try {
                    reference.resolve()
                } catch (t: Throwable) {
                    continue
                } ?: continue

            val targetFile = target.containingFile ?: continue
            val targetPath = targetFile.virtualFile?.path ?: continue
            if (targetPath == currentPath) continue
            if (targetPath.contains("node_modules")) continue
            if (!Regex("\\.(ts|tsx|js|jsx|kt|java|py)$").matches(targetFile.name)) continue

            val targetText = target.text
            if (targetText.length < 20) continue
            val fields = extractFieldNames(targetText)
            if (fields.size < 2) continue

            val content = targetText.take(MAX_ENTITY_CHUNK_CHARS)
            val chunk =
                FileChunk(
                    file_path = relativePath(project, targetPath) ?: targetPath,
                    start_line = 1,
                    end_line = content.lines().size,
                    content = content,
                )
            return chunk to fields
        }
        return null
    }

    private fun extractFieldNames(classText: String): List<String> =
        FIELD_DECL_REGEX.findAll(classText)
            .map { it.groupValues[1] }
            .filter { it !in MODIFIER_KEYWORDS && it !in METHOD_KEYWORDS }
            .distinct()
            .toList()

    /** Test accessor for [extractFieldNames]. */
    internal fun extractFieldNamesPublic(classText: String): List<String> = extractFieldNames(classText)

    /**
     * Splits an identifier into lowercase words: camelCase, PascalCase, snake_case.
     * `findByBannedRoles` -> `[find, by, banned, roles]`.
     */
    internal fun wordsOf(name: String): List<String> =
        name.replace("_", " ")
            .split(Regex("(?<=[a-z0-9])(?=[A-Z])|\\s+"))
            .filter { it.isNotBlank() }
            .map { it.lowercase() }

    /**
     * Word-level similarity: equality, plural-insensitive equality, or one word being
     * a prefix of the other with at least 3 characters (`ban` ~ `banned`).
     */
    internal fun wordMatches(
        a: String,
        b: String,
    ): Boolean {
        if (a == b) return true
        val singularA = a.removeSuffix("s")
        val singularB = b.removeSuffix("s")
        if (singularA == singularB) return true
        if (a.length >= 3 && b.length >= 3 && (a.startsWith(b) || b.startsWith(a))) return true
        return false
    }

    /** True when every word in [words] matches some word in [other]. */
    private fun wordsCovered(
        words: List<String>,
        other: List<String>,
    ): Boolean = words.all { w -> other.any { o -> wordMatches(w, o) } }

    /** True when field `banRoles` is conceptually covered by method `findByBannedRoles`. */
    internal fun fieldCoveredByMethod(
        field: String,
        method: String,
    ): Boolean = wordsCovered(wordsOf(field), wordsOf(method))

    /**
     * True when [candidate] names the same concept as some existing method, e.g.
     * `findByBanRoles` collides with `findByBannedRoles`.
     *
     * Collision requires the same word count and every word matching in both
     * directions: a longer, genuinely different name (`findByIncompatibleProductIds`
     * vs `findByProductIds`) is not a duplicate.
     */
    internal fun nameCollides(
        candidate: String,
        existing: Collection<String>,
    ): Boolean {
        val candidateWords = wordsOf(candidate)
        if (candidateWords.isEmpty()) return false
        return existing.any { method ->
            val methodWords = wordsOf(method)
            methodWords.size == candidateWords.size &&
                wordsCovered(methodWords, candidateWords) &&
                wordsCovered(candidateWords, methodWords)
        }
    }

    /**
     * Extracts the method name from a completion that starts (or continues) a method
     * signature, e.g. `"public async findByBannedRoles(bannedRoles: ..."` -> `findByBannedRoles`.
     * Returns null for text that does not look like a signature line (e.g. a method body).
     */
    internal fun extractMethodNameFromCompletion(declarationText: String): String? =
        DECL_NAME_REGEX.find(declarationText)?.groupValues?.get(1)

    /**
     * Post-filter helper for the tracker: checks whether the primary completion, placed
     * back into the document at [startIndex], re-declares a method that already exists.
     *
     * The completion may continue a half-typed name (`findByBann` + `edRoles(`), so the
     * document text from the line start is concatenated with the completion first.
     */
    fun completionDuplicatesExistingMethod(
        documentText: String,
        startIndex: Int,
        completion: String,
        existingMethods: Collection<String>,
    ): Boolean {
        val start = startIndex.coerceIn(0, documentText.length)
        val lineStart =
            documentText.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val prefix = documentText.substring(lineStart, start)
        val name = extractMethodNameFromCompletion(prefix + completion) ?: return false
        if (name in METHOD_KEYWORDS || name in MODIFIER_KEYWORDS) return false
        return nameCollides(name, existingMethods)
    }
}
