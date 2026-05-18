package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * No-op feature flag service. Cloud-side flag fetching has been removed; every query
 * returns the supplied default (or `false`/empty).
 */
@Service(Service.Level.PROJECT)
class FeatureFlagService(
    @Suppress("UNUSED_PARAMETER") private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): FeatureFlagService = project.getService(FeatureFlagService::class.java)
    }

    fun isFeatureEnabled(@Suppress("UNUSED_PARAMETER") flagKey: String): Boolean = false

    fun getFeatureFlag(@Suppress("UNUSED_PARAMETER") flagKey: String): String? = null

    fun getNumericFeatureFlag(
        @Suppress("UNUSED_PARAMETER") flagKey: String,
        defaultValue: Int,
    ): Int = defaultValue

    fun getStringFeatureFlag(
        @Suppress("UNUSED_PARAMETER") flagKey: String,
        defaultValue: String,
    ): String = defaultValue

    fun getAllFeatureFlags(): Map<String, String> = emptyMap()

    fun isInitialized(): Boolean = true

    fun refreshFeatureFlags() {
        // no-op
    }

    override fun dispose() {
        // no-op
    }
}
