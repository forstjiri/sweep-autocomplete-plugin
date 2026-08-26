package dev.sweep.assistant.utils

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.Timer

private val logger = Logger.getInstance("dev.sweep.assistant.utils.Utils")

/** Shared kotlinx.serialization JSON instance used for autocomplete request/response serialization. */
val defaultJson: kotlinx.serialization.json.Json =
    kotlinx.serialization.json.Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

/**
 * Returns a colored version of the given icon by applying a color overlay.
 */
fun colorizeIcon(
    icon: Icon,
    color: Color,
): Icon {
    val width = icon.iconWidth
    val height = icon.iconHeight

    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g2d = image.createGraphics()

    icon.paintIcon(null, g2d, 0, 0)

    g2d.composite = java.awt.AlphaComposite.SrcAtop
    g2d.color = color
    g2d.fillRect(0, 0, width, height)
    g2d.dispose()

    return ImageIcon(image)
}

class EvictingQueue<T>(
    private val maxSize: Int,
) : ConcurrentLinkedQueue<T>() {
    override fun add(element: T): Boolean {
        val result = super.add(element)
        evict()
        return result
    }

    override fun addAll(elements: Collection<T>): Boolean {
        val result = super.addAll(elements)
        evict()
        return result
    }

    fun replaceLast(element: T): Boolean {
        if (isEmpty()) return false
        remove(last())
        return add(element)
    }

    private fun evict() {
        while (size > maxSize) {
            poll()
        }
    }
}

fun isIDEDarkMode(): Boolean =
    try {
        !JBColor.isBright()
    } catch (e: Throwable) {
        Logger.getInstance("ThemeDetection").warn("Error detecting IDE theme: ${e.message}")
        true
    }

fun getCurrentSweepPluginVersion(): String? =
    try {
        val sweepId = "dev.sweep.assistant"
        PluginManagerCore.getPlugin(PluginId.getId(sweepId))?.version
    } catch (e: Exception) {
        null
    }

fun getApplicationVersion(): String =
    try {
        ApplicationInfo.getInstance().fullVersion
    } catch (e: Exception) {
        logger.warn("Error getting application version: ${e.message}")
        "unknown"
    }

fun getDebugInfo(): String =
    try {
        val application = ApplicationInfo.getInstance()
        val sweepVersion = getCurrentSweepPluginVersion() ?: "unknown"
        val osName = System.getProperty("os.name")
        "${application.fullApplicationName} (${application.build}) - OS: $osName - Sweep v$sweepVersion"
    } catch (e: Exception) {
        logger.warn("Error getting IDE info: ${e.message}")
        "Unknown IDE"
    }

inline fun <reified T : Component> findParentComponent(component: Component): T? {
    var parent = component.parent
    while (parent != null) {
        if (parent is T) {
            return parent
        }
        parent = parent.parent
    }
    return null
}

class FocusGainedAdapter(
    private val listener: FocusAdapter.(e: FocusEvent) -> Unit,
) : FocusAdapter() {
    override fun focusGained(e: FocusEvent) {
        listener(e)
    }
}

class FocusLostAdaptor(
    private val listener: FocusAdapter.(e: FocusEvent) -> Unit,
) : FocusAdapter() {
    override fun focusLost(e: FocusEvent) {
        listener(e)
    }
}

class MouseClickedAdapter(
    private val listener: MouseAdapter.(e: MouseEvent) -> Unit,
) : MouseAdapter() {
    override fun mouseClicked(e: MouseEvent) {
        listener(e)
    }
}

class MousePressedAdapter(
    private val listener: MouseAdapter.(e: MouseEvent) -> Unit,
) : MouseAdapter() {
    override fun mousePressed(e: MouseEvent) {
        listener(e)
    }
}

class MouseReleasedAdapter(
    private val listener: MouseAdapter.(e: MouseEvent) -> Unit,
) : MouseAdapter() {
    override fun mouseReleased(e: MouseEvent) {
        if (!e.isConsumed) {
            listener(e)
        }
    }
}

class MouseEnteredAdapter(
    private val listener: MouseAdapter.(e: MouseEvent) -> Unit,
) : MouseAdapter() {
    override fun mouseEntered(e: MouseEvent) {
        listener(e)
    }
}

class MouseExitedAdapter(
    private val listener: MouseAdapter.(e: MouseEvent) -> Unit,
) : MouseAdapter() {
    override fun mouseExited(e: MouseEvent) {
        listener(e)
    }
}

class KeyPressedAdapter(
    private val listener: KeyAdapter.(e: KeyEvent) -> Unit,
) : KeyAdapter() {
    override fun keyPressed(e: KeyEvent) {
        listener(e)
    }
}

class KeyReleasedAdapter(
    private val listener: KeyAdapter.(e: KeyEvent) -> Unit,
) : KeyAdapter() {
    override fun keyReleased(e: KeyEvent) {
        listener(e)
    }
}

class KeyTypedAdapter(
    private val listener: KeyAdapter.(e: KeyEvent) -> Unit,
) : KeyAdapter() {
    override fun keyTyped(e: KeyEvent) {
        listener(e)
    }
}

class ComponentResizedAdapter(
    private val listener: ComponentAdapter.(e: ComponentEvent) -> Unit,
) : ComponentAdapter() {
    override fun componentResized(e: ComponentEvent) {
        listener(e)
    }
}

class ComponentMovedAdapter(
    private val listener: ComponentAdapter.(e: ComponentEvent) -> Unit,
) : ComponentAdapter() {
    override fun componentMoved(e: ComponentEvent) {
        listener(e)
    }
}

