package dev.sweep.assistant.autocomplete.edit.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NesContextCollectorTest {
    @Test fun `automatic stops after first eligible source and unusable sources do not take slots`() {
        val collector = NesContextCollector(1, NesDeadline.after(75), { false },
            { value: String -> value.takeIf { it != "current" && it.isNotBlank() } }, { it })
        collector.collect { listOf("current", "") }
        collector.collect { listOf("external", "external") }
        collector.collect { throw AssertionError("usages must not run") }
        assertEquals(listOf("external"), collector.chunks)
    }
    @Test fun `manual deduplicates across all sources`() {
        val collector = NesContextCollector(3, NesDeadline.after(600), { false }, { value: String -> value }, { it })
        collector.collect { listOf("a") }
        collector.collect { listOf("a", "b") }
        collector.collect { listOf("c") }
        assertEquals(listOf("a", "b", "c"), collector.chunks)
    }
    @Test fun `expired deadline skips queued sources and late results`() {
        val collector = NesContextCollector(1, NesDeadline.after(0), { false }, { value: String -> value }, { it })
        collector.collect { throw AssertionError("expired source must not run") }
        assertTrue(collector.chunks.isEmpty())
        val late = NesContextCollector(1, NesDeadline.after(5), { false }, { value: String -> value }, { it })
        late.collect { Thread.sleep(10); listOf("late") }
        assertTrue(late.chunks.isEmpty())
    }
    @Test fun `cancellation skips remaining sources`() {
        var cancelled = false
        val collector = NesContextCollector(3, NesDeadline.after(600), { cancelled }, { value: String -> value }, { it })
        collector.collect { cancelled = true; listOf("late") }
        collector.collect { throw AssertionError("cancelled source must not run") }
        assertTrue(collector.chunks.isEmpty())
    }
}
