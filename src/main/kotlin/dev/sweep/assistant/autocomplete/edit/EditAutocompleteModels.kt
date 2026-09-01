package dev.sweep.assistant.autocomplete.edit

import kotlinx.serialization.Serializable

const val MAX_HUNK_SIZE = 10
const val MAX_TOKEN_COUNT = 8192
const val AVG_TOKEN_LENGTH = 4

enum class AutocompleteDisposeReason {
    ACCEPTED,
    ESCAPE_PRESSED,
    AUTOCOMPLETE_DISPOSED,
    EDITOR_LOST_FOCUS,
    CLEARING_PREVIOUS_AUTOCOMPLETE,
    CARET_POSITION_CHANGED,
    EDITOR_FOCUS_CHANGED,
    IMPORT_FIX_SHOWN,
    LOOKUP_SHOWN,
}

data class EditRecord(
    val originalText: String,
    val newText: String,
    val filePath: String,
    val offset: Int,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val diff = calculateDiff(originalText, newText)
    val formattedDiff = "File: $filePath\n$diff"
    val diffHunks: Int = countDiffHunks(diff)

    fun isTooLarge(): Boolean = diff.length > MAX_TOKEN_COUNT * AVG_TOKEN_LENGTH

    fun isNoOpDiff(): Boolean = diff.trim().isEmpty()
}

data class EditorState(
    val documentText: String,
    val line: Int,
    val cursorOffset: Int,
    val filePath: String,
    val documentLineCount: Int,
    /** Pre-computed line prefix (from line start to cursor) to avoid recomputing from full documentText */
    val currentLinePrefix: String = "",
) {
    private val prefix get() = documentText.substring(0, cursorOffset)
    private val suffix get() = documentText.substring(cursorOffset)

    fun returnInsertionTextOrNull(newDocumentText: String): String? {
        if (newDocumentText.startsWith(prefix) && newDocumentText.endsWith(suffix) && newDocumentText.length >= documentText.length) {
            return newDocumentText.removePrefix(prefix).removeSuffix(suffix)
        }
        return null
    }
}

@Serializable
data class EditorDiagnostic(
    val line: Int,
    val start_offset: Int,
    val end_offset: Int,
    val severity: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * The server renders {initial_file} from [NextEditAutocompleteRequest.original_file_contents] but
 * locates the cursor with the *current* cursor_position / line numbers. If anything above the
 * cursor line changed since the baseline was captured (inserted/deleted/edited lines), those
 * coordinates point at the wrong spot in the rendered file and the model proposes edits on the
 * wrong line.
 *
 * To keep both coordinate spaces in sync we resend the current contents as the baseline whenever
 * the text above the cursor line is no longer identical. The "what changed recently" signal is
 * unaffected: it is carried by recent_changes_high_res / recent_user_actions, not by this field.
 */
fun effectiveOriginalFileContents(
    originalFileContents: String,
    currentFileContents: String,
    cursorPosition: Int,
): String {
    if (originalFileContents == currentFileContents) return originalFileContents
    val clampedCursor = cursorPosition.coerceIn(0, currentFileContents.length)
    val cursorLine = currentFileContents.subSequence(0, clampedCursor).count { it == '\n' }
    val linesAboveCursorMatch =
        originalFileContents.split('\n').take(cursorLine) == currentFileContents.split('\n').take(cursorLine)
    return if (linesAboveCursorMatch) originalFileContents else currentFileContents
}

@Serializable
data class NextEditAutocompleteRequest(
    val repo_name: String,
    val branch: String? = null,
    val file_path: String,
    val file_contents: String,
    val recent_changes: String,
    val cursor_position: Int,
    val original_file_contents: String,
    val file_chunks: List<@Serializable FileChunk>,
    val retrieval_chunks: List<@Serializable FileChunk>,
    val recent_user_actions: List<@Serializable UserAction>,
    val multiple_suggestions: Boolean = true,
    val privacy_mode_enabled: Boolean = false,
    val client_ip: String? = null,
    val recent_changes_high_res: String,
    val changes_above_cursor: Boolean,
    val ping: Boolean = false,
    val editor_diagnostics: List<@Serializable EditorDiagnostic> = emptyList(),
    val steering: String? = null,
    val automatic_steering: String? = null,
    val avoid_completions: List<String> = emptyList(),
)

@Serializable
data class NextEditAutocompletion(
    var start_index: Int,
    var end_index: Int,
    var completion: String,
    val confidence: Float,
    val autocomplete_id: String,
) {
    fun adjustOffsets(offset: Int) {
        start_index += offset
        end_index += offset
    }

    fun applyChangesTo(original: String): String? {
        if (start_index < 0 || end_index < start_index || end_index > original.length) return null
        return original.substring(0, start_index) + completion + original.substring(end_index)
    }
}

@Serializable
data class NextEditAutocompleteResponse(
    var start_index: Int,
    var end_index: Int,
    val completion: String,
    val confidence: Float,
    val autocomplete_id: String,
    val elapsed_time_ms: Long? = null,
    // this is the new completion
    var completions: List<NextEditAutocompletion>,
) {
    fun adjustOffsets(offset: Int) {
        completions.forEach { it.adjustOffsets(offset) }
    }
}

data class CursorPositionRecord(
    val filePath: String,
    val line: Int,
    val cursorOffset: Int,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class UserAction(
    val action_type: UserActionType,
    val line_number: Int, // Line number after action is completed
    val offset: Int, // Offset after action is completed
    val file_path: String,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class UserActionType {
    INSERT_CHAR, // Individual character input
    INSERT_SELECTION, // Inserting multiple characters (Paste)
    DELETE_SELECTION, // Deletion of multiple characters
    DELETE_CHAR, // Individual character deletion
    UNDO,
    REDO,
    CURSOR_MOVEMENT,
}

@Serializable
data class FileChunk(
    val file_path: String,
    val start_line: Int,
    var end_line: Int,
    var content: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun truncate(maxSize: Int) {
        end_line = end_line.coerceAtMost(start_line + maxSize)
        content = content.lines().take((end_line - start_line + 1).coerceAtLeast(1)).joinToString("\n")
    }
}
