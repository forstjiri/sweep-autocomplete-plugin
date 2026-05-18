package dev.sweep.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "SweepMetaData", storages = [Storage("SweepMetaData.xml")])
class SweepMetaData : PersistentStateComponent<SweepMetaData.MetaData> {
    data class MetaData(
        // Whether the user has used ACTION_CHOOSE_LOOKUP_ITEM (pressed Enter on autocomplete)
        var hasUsedLookupItem: Boolean = false,
        // Number of autocomplete suggestions the user has accepted via TAB
        var ghostTextTabAcceptCount: Int = 0,
        // Map of tip hash to show count (to bias towards showing new tips and limit to N shows per tip)
        var tipShowCounts: MutableMap<Int, Int> = mutableMapOf(),
        // Local privacy mode toggle (no remote calls regardless, but autocomplete metrics may want to skip)
        var privacyModeEnabled: Boolean = false,
        // Whether the autocomplete badge has been seen / dismissed
        var hasHandledPluginConflictsOnFirstInstall: Boolean = false,
        // User-dismissed notification toggles
        var dontShowShortcutNotifications: Boolean = false,
        var dontShowConflictNotifications: Boolean = false,
        var dontShowCmdJConflictNotifications: Boolean = false,
        // Versions for which the "what's new" notification has been shown
        var shownUpdateVersions: MutableList<String> = mutableListOf(),
    )

    private var metaData = MetaData()

    override fun getState(): MetaData = metaData

    override fun loadState(state: MetaData) {
        this.metaData =
            state.copy(
                shownUpdateVersions = state.shownUpdateVersions.toMutableList(),
                tipShowCounts = state.tipShowCounts.toMutableMap(),
            )
    }

    var hasUsedLookupItem: Boolean
        get() = metaData.hasUsedLookupItem
        set(value) {
            metaData.hasUsedLookupItem = value
        }

    var autocompleteAcceptCount: Int
        get() = metaData.ghostTextTabAcceptCount
        set(value) {
            metaData.ghostTextTabAcceptCount = value
        }

    var privacyModeEnabled: Boolean
        get() = metaData.privacyModeEnabled
        set(value) {
            metaData.privacyModeEnabled = value
        }

    var hasHandledPluginConflictsOnFirstInstall: Boolean
        get() = metaData.hasHandledPluginConflictsOnFirstInstall
        set(value) {
            metaData.hasHandledPluginConflictsOnFirstInstall = value
        }

    var dontShowShortcutNotifications: Boolean
        get() = metaData.dontShowShortcutNotifications
        set(value) {
            metaData.dontShowShortcutNotifications = value
        }

    var dontShowConflictNotifications: Boolean
        get() = metaData.dontShowConflictNotifications
        set(value) {
            metaData.dontShowConflictNotifications = value
        }

    var dontShowCmdJConflictNotifications: Boolean
        get() = metaData.dontShowCmdJConflictNotifications
        set(value) {
            metaData.dontShowCmdJConflictNotifications = value
        }

    var shownUpdateVersions: MutableList<String>
        get() = metaData.shownUpdateVersions
        set(value) {
            metaData.shownUpdateVersions = value
        }

    fun hasShownUpdateForVersion(version: String): Boolean = metaData.shownUpdateVersions.contains(version)

    fun markUpdateAsShown(version: String) {
        if (!metaData.shownUpdateVersions.contains(version)) {
            metaData.shownUpdateVersions.add(version)
        }
    }

    @Synchronized
    fun getTipShowCount(tipHash: Int): Int = metaData.tipShowCounts[tipHash] ?: 0

    @Synchronized
    fun incrementTipShowCount(tipHash: Int) {
        val currentCount = metaData.tipShowCounts[tipHash] ?: 0
        metaData.tipShowCounts[tipHash] = currentCount + 1
    }

    companion object {
        fun getInstance(): SweepMetaData = ApplicationManager.getApplication().getService(SweepMetaData::class.java)
    }
}
