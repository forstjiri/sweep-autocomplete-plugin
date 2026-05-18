package dev.sweep.assistant.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.InvalidPathException
import kotlin.math.min

/**
 * Extract a range of text from a Document, applying optional line/char limits.
 */
private fun extractTextFromDocument(
    document: Document,
    maxLines: Int,
    maxChars: Int,
): String {
    if (maxLines == -1 && maxChars == -1) {
        return document.text
    }

    val totalLines = document.lineCount
    val linesToExtract = if (maxLines == -1) totalLines else min(totalLines, maxLines)
    if (linesToExtract == 0) return ""

    val endLineOffset = document.getLineEndOffset(linesToExtract - 1)
    val textRange = TextRange(0, min(endLineOffset, document.textLength))
    var extractedText = document.charsSequence.subSequence(textRange.startOffset, textRange.endOffset).toString()

    val truncatedByChars = maxChars != -1 && extractedText.length > maxChars
    if (truncatedByChars) {
        extractedText = extractedText.substring(0, maxChars)
    }

    val truncatedByLines = maxLines != -1 && totalLines > maxLines
    return if (truncatedByLines || truncatedByChars) {
        buildString {
            append(extractedText)
            append("\n\n[File contents truncated: ")
            if (truncatedByLines) append("showing first $linesToExtract of $totalLines lines")
            if (truncatedByChars) {
                if (truncatedByLines) append(", ")
                append("limited to $maxChars characters")
            }
            append("]")
        }
    } else {
        extractedText
    }
}

private fun truncateText(
    text: String,
    maxLines: Int,
    maxChars: Int,
): String {
    if (maxLines == -1 && maxChars == -1) return text
    if (text.isEmpty()) return text

    val lines = text.lines()
    val totalLines = lines.size
    val linesToTake = if (maxLines == -1) totalLines else min(totalLines, maxLines)

    val linesTruncated = lines.take(linesToTake)
    var joinedText = linesTruncated.joinToString("\n")

    val truncatedByChars = maxChars != -1 && joinedText.length > maxChars
    if (truncatedByChars) {
        joinedText = joinedText.substring(0, maxChars)
    }

    val truncatedByLines = maxLines != -1 && totalLines > maxLines
    return if (truncatedByLines || truncatedByChars) {
        buildString {
            append(joinedText)
            append("\n\n[File contents truncated: ")
            if (truncatedByLines) append("showing first $linesToTake of $totalLines lines")
            if (truncatedByChars) {
                if (truncatedByLines) append(", ")
                append("limited to $maxChars characters")
            }
            append("]")
        }
    } else {
        joinedText
    }
}

private fun readFileWithLimits(
    file: File,
    maxLines: Int,
    maxChars: Int,
): String? {
    if (!file.exists() || !file.canRead()) return null

    if (maxLines == -1 && maxChars == -1) {
        return file.readText()
    }

    val fileSize = file.length()
    val estimatedSafeSize = if (maxLines == -1) Long.MAX_VALUE else maxLines * 100L

    return if (fileSize <= estimatedSafeSize) {
        truncateText(file.readText(), maxLines, maxChars)
    } else {
        val lines = mutableListOf<String>()
        var totalChars = 0
        var reachedLimit = false

        file.bufferedReader().use { reader ->
            var lineCount = 0
            while (maxLines == -1 || lineCount < maxLines) {
                val line = reader.readLine() ?: break

                if (maxChars != -1 && totalChars + line.length + 1 > maxChars) {
                    val remainingChars = maxChars - totalChars - 1
                    if (remainingChars > 0) {
                        lines.add(line.substring(0, min(line.length, remainingChars)))
                    }
                    reachedLimit = true
                    break
                }

                lines.add(line)
                totalChars += line.length + 1
                lineCount++
            }
        }

        val result = lines.joinToString("\n")
        if (reachedLimit || (maxLines != -1 && lines.size >= maxLines)) {
            result + "\n\n[File contents truncated: showing first ${lines.size} lines, limited to $maxChars characters]"
        } else {
            result
        }
    }
}

