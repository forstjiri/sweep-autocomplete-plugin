@file:JvmName("AutocompleteHighlightingUtils")

package dev.sweep.assistant.autocomplete

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationInfo

/**
 * Adjusts the provided fullContext string based on the running IDE.
 *
 * Currently supported:
 * - PhpStorm: Prepend "<?php" followed by a newline if not already present.
 * - GoLand: Prepend "package test" and 'import "fmt"' followed by a newline if not already present.
 *
 * For other IDEs, the input is returned unchanged.
 */
fun adjustFullContextForIde(fullContext: String): String =
    try {
        val application = ApplicationInfo.getInstance()
        val appName = application.fullApplicationName
        when {
            appName.contains("PhpStorm", ignoreCase = true) -> {
                if (fullContext.trimStart().startsWith("<?php")) fullContext else "<?php\n$fullContext"
            }
            appName.contains("GoLand", ignoreCase = true) -> {
                if (fullContext.trimStart().startsWith("package")) fullContext else "package test\n$fullContext"
            }
            else -> fullContext
        }
    } catch (e: Exception) {
        // If anything goes wrong determining the IDE, return the original context unchanged
        fullContext
    }

/**
 * Determines whether we should run language annotators as part of semantic highlighting.
 *
 * Currently:
 * - PhpStorm, PyCharm, DataGrip, CLion, RustRover, Android Studio, RubyMine, Rider, GoLand, WebStorm, IntelliJ:
 *   controlled by per-IDE feature flag "<ide>-run-annotators" (off by default if Project is null)
 * - Others: true
 *
 * This will be expanded later with IDE-specific behavior.
 */
fun shouldRunAnnotatorsForSemanticHighlights(@Suppress("UNUSED_PARAMETER") project: Project?): Boolean = false
