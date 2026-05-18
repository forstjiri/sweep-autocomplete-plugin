package dev.sweep.assistant.listener

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.services.LocalAutocompleteServerManager

/**
 * Cleans up the locally-launched autocomplete server when the plugin is being unloaded.
 */
class PluginUnloadListener : DynamicPluginListener {
    companion object {
        private val logger = Logger.getInstance(PluginUnloadListener::class.java)
        private const val SWEEP_PLUGIN_ID = "dev.sweep.assistant"
    }

    override fun beforePluginUnload(
        pluginDescriptor: IdeaPluginDescriptor,
        isUpdate: Boolean,
    ) {
        if (pluginDescriptor.pluginId.idString != SWEEP_PLUGIN_ID) return
        try {
            LocalAutocompleteServerManager.getInstance().stopServer()
        } catch (e: Throwable) {
            logger.warn("Failed to stop local autocomplete server during unload", e)
        }
    }
}
