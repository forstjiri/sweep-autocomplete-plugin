package dev.sweep.assistant.autocomplete.edit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tests for the ranked retrieval candidates that feed the steering matrix's
 * context variants (V2/V3).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NesRetrievalTest {

    private fun fileWithLines(count: Int): String =
        (1..count).joinToString("") { "val item$it = $it\n" }

    @Test
    fun `fallback block after cursor when nothing matches`() {
        val file = fileWithLines(40)

        val candidates = NesRetrieval.findCandidateBlocks(file, "", cursorPosition = 0)

        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.isBlockAfterCursor })
        // the fallback block skips blockSize lines past the cursor line
        assertEquals(file.indexOf("val item8 = 8\n"), candidates.first().blockStartOffset)
    }

    @Test
    fun `candidates are deduped and capped at two`() {
        val file = fileWithLines(40)
        val candidates = NesRetrieval.findCandidateBlocks(file, "", cursorPosition = 0)

        assertEquals(candidates.map { it.blockStartOffset }.distinct().size, candidates.map { it.blockStartOffset }.size)
        assertTrue(candidates.size <= 2)
    }

    @Test
    fun `best single match uses the fallback when nothing matches`() {
        val file = fileWithLines(40)
        val best = NesRetrieval.findBestMatchingBlock(file, "", cursorPosition = 0)

        assertTrue(best.isBlockAfterCursor)
        assertEquals(file.indexOf("val item8 = 8\n"), best.blockStartOffset)
    }
}