class ComponentShownAdapter(
    private val listener: ComponentAdapter.(e: ComponentEvent) -> Unit,
) : ComponentAdapter() {
    override fun componentShown(e: ComponentEvent) {
        listener(e)
    }
}

class ComponentHiddenAdapter(
    private val listener: ComponentAdapter.(e: ComponentEvent) -> Unit,
) : ComponentAdapter() {
    override fun componentHidden(e: ComponentEvent) {
        listener(e)
    }
}

class DocumentChangeListenerAdapter(
    private val listener: DocumentListener.(event: DocumentEvent) -> Unit,
) : DocumentListener {
    override fun documentChanged(event: DocumentEvent) = listener(event)
}

class CaretPositionChangedAdapter(
    private val listener: CaretListener.(event: CaretEvent) -> Unit,
) : CaretListener {
    override fun caretPositionChanged(event: CaretEvent) = listener(event)
}

class FileEditorSelectionChangedAdapter(
    private val listener: FileEditorManagerListener.(event: FileEditorManagerEvent) -> Unit,
) : FileEditorManagerListener {
    override fun selectionChanged(event: FileEditorManagerEvent) = listener(event)
}

class DocumentChangeListener(
    private val handler: DocumentChangeListener.(event: DocumentEvent) -> Unit,
) : DocumentListener {
    override fun documentChanged(event: DocumentEvent) = handler(event)
}

class AutoComponentListener(
    private val handler: (ComponentEvent) -> Unit,
) : ComponentListener {
    override fun componentResized(e: ComponentEvent) = handler(e)

    override fun componentMoved(e: ComponentEvent) = handler(e)

    override fun componentShown(e: ComponentEvent) = handler(e)

    override fun componentHidden(e: ComponentEvent) {}
}

class ActionPerformer(
    private val handler: ActionPerformer.(event: AnActionEvent) -> Unit,
) : AnAction() {
    override fun actionPerformed(event: AnActionEvent) = handler(event)
}

fun createAutoStartTimer(
    duration: Int,
    callback: () -> Unit,
) = Timer(duration) { callback() }.apply {
    isRepeats = false
    start()
}

fun max(
    instantA: Instant,
    instantB: Instant,
): Instant = if (instantA.isAfter(instantB)) instantA else instantB

fun userSpecificRepoName(project: Project): String {
    return project.basePath?.let { File(it).name } ?: "unknown"
}

fun showNotification(
    project: Project,
    title: String,
    body: String,
    notificationGroup: String = "Vulcan Sweep",
    notificationType: NotificationType = NotificationType.INFORMATION,
    icon: Icon? = null,
    action: NotificationAction? = null,
    action2: NotificationAction? = null,
) {
    ApplicationManager.getApplication().invokeLater {
        val group =
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(notificationGroup)

        if (group != null) {
            val notification = group.createNotification(title, body, notificationType)
            if (icon != null) notification.icon = icon
            if (action != null) notification.addAction(action)
            if (action2 != null) notification.addAction(action2)
            notification.notify(project)
        } else {
            logger.debug("Notification group '$notificationGroup' not available; skipping: $title")
        }
    }
}

/**
 * Glob-style match against a file name. Supports `*` and `?` only, and is anchored
 * (matches the full string). Patterns containing path separators are treated as
 * suffix matches.
 */
fun matchesExclusionPattern(
    fileName: String,
    pattern: String,
): Boolean {
    if (pattern.isEmpty()) return false
    if (pattern == fileName) return true

    val regex =
        buildString {
            append("^")
            for (ch in pattern) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    '.' -> append("\\.")
                    '\\', '+', '(', ')', '[', ']', '{', '}', '|', '^', '$' -> append('\\').append(ch)
                    else -> append(ch)
                }
            }
            append("$")
        }
    return try {
        Regex(regex).matches(fileName)
    } catch (e: Throwable) {
        false
    }
}

/**
 * Returns the keystrokes currently bound to the given action ID in the active keymap.
 */
fun getKeyStrokesForAction(actionId: String): List<javax.swing.KeyStroke> {
    val keymap = com.intellij.openapi.keymap.KeymapManager.getInstance().activeKeymap
    return keymap
        .getShortcuts(actionId)
        .asSequence()
        .filterIsInstance<com.intellij.openapi.actionSystem.KeyboardShortcut>()
        .flatMap { sequenceOf(it.firstKeyStroke, it.secondKeyStroke) }
        .filterNotNull()
        .toList()
}

/**
 * Renders a KeyStroke into a short, human-readable shortcut label.
 */
fun parseKeyStrokesToPrint(k: javax.swing.KeyStroke?): String? {
    if (k == null) return null
    val isMac = com.intellij.openapi.util.SystemInfo.isMac
    return k
        .toString()
        .replace("pressed ", "")
        .replace("meta", "⌘")
        .replace("control", "Ctrl")
        .replace("ctrl", "Ctrl")
        .replace("alt", if (isMac) "⌥" else "Alt")
        .replace("shift", if (isMac) "⇧" else "Shift")
        .replace("BACK_SPACE", "⌫")
        .replace("ENTER", "⏎")
        .replace(" ", if (isMac) "" else "+")
}

/** Common URL prefixes that should be treated as "non-file" inputs (terminals, scratches, ...). */
val BLOCKED_URL_PREFIXES: List<String> =
    listOf(
        "terminal://",
        "console://",
        "vcs://",
        "diff://",
        "mock://",
        "lightVirtualFile://",
    )

inline fun <T> measureTimeAndLog(
    label: String,
    block: () -> T,
): T {
    val start = System.nanoTime()
    val result = block()
    val durationMs = (System.nanoTime() - start) / 1_000_000.0
    Logger.getInstance("dev.sweep.assistant.timing").debug("$label took $durationMs ms")
    return result
}
