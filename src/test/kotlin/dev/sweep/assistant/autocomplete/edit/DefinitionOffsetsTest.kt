package dev.sweep.assistant.autocomplete.edit

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DefinitionOffsetsTest {
    @Test fun `current line resolves left backwards then right forwards before older lines`() {
        val text = "above\nfirst.second(third, fourth)\n"
        val cursor = text.indexOf("second") + 3
        assertEquals(listOf(text.indexOf("second") + 2, text.indexOf("first") + 4,
            cursor, text.indexOf("third"), text.indexOf("fourth"), 0),
            definitionOffsetsInPriorityOrder(text, cursor).toList())
    }

    @Test fun `previous line limit counts words not blank or punctuation-only lines`() {
        val text = (1..8).joinToString("") { "word$it\n \t{}\n" } + "\ncaret"
        val cursor = text.indexOf("caret")
        assertEquals(listOf(cursor) + (8 downTo 3).map { text.indexOf("word$it") },
            definitionOffsetsInPriorityOrder(text, cursor).toList())
    }

    @Test fun `file boundaries newline cursor and unicode whitespace keep valid offsets`() {
        assertEquals(emptyList<Int>(), definitionOffsetsInPriorityOrder("", 0).toList())
        assertEquals(listOf(4, 0, 2), definitionOffsetsInPriorityOrder("a b\nc\n", 6).toList())
        assertEquals(listOf(0, 2), definitionOffsetsInPriorityOrder("a b\nc\n", 0).toList())
        val text = "α\u2003β\tγ"
        assertEquals(listOf(0, 2, 4), definitionOffsetsInPriorityOrder(text, 0).toList())
        assertEquals(listOf(2, 0), definitionOffsetsInPriorityOrder("a b\nc", 3).toList())
    }

    @Test fun `lazy walk stops on cancellation and when enough definitions were collected`() {
        assertTrue(definitionOffsetsInPriorityOrder("alpha beta", 0) { true }.none())
        var cancelled = false
        val iterator = definitionOffsetsInPriorityOrder("alpha beta\nolder", 0) { cancelled }.iterator()
        assertEquals(0, iterator.next())
        cancelled = true
        assertFalse(iterator.hasNext())
        assertEquals(listOf(0), definitionOffsetsInPriorityOrder("alpha beta", 0).take(1).toList())
    }
}
