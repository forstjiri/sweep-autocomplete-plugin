package dev.sweep.assistant.autocomplete.edit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
    fun `diagnostic branch ranks before the fallback`() {
        val file = fileWithLines(40)
        val diagOffset = file.indexOf("val item35 = 35\n")
        val diagnostic =
            NesRetrieval.EditorDiagnosticData(
                line = 35,
                lineNumber = 34,
                startOffset = diagOffset,
                endOffset = diagOffset + 5,
                severity = "ERROR",
                message = "unresolved reference",
            )

        val candidates =
            NesRetrieval.findCandidateBlocks(
                file,
                "",
                cursorPosition = 0,
                editorDiagnostics = listOf(diagnostic),
            )

        assertTrue(candidates.size >= 2)
        assertEquals(diagOffset, candidates.first().blockStartOffset)
        assertEquals(diagnostic, candidates.first().diagnostic)
        assertTrue(candidates.all { it.codeBlock.isNotEmpty() })
    }

    @Test
    fun `candidates are deduped and capped at two`() {
        val file = fileWithLines(40)
        val diagOffset = file.indexOf("val item35 = 35\n")
        val diagnostic =
            NesRetrieval.EditorDiagnosticData(
                line = 35,
                lineNumber = 34,
                startOffset = diagOffset,
                endOffset = diagOffset + 5,
                severity = "ERROR",
                message = "unresolved reference",
            )

        val candidates =
            NesRetrieval.findCandidateBlocks(
                file,
                "",
                cursorPosition = 0,
                editorDiagnostics = listOf(diagnostic),
            )

        assertEquals(candidates.map { it.blockStartOffset }.distinct().size, candidates.map { it.blockStartOffset }.size)
        assertTrue(candidates.size <= 2)
    }

    @Test
    fun `best single match keeps legacy priority`() {
        val file = fileWithLines(40)
        val diagOffset = file.indexOf("val item35 = 35\n")
        val diagnostic =
            NesRetrieval.EditorDiagnosticData(
                line = 35,
                lineNumber = 34,
                startOffset = diagOffset,
                endOffset = diagOffset + 5,
                severity = "ERROR",
                message = "unresolved reference",
            )

        val best =
            NesRetrieval.findBestMatchingBlock(
                file,
                "",
                cursorPosition = 0,
                editorDiagnostics = listOf(diagnostic),
            )

        assertNotNull(best)
        assertEquals(diagOffset, best.blockStartOffset)
    }
}
