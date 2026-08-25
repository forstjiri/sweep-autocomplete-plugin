package dev.sweep.assistant.autocomplete.edit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EffectiveOriginalFileContentsTest {
    private val original =
        """
        line 1
        line 2
        line 3
        line 4
        line 5
        """.trimIndent()

    private fun cursorAt(
        text: String,
        line: Int,
    ): Int = text.split('\n').take(line).joinToString("\n").length

    @Test
    fun `identical contents keep the baseline`() {
        effectiveOriginalFileContents(original, original, cursorAt(original, 3)) shouldBe original
    }

    @Test
    fun `insertion above cursor falls back to current contents`() {
        val current = "new line\n" + original
        effectiveOriginalFileContents(original, current, cursorAt(current, 4)) shouldBe current
    }

    @Test
    fun `deletion above cursor falls back to current contents`() {
        val current = original.removePrefix("line 1\n")
        effectiveOriginalFileContents(original, current, cursorAt(current, 2)) shouldBe current
    }

    @Test
    fun `edit above cursor without line change falls back to current contents`() {
        val current = original.replace("line 1", "LINE 1")
        effectiveOriginalFileContents(original, current, cursorAt(current, 3)) shouldBe current
    }

    @Test
    fun `edit below cursor keeps the baseline`() {
        val current = original.replace("line 5", "line 5 changed")
        effectiveOriginalFileContents(original, current, cursorAt(current, 2)) shouldBe original
    }

    @Test
    fun `edit on the cursor line keeps the baseline`() {
        val current = original.replace("line 3", "line 3 edited")
        // Cursor on line 3 (0-based line 2): lines above it are untouched.
        effectiveOriginalFileContents(original, current, cursorAt(current, 3)) shouldBe original
    }

    @Test
    fun `insertion below cursor keeps the baseline`() {
        val current = original.replace("line 5", "line 5\nline 6")
        effectiveOriginalFileContents(original, current, cursorAt(current, 2)) shouldBe original
    }

    @Test
    fun `empty baseline falls back to current contents`() {
        effectiveOriginalFileContents("", original, cursorAt(original, 2)) shouldBe original
    }

    @Test
    fun `cursor position out of bounds is clamped`() {
        val current = "new line\n" + original
        // Clamped to the end of the document: cursor sits on the last line, above it the
        // inserted line shifted everything, so the current contents must be resent.
        effectiveOriginalFileContents(original, current, Int.MAX_VALUE) shouldBe current
        // Clamped to the start of the document: there are no lines above the cursor, so the
        // baseline is still line-synced and is kept.
        effectiveOriginalFileContents(original, current, -1) shouldBe original
    }
}
