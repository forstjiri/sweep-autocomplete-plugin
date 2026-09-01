package dev.sweep.assistant.autocomplete.edit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tests for the steering section of the NES prompt: the `<steering>` tag must
 * be appended verbatim at the end of the prompt (after truncation) and never
 * appear for unsteered requests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NesPromptBuilderTest {

    private val defaultContents = """
        fun computeTotal(items: List<Item>): Int {
            var total = 0
            for (item in items) {
                total += item.price
            }
            return total
        }
    """.trimIndent()

    private fun buildPrompt(
        steering: String?,
        fileChunks: List<NesPromptBuilder.FileChunkData> = emptyList(),
        fileContents: String = defaultContents,
    ): NesPromptBuilder.PromptBuildResult =
        NesPromptBuilder.buildPrompt(
            filePath = "src/Main.kt",
            fileContents = fileContents,
            originalFileContents = fileContents,
            recentChanges = "",
            cursorPosition = fileContents.indexOf("total +="),
            codeBlock = fileContents,
            blockStartIndex = 0,
            fileChunks = fileChunks,
            steering = steering,
        )

    @Test
    fun `steering block is appended at the prompt end`() {
        val result = buildPrompt(steering = "Provide a different useful next edit.")

        assertTrue(
            result.formattedPrompt.endsWith("\n<steering>\nProvide a different useful next edit.\n</steering>"),
            "prompt tail: ...${result.formattedPrompt.takeLast(120)}",
        )
    }

    @Test
    fun `no steering block without steering`() {
        val result = buildPrompt(steering = null)

        assertFalse(result.formattedPrompt.contains("<steering>"))
        assertFalse(result.formattedPrompt.contains("</steering>"))
    }

    @Test
    fun `steering survives prompt truncation`() {
        // A file chunk large enough to push prompt + chunks over
        // CHARACTER_BOUND_TO_CHECK_TOKENIZATION forces the size-based rebuild;
        // the steering tag is appended afterwards and must survive it.
        val bigChunk =
            NesPromptBuilder.FileChunkData(
                filePath = "other/Big.kt",
                content = buildString {
                    repeat(1200) { i -> append("val other$i = $i\n") }
                },
                startLine = 1,
                endLine = 1200,
            )

        val result = buildPrompt(steering = "Try another location.", fileChunks = listOf(bigChunk))

        assertTrue(result.formattedPrompt.isNotEmpty())
        assertTrue(result.formattedPrompt.length + bigChunk.content.length > NesConstants.CHARACTER_BOUND_TO_CHECK_TOKENIZATION)
        assertTrue(result.formattedPrompt.contains("<steering>"))
        assertTrue(result.formattedPrompt.contains("Try another location."))
    }

    @Test
    fun `cursor block covers 10 lines above and below the cursor`() {
        // Sweep blog (oss-next-edit): the rewrite window is 10 lines above +
        // cursor + 10 lines below.
        val contents = (1..40).joinToString("") { "val item$it = $it\n" }
        val cursorAt15 = contents.indexOf("val item15")

        val block = NesPromptBuilder.getBlockAtCursor(contents, cursorAt15)

        assertTrue(block.codeBlock.startsWith("val item5 = 5\n"), block.codeBlock.take(40))
        assertTrue(block.codeBlock.contains("val item25 = 25\n"))
        assertFalse(block.codeBlock.contains("val item26 = 26\n"))
        assertEquals(contents.indexOf("val item5 = 5\n"), block.blockStartIndex)
    }
}
