package dev.sweep.assistant.startup

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.sweep.assistant.autocomplete.edit.AcceptEditCompletionAction
import dev.sweep.assistant.autocomplete.edit.EditorActionsRouterService
import dev.sweep.assistant.autocomplete.edit.RecentEditsTracker
import dev.sweep.assistant.autocomplete.edit.RejectEditCompletionAction
import dev.sweep.assistant.services.NotificationDeduplicationService
import dev.sweep.assistant.services.RipgrepManager
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.disableFullLineCompletion
import dev.sweep.assistant.utils.disableFullLineCompletionAndNotify
import dev.sweep.assistant.utils.showNotification
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

class SweepStartupActivity : ProjectActivity {
    private val logger = Logger.getInstance(SweepStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        // Initialize application-level services
        RipgrepManager.getInstance()

        // Initialize project-level services autocomplete depends on
        SweepProjectService.getInstance(project)
        NotificationDeduplicationService.getInstance(project)
        RecentEditsTracker.getInstance(project)

        // Install the application-level accept/reject action router
        EditorActionsRouterService.getInstance()

        // Auto-start local autocomplete server if autocomplete is enabled
        if (SweepSettings.getInstance().nextEditPredictionFlagOn) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val manager = dev.sweep.assistant.services.LocalAutocompleteServerManager.getInstance()
                if (!manager.isServerHealthy()) {
                    manager.startServerInTerminal(project)
                }
            }
        }

        // Suppress noisy KtLint plugin errors
        val loggerIncompatiblePlugins = listOf(PluginId.getId("io.jmix.studio"))
        val hasLoggerIncompatiblePlugin =
            loggerIncompatiblePlugins.any { id ->
                PluginManagerCore.isPluginInstalled(id) && PluginManagerCore.getPlugin(id)?.isEnabled == true
            }
        if (!hasLoggerIncompatiblePlugin) {
            try {
                Logger.getInstance("com.nbadal.ktlint.KtlintAnnotator").setLevel(LogLevel.OFF)
            } catch (e: Throwable) {
                logger.debug("Could not set log level for ktlint logger", e)
            }
        }

        // Make sure accept / reject keyboard shortcuts are bound
        ApplicationManager.getApplication().invokeLater {
            ensureEditAutocompleteActionsAreBound()
        }

        // Handle conflicting autocomplete plugins
        handleFullLineCompletionConflicts(project)
    }

    private fun handleFullLineCompletionConflicts(project: Project) {
        if (SweepSettings.getInstance().disableConflictingPlugins) {
            disableFullLineCompletion(project)
        } else {
            ApplicationManager.getApplication().invokeLater {
                checkAndNotifyConflictingPlugins(project)
            }
        }
    }

    private fun checkAndNotifyConflictingPlugins(project: Project) {
        val metaData = SweepMetaData.getInstance()
        val enabledConflictingPlugins =
            SweepConstants.PLUGINS_TO_DISABLE
                .filter { PluginManagerCore.isPluginInstalled(it) && PluginManagerCore.getPlugin(it)?.isEnabled == true }
        if (enabledConflictingPlugins.isEmpty()) return
        if (metaData.dontShowConflictNotifications) return

        val pluginNames =
            enabledConflictingPlugins.joinToString(", ") { id ->
                SweepConstants.PLUGIN_ID_TO_NAME[id] ?: PluginManagerCore.getPlugin(id)?.name ?: id.idString
            }

        showNotification(
            project = project,
            title = "Conflicting Autocomplete Plugins Detected",
            body = "Sweep detected potentially conflicting plugins: $pluginNames. " +
                "These plugins may interfere with Vulcan Sweep. " +
                "You can manage them in Settings > Plugins.",
            notificationGroup = "Vulcan Sweep",
            notificationType = NotificationType.WARNING,
            action = object : NotificationAction("Disable autocomplete for these plugins") {
                override fun actionPerformed(
                    e: AnActionEvent,
                    notification: com.intellij.notification.Notification,
                ) {
                    notification.expire()
                    metaData.dontShowConflictNotifications = true
                    disableFullLineCompletionAndNotify(project)
                }
            },
            action2 = object : NotificationAction("Don't show again") {
                override fun actionPerformed(
                    e: AnActionEvent,
                    notification: com.intellij.notification.Notification,
                ) {
                    notification.expire()
                    metaData.dontShowConflictNotifications = true
                }
            },
        )
    }

    private fun ensureEditAutocompleteActionsAreBound() {
        val keymap = KeymapManager.getInstance().activeKeymap
        val acceptActionId = AcceptEditCompletionAction.ACTION_ID
        val rejectActionId = RejectEditCompletionAction.ACTION_ID

        if (keymap.getShortcuts(acceptActionId).isEmpty()) {
            keymap.addShortcut(
                acceptActionId,
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), null),
            )
        }
        if (keymap.getShortcuts(rejectActionId).isEmpty()) {
            keymap.addShortcut(
                rejectActionId,
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), null),
            )
        }
    }
}