fun readFile(
    project: Project,
    filePath: String,
    maxLines: Int = -1,
    maxChars: Int = -1,
): String? {
    val application = ApplicationManager.getApplication()
    val maxFileSize = SweepConstants.MAX_FILE_SIZE_BYTES
    val normalized = FileUtil.toSystemIndependentName(filePath)

    fun readFromEditor(): String? {
        if (project.isDisposed) return null
        return FileEditorManager
            .getInstance(project)
            .allEditors
            .mapNotNull { it.file }
            .find { it.path.endsWith(normalized) }
            ?.let { file ->
                if (file.length > maxFileSize) {
                    null
                } else {
                    FileDocumentManager.getInstance().getDocument(file)?.let { document ->
                        extractTextFromDocument(document, maxLines, maxChars)
                    }
                }
            }
    }

    val textFromEditor =
        if (application.isReadAccessAllowed) {
            readFromEditor()
        } else {
            application.runReadAction<String?> { readFromEditor() }
        }

    return textFromEditor
        ?: runCatching {
            val basePath = project.osBasePath
            val file =
                if (basePath != null) {
                    File(basePath, normalized).takeIf { it.exists() && it.canRead() }
                } else {
                    File(normalized).takeIf { it.exists() && it.canRead() }
                }
            if (file != null && file.length() > maxFileSize) {
                null
            } else {
                file?.let { readFileWithLimits(it, maxLines, maxChars) }
            }
        }.getOrNull()
}

fun readFile(
    project: Project,
    vFile: VirtualFile?,
    maxLines: Int = -1,
    maxChars: Int = -1,
): String? {
    val filePath = relativePath(project, vFile) ?: return null
    return readFile(project, filePath, maxLines, maxChars)
}

fun getVirtualFile(
    project: Project,
    path: String,
    refresh: Boolean = false,
): VirtualFile? {
    val absolutePath = absolutePath(project, path)
    return if (refresh) {
        LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
    } else {
        LocalFileSystem.getInstance().findFileByPath(absolutePath)
    }
}

fun relativePath(
    project: Project,
    vf: VirtualFile?,
): String? =
    runCatching {
        vf?.path?.takeIf { project.osBasePath != null }?.let {
            File(it).relativeTo(File(project.osBasePath!!)).toString()
        }
    }.getOrNull()?.takeUnless { it.isBlank() || it.startsWith("..") }

fun relativePath(
    basePath: String,
    fullPath: String,
): String? {
    if (BLOCKED_URL_PREFIXES.any { fullPath.startsWith(it) }) {
        return null
    }
    return try {
        val basePathNorm = File(basePath).toPath().normalize().toString()
        val fullPathNorm = File(fullPath).toPath().normalize().toString()
        if (fullPathNorm.startsWith(basePathNorm)) {
            fullPathNorm.substring(basePathNorm.length).trimStart(File.separatorChar)
        } else {
            null
        }
    } catch (e: InvalidPathException) {
        null
    }
}

fun relativePath(
    project: Project,
    fullPath: String,
): String? {
    if (project.isDisposed) {
        return project.osBasePath?.let { basePath -> relativePath(basePath, fullPath) }
    }
    val basePath = project.osBasePath ?: return null
    return relativePath(basePath, fullPath)
}

fun absolutePath(
    project: Project,
    relativePath: String,
): String {
    if (File(relativePath).isAbsolute) return relativePath
    if (project.isDisposed) return File(relativePath).absolutePath
    return File(project.osBasePath ?: "", relativePath).path
}

fun getSafeStartAndEndLines(
    document: Document,
    startLine: Int,
    endLine: Int,
): Pair<Int, Int> {
    val total = document.lineCount.coerceAtLeast(0)
    val safeStart = startLine.coerceIn(0, (total - 1).coerceAtLeast(0))
    val safeEnd = endLine.coerceIn(safeStart, (total - 1).coerceAtLeast(0))
    return safeStart to safeEnd
}
