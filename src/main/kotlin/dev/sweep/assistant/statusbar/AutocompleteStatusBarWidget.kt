package dev.sweep.assistant.statusbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.util.Consumer
import dev.sweep.assistant.services.AutocompleteSnoozeService
import dev.sweep.assistant.services.LocalAutocompleteServerManager
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.settings.SweepSettingsConfigurable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import javax.swing.Icon

class AutocompleteStatusBarWidget(
    private val project: Project,
) : StatusBarWidget,
    StatusBarWidget.IconPresentation,
    Disposable {
    companion object {
        const val ID = "SweepAutocompleteStatus"
        private const val CHECK_INTERVAL_MS = 10_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var isAlive = false
    @Volatile
    private var isChecking = false
    @Volatile
    private var isLoadingSuggestions = false
    private val spinnerIcon = AnimatedIcon.Default()
    private val removeLoadingListener =
        AutocompleteLoadingNotifier.addListener { loading ->
            isLoadingSuggestions = loading
            updateWidget()
        }
    private val snoozeService = AutocompleteSnoozeService.getInstance(project)
    private val snoozeStateListener = { updateWidget() }
    private val clickHandler = Consumer<MouseEvent> { event -> showPopupMenu(event) }

    init {
        Disposer.register(SweepProjectService.getInstance(project), this)
        snoozeService.addSnoozeStateListener(snoozeStateListener)
        startHealthCheck()
    }

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        // no-op
    }

    override fun dispose() {
        removeLoadingListener()
        snoozeService.removeSnoozeStateListener(snoozeStateListener)
        scope.cancel()
    }

    override fun getIcon(): Icon {
        // While suggestions are loading, replace the static icon with the platform
        // spinner. The animated icon repaints the status bar by itself.
        val enabled =
            SweepSettings.getInstance().nextEditPredictionFlagOn && !snoozeService.isAutocompleteSnooze()
        if (isLoadingSuggestions && enabled) return spinnerIcon

        val base = IconLoader.getIcon("/icons/sweep16x16.svg", javaClass)
        return object : Icon {
            override fun paintIcon(
                c: java.awt.Component?,
                g: java.awt.Graphics?,
                x: Int,
                y: Int,
            ) {
                val g2 = g as? java.awt.Graphics2D
                val original = g2?.composite
                val shouldDim = snoozeService.isAutocompleteSnooze() ||
                    !isAlive ||
                    !SweepSettings.getInstance().nextEditPredictionFlagOn
                if (shouldDim) {
                    g2?.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.5f)
                }
                base.paintIcon(c, g, x, y)
                g2?.composite = original

                g2?.let { graphics ->
                    val dotColor = when {
                        !SweepSettings.getInstance().nextEditPredictionFlagOn ||
                            snoozeService.isAutocompleteSnooze() -> Color.GRAY
                        isChecking -> Color.ORANGE
                        isAlive -> Color(0x35, 0xB7, 0x5D)
                        else -> Color(0xD9, 0x3F, 0x3F)
                    }
                    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    graphics.color = dotColor
                    graphics.fillOval(x + iconWidth - 7, y + 1, 6, 6)
                }
            }

            override fun getIconWidth(): Int = base.iconWidth
            override fun getIconHeight(): Int = base.iconHeight
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = clickHandler

    override fun getTooltipText(): String =
        when {
            !SweepSettings.getInstance().nextEditPredictionFlagOn -> "Vulcan Sweep: Disabled - Click for options"
            snoozeService.isAutocompleteSnooze() -> "Vulcan Sweep: Snoozed (${snoozeService.formatRemainingTime()})"
            isLoadingSuggestions -> "Vulcan Sweep: Loading suggestions - Click for options"
            isChecking -> "Vulcan Sweep: Checking local server - Click for options"
            isAlive -> "Vulcan Sweep: Online - Click for options"
            else -> "Vulcan Sweep: Offline - Click for options"
        }

    private fun startHealthCheck() {
        scope.launch {
            while (isActive) {
                isChecking = true
                updateWidget()
                isAlive = LocalAutocompleteServerManager.getInstance().isServerHealthy()
                isChecking = false
                updateWidget()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun showPopupMenu(event: MouseEvent) {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val settings = SweepSettings.getInstance()
        items.add("Local server: ${when {
            isChecking -> "checking..."
            isAlive -> "running"
            else -> "not running"
        }}")
        actions.add { }

        if (settings.nextEditPredictionFlagOn) {
            items.add("Disable Vulcan Sweep")
            actions.add { settings.nextEditPredictionFlagOn = false; updateWidget() }
        } else {
            items.add("Enable Vulcan Sweep")
            actions.add { settings.nextEditPredictionFlagOn = true; updateWidget() }
        }

        if (snoozeService.isAutocompleteSnooze()) {
            items.add("Cancel snooze (${snoozeService.formatRemainingTime()} remaining)")
            actions.add { snoozeService.unsnooze(); updateWidget() }
        } else {
            listOf(
                "Snooze for 5 minutes" to AutocompleteSnoozeService.SNOOZE_5_MINUTES,
                "Snooze for 15 minutes" to AutocompleteSnoozeService.SNOOZE_15_MINUTES,
                "Snooze for 30 minutes" to AutocompleteSnoozeService.SNOOZE_30_MINUTES,
                "Snooze for 1 hour" to AutocompleteSnoozeService.SNOOZE_1_HOUR,
                "Snooze for 2 hours" to AutocompleteSnoozeService.SNOOZE_2_HOURS,
            ).forEach { (label, duration) ->
                items.add(label)
                actions.add { snoozeService.snoozeAutocomplete(duration); updateWidget() }
            }
        }

        items.add("Retry connection to local server")
        actions.add {
            scope.launch {
                LocalAutocompleteServerManager.getInstance().startServerInTerminal(project)
                isAlive = LocalAutocompleteServerManager.getInstance().isServerHealthy()
                updateWidget()
            }
        }

        items.add("Restart terminal server")
        actions.add {
            LocalAutocompleteServerManager.getInstance().restartServerInTerminal(project)
            isAlive = false
            updateWidget()
        }

        items.add("Open settings")
        actions.add {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SweepSettingsConfigurable::class.java)
        }

        val step = object : BaseListPopupStep<String>("Vulcan Sweep", items) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    val index = items.indexOf(selectedValue)
                    if (index >= 0) actions[index].invoke()
                }
                return PopupStep.FINAL_CHOICE
            }
        }

        val popup = JBPopupFactory.getInstance().createListPopup(step)
        val component = event.component
        popup.show(com.intellij.ui.awt.RelativePoint(component, java.awt.Point(0, -popup.content.preferredSize.height)))
    }

    private fun updateWidget() {
        val statusBar = WindowManager.getInstance().getStatusBar(project) ?: return
        statusBar.updateWidget(ID)
    }
}
