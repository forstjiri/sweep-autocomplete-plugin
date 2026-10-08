package dev.sweep.assistant.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BoundedDiffRenderingTest {
    @Test fun `shared rendering preserves warning prefix headers separators and descending stable order`() {
        val small = DiffInfo("Added new file (unversioned)", "new.kt", listOf("+a"))
        val large = DiffInfo("Modified file", "old.kt", listOf("-old value", "+new value"))
        val prefix = StringBuilder("Skipped large file: huge.kt (size exceeds 20MB)\n\n")
        assertEquals(prefix.toString() + "Modified file: old.kt\n-old value\n+new value\n\n" +
            "Added new file (unversioned): new.kt\n+a\n\n", renderBoundedDiffs(listOf(small, large), prefix))
        val equalA = DiffInfo("Modified", "a", listOf("line"))
        val equalB = equalA.copy(fileName = "b")
        assertTrue(renderBoundedDiffs(listOf(equalB, equalA)).startsWith("Modified: b\n"))
    }

    @Test fun `oversized largest diff trims whole lines and leaves space for another diff`() {
        val line = "a".repeat(200_000)
        val large = DiffInfo("Modified", "large", listOf(line, "b".repeat(70_000)))
        val small = DiffInfo("Modified", "small", listOf("+small"))
        assertEquals("Modified: large\n$line\n... (diff truncated)\n\nModified: small\n+small\n\n",
            renderBoundedDiffs(listOf(small, large)))
    }

    @Test fun `total budget skips non-fitting diffs and still includes smaller later diffs`() {
        val diffs = listOf(200_000, 180_000, 150_000, 100).mapIndexed { i, n ->
            DiffInfo("Modified", "file$i", listOf("x".repeat(n)))
        }
        val result = renderBoundedDiffs(diffs)
        assertTrue(result.contains("file0\n"))
        assertTrue(result.contains("file1\n"))
        assertFalse(result.contains("file2\n"))
        assertTrue(result.contains("file3\n"))
        assertFalse(result.contains("diff truncated"))
    }

    @Test fun `empty collections preserve skipped-file messages`() {
        assertEquals("", renderBoundedDiffs(emptyList()))
        assertEquals("warning\n\n", renderBoundedDiffs(emptyList(), StringBuilder("warning\n\n")))
    }
}
