package dev.sweep.assistant.autocomplete.edit.engine

import dev.sweep.assistant.autocomplete.edit.calculateDiff
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Live replay of the cartProductLimitation "extract variable" case against the
 * local llama-server on :18081. Skips automatically when the server is down.
 *
 * Scenario: the user just typed
 *   const productMax = limitation?.maximumAmountInCart ?? globalMaximumAmountInCart;
 * and the same expression appears again three lines below. The expected
 * OFFERED suggestion replaces the second occurrence with `productMax`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NesLiveSimulationTest {

    private val filePath = "src/module/model/facade/cartProductLimitation.facade.ts"
    private val expression = "limitation?.maximumAmountInCart ?? globalMaximumAmountInCart"
    private val typedLinePrefix = "const productMax = "

    private fun tab(n: Int) = "\t".repeat(n)

    private fun buildFile(withTypedLine: Boolean): String {
        val typedLine = tab(3) + typedLinePrefix + "$expression;\n"
        val filler1 = tab(3) + "const hasLimitation = limitation != null;\n"
        val filler2 = tab(3) + "const currentAmount = amount;\n"
        // Real shape from idea.log: the expression appears as the second
        // argument of Math.min inside a ternary below the typed line.
        val minLine = tab(4) + ": Math.min(globalMaximumAmountInCart, $expression);\n"
        return buildString {
            append("export default class CartProductLimitationFacade {\n")
            append(tab(1) + "async getEffectiveMaximumAmounts(products: CartProduct[]): Promise<Map<string, number>> {\n")
            append(tab(2) + "const limitationByProductId = await this.propertyFacade.getCartProductLimitations(\n")
            append(tab(3) + "products.map((product) => product.productId),\n")
            append(tab(2) + ").then((limitations) => new Map(limitations.map((limitation) => [limitation.productId, limitation])));\n")
            append(tab(2) + "const globalMaximumAmountInCart = this.configuration.get('cart.maximumAmountInCart');\n")
            append(tab(2) + "return products.reduce((result, {productId, amount}) => {\n")
            append(tab(3) + "const limitation = limitationByProductId.get(productId);\n")
            if (withTypedLine) append(typedLine)
            append(filler1)
            append(filler2)
            append(tab(3) + "return amount > 0\n")
            append(tab(4) + "? amount\n")
            append(minLine)
            append(tab(2) + "}, new Map());\n")
            append(tab(1) + "}\n")
            append("}\n")
        }
    }

    private val current = buildFile(withTypedLine = true)
    private val original = buildFile(withTypedLine = false)
    private val typedLineEnd =
        current.indexOf(typedLinePrefix + expression) + (typedLinePrefix + expression).length
    private val secondOccurrence = current.indexOf(expression, typedLineEnd)

    private fun healthCheck(): Boolean =
        try {
            LlamaServerClient("http://localhost:18081").isHealthy()
        } catch (t: Throwable) {
            println("SIM health check threw: $t")
            false
        }

    private fun buildRequest(
        steering: String?,
        avoid: List<String> = emptyList(),
        postAcceptBaseline: Boolean = false,
        noRecentChanges: Boolean = false,
    ): NextEditAutocompleteEngine.NesRequest {
        val recentChanges = if (noRecentChanges) "" else "File: $filePath\n" + calculateDiff(original, current)

        return NextEditAutocompleteEngine.NesRequest(
            filePath = filePath,
            fileContents = current,
            originalFileContents = if (postAcceptBaseline || noRecentChanges) current else original,
            recentChanges = recentChanges,
            cursorPosition = typedLineEnd,
            recentUserActions = listOf(
                NextEditAutocompleteEngine.UserAction("INSERT_CHAR", 10, typedLineEnd - 1, filePath),
            ),
            steering = steering,
            avoidCompletions = avoid,
        )
    }

    /** True when the hunk's replaced span covers the second occurrence of the expression. */
    private fun run(
        label: String,
        steering: String?,
        avoid: List<String> = emptyList(),
        postAcceptBaseline: Boolean = false,
        noRecentChanges: Boolean = false,
    ) {
        val engine = NextEditAutocompleteEngine(LlamaServerClient("http://localhost:18081"))
        val response = engine.fetchNextEdits(buildRequest(steering, avoid, postAcceptBaseline, noRecentChanges))
        val hunks = response.completions
        println("SIM [$label] completions=${hunks.size} elapsed=${response.elapsedMs}ms secondOccurrence=$secondOccurrence")
        hunks.forEachIndexed { i, c ->
            val spansSecond = c.startIndex <= secondOccurrence + expression.length &&
                c.endIndex >= secondOccurrence
            println(
                "SIM [$label]   [$i] start=${c.startIndex} end=${c.endIndex} spansSecond=$spansSecond " +
                    "text='${c.completion.replace("\n", "\\n").take(160)}'",
            )
        }
        val winner = hunks.firstOrNull {
            it.completion.contains("productMax") &&
                it.startIndex <= secondOccurrence + expression.length &&
                it.endIndex >= secondOccurrence &&
                // The canonical form replaces the expression itself — if the raw
                // expression is still in the completion, the model picked (or
                // kept) the wrong slot, e.g. Math.min(productMax, <expr>).
                !it.completion.contains(expression)
        }
        val primary = hunks.firstOrNull()
        println(
            "SIM [$label] primarySpansSecond=${primary != null && primary.completion.contains("productMax") && primary.startIndex <= secondOccurrence + expression.length && primary.endIndex >= secondOccurrence && !primary.completion.contains(expression)}",
        )
        assertTrue(
            winner != null,
            "[$label] expected a hunk replacing the second occurrence of the expression with productMax",
        )
    }

    @Test
    fun `auto greedy suggests replacing duplicate expression`() {
        assumeTrue(healthCheck(), "llama-server not running on :18081")
        run("auto", steering = null)
    }

    @Test
    fun `steered first press without misleading prompt`() {
        assumeTrue(healthCheck(), "llama-server not running on :18081")
        run("steered-first", steering = null)
    }

    @Test
    fun `post-accept baseline reset still yields suggestion when recent changes present`() {
        // acceptSuggestion() resets originalDocumentText to the post-accept text.
        // If the accepted line is recorded in recentEdits, the model must still
        // get the "what just happened" signal even though initial_file == current.
        assumeTrue(healthCheck(), "llama-server not running on :18081")
        run("post-accept-baseline", steering = null, postAcceptBaseline = true)
    }

    @Test
    fun `fresh session shortcut press synthesizes cursor-line signal`() {
        // The live failure: IDE restarted (no tracked edits, baseline == current),
        // the user places the cursor on a line and presses the next-suggestion
        // shortcut. The engine must synthesize the cursor-line anchor and still
        // offer the dedup suggestion.
        assumeTrue(healthCheck(), "llama-server not running on :18081")
        run(
            "fresh-session-shortcut",
            steering = "Provide two different alternative next edit suggestions. Do not repeat the previous suggestion.",
            noRecentChanges = true,
        )
    }

    @Test
    fun `steered retry away from previous suggestion`() {
        assumeTrue(healthCheck(), "llama-server not running on :18081")
        val noise = tab(4) + ": Math.min(productMax, $expression);\n"
        run(
            "steered-retry",
            steering = "Suggest a different useful next edit at the current cursor. Use a different implementation and do not repeat the previous suggestion.",
            avoid = listOf(noise),
        )
    }
}
