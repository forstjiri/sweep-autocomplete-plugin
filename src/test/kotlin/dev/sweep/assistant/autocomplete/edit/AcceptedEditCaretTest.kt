package dev.sweep.assistant.autocomplete.edit

import dev.sweep.assistant.utils.DiffGroup
import dev.sweep.assistant.utils.computeDiffGroups
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AcceptedEditCaretTest {
    private fun caret(start: Int, before: String, after: String): Int {
        val hunks = computeDiffGroups(before, after)
        return caretOffsetAfterAcceptedEdit(start, hunks,
            hunks.sumOf { it.additions.length - it.deletions.length }, start + after.length)
    }

    @Test fun `PHP constructor then assignment leaves caret after semicolon before unchanged brace`() {
        val constructor = "public function __construct(bool \$enabled) {"
        val updated = "public function __construct(bool \$enabled, bool \$isDebug) {"
        assertEquals(updated.indexOf(") {"), caret(0, constructor, updated))
        val assignment = "\t\t\$this->isDebug = \$isDebug;"
        val replacement = "$assignment\n\t}"
        assertEquals(263, caret(235, "\t}", replacement))
        assertEquals(assignment.length, caret(0, "\t}", replacement))
        assertEquals("\t}", replacement.substring(assignment.length + 1))
    }

    @Test fun `last changed hunk accounts for earlier deltas and ignores trailing whitespace`() {
        val hunks = listOf(DiffGroup("a", "long", 0), DiffGroup("x", "done;\t\n\t", 10))
        assertEquals(28, caretOffsetAfterAcceptedEdit(10, hunks, 10, 100))
    }

    @Test fun `deletions and blank additions preserve existing offsets`() {
        assertEquals(12, caretOffsetAfterAcceptedEdit(10, listOf(DiffGroup("gone", "", 2)), -4, 100))
        assertEquals(15, caretOffsetAfterAcceptedEdit(10, listOf(DiffGroup("", "\n\t ", 2)), 3, 100))
        assertEquals(10, caretOffsetAfterAcceptedEdit(10, emptyList(), 0, 100))
    }

    @Test fun `import fixes retain their caret policy`() {
        assertEquals(10, caretOffsetAfterAcceptedEdit(10, listOf(DiffGroup("", "code;\n", 0)),
            0, 100, trimTrailingWhitespace = false))
    }

    @Test fun `caret is bounded by document length`() {
        assertEquals(0, caretOffsetAfterAcceptedEdit(-10, emptyList(), 0, 100))
        assertEquals(5, caretOffsetAfterAcceptedEdit(10, emptyList(), 0, 5))
    }
}
