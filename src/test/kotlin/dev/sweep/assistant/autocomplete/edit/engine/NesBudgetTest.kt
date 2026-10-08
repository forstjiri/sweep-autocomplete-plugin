package dev.sweep.assistant.autocomplete.edit.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NesBudgetTest {
    private fun prompt(source: String, cursor: Int = source.length, policy: NesRequestPolicy = NesRequestPolicy.AUTOMATIC,
                       path: String = "Example.php", chunks: List<NesPromptBuilder.FileChunkData> = emptyList(), steering: String? = null): NesPromptBuilder.PromptBuildResult {
        val block = NesPromptBuilder.getBlockAtCursor(source, cursor)
        return NesPromptBuilder.buildPrompt(path, source, source, "", cursor, block.codeBlock, block.blockStartIndex,
            fileChunks = chunks, steering = steering, policy = policy)
    }

    @Test fun `both modes budget complete serialized prompts including unicode and paths`() {
        val source = "<?php\nclass Example {\n" + (1..2000).joinToString("\n") { "    private string \$value$it; // český 日本語" } + "\n}\n"
        for (policy in listOf(NesRequestPolicy.AUTOMATIC, NesRequestPolicy.MANUAL)) {
            val result = prompt(source, policy = policy, path = "nested/".repeat(50) + "Example.php", steering = "Suggest a useful edit.")
            assertTrue(result.formattedPrompt.isNotEmpty())
            assertTrue(result.estimatedTokens <= policy.inputTokens)
            assertEquals("window_outline", result.contextSelection)
            assertTrue(result.formattedPrompt.endsWith("</steering>"))
        }
    }

    @Test fun `mandatory overflow skips request`() {
        val result = prompt("<?php\nclass X {}", path = "x".repeat(15000))
        assertEquals("", result.formattedPrompt)
        assertEquals("mandatory_overflow", result.skipReason)
    }

    @Test fun `auxiliary changes preserve primary prefix`() {
        val source = "<?php\nclass X { private string \$name; }\n"
        val a = prompt(source, chunks = listOf(NesPromptBuilder.FileChunkData("a.php", "one", 1, 1)))
        val b = prompt(source, chunks = listOf(NesPromptBuilder.FileChunkData("b.php", "two", 1, 1)))
        val prefix = "<|file_sep|>Example.php\n$source"
        assertTrue(a.formattedPrompt.startsWith(prefix))
        assertTrue(b.formattedPrompt.startsWith(prefix))
    }

    @Test fun `small files with many lines remain full files`() {
        val result = prompt("\n".repeat(1000) + "<?php\n")
        assertEquals("full", result.contextSelection)
    }

    @Test fun `distant properties and existing accessors survive windowing`() {
        val source = "<?php\nclass Example {\n private string \$name;\n public function getName(): string { return \$this->name; }\n" +
            " // filler with no declaration\n".repeat(1500) + " public function work() {\n  \$this->\n }\n}\n"
        val result = prompt(source, source.indexOf("  \$this->") + 9)
        assertTrue(result.formattedPrompt.contains("private string \$name;"))
        assertTrue(result.formattedPrompt.contains("public function getName(): string"))
        assertFalse(result.formattedPrompt.contains("return \$this->name"))
    }

    @Test fun `fallback handles promoted and multiline declarations without bodies or fake comments`() {
        val source = """<?php
class Example extends Base {
    // private string ${'$'}fake;
    private ?Foo
        ${'$'}real;
    public function __construct(
        private readonly Foo ${'$'}promoted,
    ) { echo "public function fake() {}"; }
    public function getReal(
    ): ?Foo { return ${'$'}this->real; }
}
"""
        val outline = NesStructuralOutline.extract(source, source.indexOf("getReal")).joinToString("\n") { it.text }
        assertTrue(outline.contains("${'$'}real;"))
        assertTrue(outline.contains("private readonly Foo ${'$'}promoted"))
        assertTrue(outline.contains("getReal("))
        assertFalse(outline.contains("fake"))
        assertFalse(outline.contains("return"))
        assertFalse(outline.contains("echo"))
    }

    @Test fun `cancelled extraction produces no partial declarations`() {
        assertTrue(NesStructuralOutline.extract("<?php class A { private string \$x; }", 0) { true }.isEmpty())
    }

    @Test fun `manual classification excludes generated steering`() {
        assertEquals(NesRequestPolicy.MANUAL, NesRequestPolicy.forRequest(null, listOf("old")))
        assertEquals(NesRequestPolicy.MANUAL, NesRequestPolicy.forRequest("next", emptyList()))
        assertEquals(NesRequestPolicy.AUTOMATIC, NesRequestPolicy.forRequest(null, emptyList()))
    }
}
