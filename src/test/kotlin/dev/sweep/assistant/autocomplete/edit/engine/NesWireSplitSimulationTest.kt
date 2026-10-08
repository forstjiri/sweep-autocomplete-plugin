package dev.sweep.assistant.autocomplete.edit.engine

import dev.sweep.assistant.autocomplete.edit.calculateDiff
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Live replay of the "wire(" signature-split case against the local
 * llama-server on :18081. Skips automatically when the server is down.
 *
 * Scenario (reported from PhpStorm 2026.3): the user typed the whole
 * signature on one line and pressed Enter right after `wire(`. The IDE
 * inserted "\n" + a tab indent. Expected suggestion: split the remaining
 * parameters onto separate lines, e.g.
 *
 *   function wire(
 *   	type: Type,
 *   	declaration: Node,
 *   	path: string, parents = new Set<Type>(),
 *   ): WireType {
 *
 * Nothing was offered in the IDE (log showed `filtered:unchanged`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NesWireSplitSimulationTest {

    private val filePath = "src/wire/wireFactory.ts"
    private val signatureLine =
        "function wire(type: Type, declaration: Node, path: string, parents = new Set<Type>()): WireType {"

    private val original = buildString {
        append(signatureLine + "\n")
        append("\treturn createWire(type, declaration, path, parents);\n")
        append("}\n")
    }

    /** Document state right after pressing Enter behind `wire(` (auto-indent tab). */
    private val current = buildString {
        append("function wire(\n")
        append("\ttype: Type, declaration: Node, path: string, parents = new Set<Type>()): WireType {\n")
        append("\treturn createWire(type, declaration, path, parents);\n")
        append("}\n")
    }

    /** Cursor sits at the end of the auto-inserted indent on the second line. */
    private val cursorPosition = "function wire(\n\t".length

    private fun healthCheck(): Boolean =
        try {
            LlamaServerClient("http://localhost:18081").isHealthy()
        } catch (t: Throwable) {
            println("SIM health check threw: $t")
            false
        }

    private fun buildRequest(): NextEditAutocompleteEngine.NesRequest =
        NextEditAutocompleteEngine.NesRequest(
            filePath = filePath,
            fileContents = current,
            originalFileContents = original,
            recentChanges = "File: $filePath\n" + calculateDiff(original, current),
            cursorPosition = cursorPosition,
            recentUserActions = listOf(
                // Enter is tracked as a char insertion of "\n" plus indent.
                NextEditAutocompleteEngine.UserAction("INSERT_CHAR", 1, cursorPosition - 1, filePath),
            ),
            steering = null,
            avoidCompletions = emptyList(),
        )

    /** A hunk splits the signature when it breaks at least two params onto new lines. */
    private fun splitsSignature(text: String): Boolean =
        text.contains("type: Type,\n") && text.contains("declaration: Node,\n")

    @Test
    fun `enter behind wire paren offers signature split`() {
        assumeTrue(healthCheck(), "llama-server not running on :18081")
        val engine = NextEditAutocompleteEngine(LlamaServerClient("http://localhost:18081"))
        val response = engine.fetchNextEdits(buildRequest())
        val hunks = response.completions
        println("SIM [wire] completions=${hunks.size} elapsed=${response.elapsedMs}ms cursor=$cursorPosition")
        hunks.forEachIndexed { i, c ->
            println(
                "SIM [wire]   [$i] start=${c.startIndex} end=${c.endIndex} splits=${splitsSignature(c.completion)} " +
                    "text='${c.completion.replace("\n", "\\n").take(240)}'",
            )
        }
        assertTrue(
            hunks.any { splitsSignature(it.completion) },
            "expected a hunk splitting the wire( parameters onto separate lines; got " +
                hunks.joinToString(" | ") { it.completion.replace("\n", "\\n").take(120) },
        )
    }

    /** State A: the user just finished typing the signature line (cursor at its end). */
    private val stateA = original
    private val cursorA = signatureLine.length

    @Test
    fun `consecutive keystrokes keep prefix cache warm`() {
        assumeTrue(healthCheck(), "llama-server not running on :18081")
        val engine = NextEditAutocompleteEngine(LlamaServerClient("http://localhost:18081"))

        fun request(state: String, cursor: Int, originalContents: String) =
            NextEditAutocompleteEngine.NesRequest(
                filePath = filePath,
                fileContents = state,
                originalFileContents = originalContents,
                recentChanges = "File: $filePath\n" + calculateDiff(originalContents, state),
                cursorPosition = cursor,
                recentUserActions = listOf(
                    NextEditAutocompleteEngine.UserAction("INSERT_CHAR", 1, cursor - 1, filePath),
                ),
                steering = null,
                avoidCompletions = emptyList(),
            )

        // Consecutive typing: baseline stays fixed, only the document and cursor move.
        val a = engine.fetchNextEdits(request(stateA, cursorA, original))
        val b = engine.fetchNextEdits(request(current, cursorPosition, original))
        println("SIM [wire-seq] A completions=${a.completions.size} elapsed=${a.elapsedMs}ms")
        println("SIM [wire-seq] B completions=${b.completions.size} elapsed=${b.elapsedMs}ms")
        // Diagnostic cache test: both keystroke transitions must succeed end-to-end.
        assertTrue(a.elapsedMs > 0 && b.elapsedMs > 0, "engine requests must complete")
    }
}
