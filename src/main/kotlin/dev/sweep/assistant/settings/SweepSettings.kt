package dev.sweep.assistant.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "dev.sweep.jetbrains.settings.SweepSettings",
    storages = [Storage("SweepSettings.xml")],
)
class SweepSettings : PersistentStateComponent<SweepSettings> {
    companion object {
        private const val DEFAULT_NEXT_EDIT_PREDICTION_ON = true
        private const val DEFAULT_ACCEPT_WORD_ON_RIGHT_ARROW = true
        // -1L means "unset"; first access initializes to a sane default.
        private const val DEFAULT_AUTOCOMPLETE_DEBOUNCE_MS = -1L

        // Default to true - automatically disable conflicting autocomplete plugins on first run
        private const val DEFAULT_DISABLE_CONFLICTING_PLUGINS = true

        fun getInstance(): SweepSettings = ApplicationManager.getApplication().getService(SweepSettings::class.java)
    }

    // Do not notify settings changed on each save, fire it in config instead
    fun interface SettingsChangedNotifier {
        fun settingsChanged()

        companion object {
            @JvmField
            val TOPIC = Topic.create("Sweep settings changed", SettingsChangedNotifier::class.java)
        }
    }

    var nextEditPredictionFlagOn: Boolean = DEFAULT_NEXT_EDIT_PREDICTION_ON
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var acceptWordOnRightArrow: Boolean = DEFAULT_ACCEPT_WORD_ON_RIGHT_ARROW
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    /**
     * Autocomplete debounce delay in milliseconds, clamped to [10, 1000].
     * -1 means "unset"; callers should resolve to a default via [getDebounceThresholdMs].
     */
    var autocompleteDebounceMs: Long = DEFAULT_AUTOCOMPLETE_DEBOUNCE_MS
        set(value) {
            val clamped = if (value < 0L) value else value.coerceIn(10L, 1000L)
            field = clamped
        }

    /**
     * Effective debounce in ms. Uses 200 as the default when unset.
     */
    fun getDebounceThresholdMs(): Long = if (autocompleteDebounceMs <= 0L) 200L else autocompleteDebounceMs

    /**
     * Automatically disable conflicting autocomplete plugins.
     */
    var disableConflictingPlugins: Boolean = DEFAULT_DISABLE_CONFLICTING_PLUGINS
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    /**
     * Local autocomplete server port. The plugin runs `llama-server` on 127.0.0.1:<this>.
     */
    var autocompleteLocalPort: Int = 8081

    /**
     * Model id used by the native engine (see NesModelConfig).
     */
    var autocompleteLocalModel: String = "sweep-0.5B"

    /**
     * File-name patterns (globs) excluded from autocomplete suggestions.
     */
    var autocompleteExclusionPatterns: MutableSet<String> = mutableSetOf(".env")

    /**
     * Suppresses the "Vulcan Sweep is disabled for this file type" banner once dismissed.
     */
    var hideAutocompleteExclusionBanner: Boolean = false

    /**
     * Whether to always render the "Tab to accept" badge on autocomplete ghost text.
     */
    var showAutocompleteBadge: Boolean = false

    fun notifySettingsChanged() {
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager
                .getApplication()
                ?.messageBus
                ?.syncPublisher(SettingsChangedNotifier.TOPIC)
                ?.settingsChanged()
        }
    }

    fun runNowAndOnSettingsChange(
        project: Project,
        parentDisposable: Disposable,
        callback: SweepSettings.() -> Unit,
    ) {
        this.callback()
        project.messageBus.connect(parentDisposable).subscribe(
            SettingsChangedNotifier.TOPIC,
            SettingsChangedNotifier {
                getInstance().callback()
            },
        )
    }

    override fun getState(): SweepSettings = this

    override fun loadState(state: SweepSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }
}
