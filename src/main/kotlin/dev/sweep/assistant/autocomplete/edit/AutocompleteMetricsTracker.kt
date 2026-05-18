package dev.sweep.assistant.autocomplete.edit

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project

/**
 * Local-only autocomplete metrics tracker. The cloud telemetry endpoint has been removed;
 * this class keeps the call surface intact but is a no-op.
 */
@Service(Service.Level.PROJECT)
class AutocompleteMetricsTracker(
    @Suppress("UNUSED_PARAMETER") private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): AutocompleteMetricsTracker =
            project.getService(AutocompleteMetricsTracker::class.java)
    }

    fun trackSuggestionShown(@Suppress("UNUSED_PARAMETER") suggestion: AutocompleteSuggestion) {
        // no-op
    }

    fun trackSuggestionDisposed(@Suppress("UNUSED_PARAMETER") suggestion: AutocompleteSuggestion) {
        // no-op
    }

    fun trackSuggestionAccepted(@Suppress("UNUSED_PARAMETER") suggestion: AutocompleteSuggestion) {
        // no-op
    }

    @Suppress("UNUSED_PARAMETER")
    fun trackFileContentsAfterDelay(
        document: Document,
        rangeMarker: RangeMarker?,
        suggestionType: String? = null,
        additionsAndDeletions: Pair<Int, Int> = 0 to 0,
        autocompleteId: String = "",
    ) {
        // no-op
    }

    override fun dispose() {
        // no-op
    }
}
