package dev.sweep.assistant.autocomplete.edit.engine

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class NesTypingPrefillTest {
    private val source = "fun example() {\n    val greeting = \"český 日本語\"\n    println(greet)\n}\n"
    private val cursor = source.indexOf("greet)") + "greet".length

    private fun prompt(text: String = source, position: Int = cursor, steering: String? = null, path: String = "Example.kt") =
        NesPromptBuilder.buildPrompt(path, text, text, "", position, text, 0,
            steering = steering, prefillTyping = true)

    @Test fun `prefill is final prompt text after steering and reconstructs exact unicode prefix`() {
        val result = prompt(steering = "Complete the expression.")
        assertTrue(result.prefill.isNotEmpty())
        assertTrue(result.formattedPrompt.endsWith(result.prefill))
        assertTrue(result.formattedPrompt.indexOf("</steering>") < result.formattedPrompt.lastIndexOf(result.prefill))
        assertEquals(source.take(cursor), result.prefill + result.forcedPrefix)
        assertTrue(result.estimatedTokens <= NesRequestPolicy.AUTOMATIC.inputTokens)
    }

    @Test fun `EOF typing and empty prefix are supported`() {
        val eof = prompt(position = source.length)
        assertEquals(source, eof.prefill + eof.forcedPrefix)
        assertTrue(eof.prefill.isNotEmpty())
        assertEquals("", prompt(position = 0).prefill)
        assertEquals("", prompt("", 0).prefill)
    }

    @Test fun `mandatory budget includes prefill and skips overflow`() {
        val result = prompt(path = "x".repeat(15000))
        assertEquals("mandatory_overflow", result.skipReason)
        assertEquals("", result.formattedPrompt)
    }

    private fun run(action: String? = "INSERT_CHAR", steering: String? = null, avoid: List<String> = emptyList(),
                    automaticSteering: Boolean = false, invalidPrefix: Boolean = false, unchanged: Boolean = false): Pair<List<String>, NextEditAutocompleteEngine.NesResponse> {
        val prompts = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/completions") { exchange ->
            val request = JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject
            val p = request["prompt"].asString
            prompts.add(p)
            val expected = prompt(steering = steering).prefill
            val tail = p.substringAfter("<|file_sep|>updated/").substringAfter('\n')
            val prefilled = expected.isNotEmpty() && tail.endsWith(expected)
            val rewritten = if (unchanged) source else source.replace("println(greet)", "println(greeting)")
            val text = if (invalidPrefix) "<|bad|>" else if (prefilled) rewritten.removePrefix(expected) else rewritten
            val event = Gson().toJson(mapOf("choices" to listOf(mapOf("text" to text, "finish_reason" to "stop"))))
            val data = "data: $event\n\ndata: [DONE]\n\n".toByteArray()
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.use { it.write(data) }
        }
        server.start()
        try {
            val request = NextEditAutocompleteEngine.NesRequest("Example.kt", source, source, "", cursor,
                recentUserActions = action?.let { listOf(NextEditAutocompleteEngine.UserAction(it, 2, cursor - 1, "Example.kt")) } ?: emptyList(),
                steering = if (automaticSteering) null else steering, automaticSteering = if (automaticSteering) steering else null, avoidCompletions = avoid)
            return prompts to NextEditAutocompleteEngine(LlamaServerClient("http://127.0.0.1:${server.address.port}")).fetchNextEdits(request)
        } finally { server.stop(0) }
    }

    @Test fun `typing continuation yields a usable mid-line edit without changing text before caret`() {
        val (prompts, response) = run()
        assertEquals(1, prompts.size)
        assertTrue(prompts.single().endsWith(prompt().prefill))
        assertTrue(response.completions.isNotEmpty())
        assertTrue(response.completions.all { it.startIndex >= cursor })
        assertTrue(response.completions.any { "ing" in it.completion })
    }

    @Test fun `manual and other actions keep full rewrite format`() {
        for ((action, steering, avoid) in listOf(
            Triple("INSERT_CHAR", "next suggestion", emptyList()),
            Triple("INSERT_CHAR", null, listOf("old suggestion")),
            Triple("DELETE", null, emptyList()), Triple("PASTE", null, emptyList()),
            Triple(null, null, emptyList()), Triple("UNDO", null, emptyList()))) {
            val (prompts, response) = run(action, steering, avoid)
            assertTrue(prompts.isNotEmpty())
            assertFalse(prompts.first().endsWith(prompt().prefill))
            assertTrue(response.completions.isNotEmpty(), "action=$action steering=$steering")
        }
    }

    @Test fun `generated steering remains typing and invalid continuation does not retry`() {
        val (prompts, response) = run(steering = "Continue typing.", automaticSteering = true)
        assertTrue(prompts.single().endsWith(prompt(steering = "Continue typing.").prefill))
        assertTrue(response.completions.isNotEmpty())
        val (invalidPrompts, invalidResponse) = run(invalidPrefix = true)
        assertEquals(1, invalidPrompts.size)
        assertTrue(invalidResponse.completions.isEmpty())
    }

    @Test fun `unchanged continuation is filtered after reconstruction`() {
        val (prompts, response) = run(unchanged = true)
        assertEquals(1, prompts.size) // No alternate context exists for this fixture.
        assertTrue(response.completions.isEmpty())
    }

    @Test fun `complete changed window detection reconstructs prefix`() {
        val original = source + "// unchanged footer\n"
        val result = prompt(text = original)
        val changed = original.replace("println(greet)", "println(greeting)")
        val continuation = changed.removePrefix(result.prefill)
        assertTrue(NesCompletionParser.hasCompleteChangedWindow(result.prefill + continuation, original))
        assertFalse(NesCompletionParser.hasCompleteChangedWindow(result.prefill + original.removePrefix(result.prefill), original))
    }
    @Test fun `retrieval fallback respects the real typing caret while manual edits remain available`() {
        val original = (1..80).joinToString("") {
            if (it == 10 || it == 60) "val result = old(alpha, beta, gamma)\n" else "val item$it = $it\n"
        }
        val marker = original.lastIndexOf("val result = old")
        val file = original.replaceRange(marker, marker + "val result = old(alpha, beta, gamma)".length,
            "val result = normalized")
        val caret = file.indexOf("normalized") + "normalized".length
        val recent = "File: Example.kt\n" + dev.sweep.assistant.autocomplete.edit.calculateDiff(original, file)
        for (manual in listOf(false, true)) {
            var calls = 0
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/completions") { exchange ->
                val p = JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject["prompt"].asString
                calls++
                val block = p.substringAfter("<|file_sep|>current/").substringAfter('\n')
                    .substringBefore("\n<|file_sep|>updated/").replace("<|cursor|>", "")
                val text = if (calls == 1) "" else block.replace("val item12 = 12", "val item12 = 99")
                val event = Gson().toJson(mapOf("choices" to listOf(mapOf("text" to text, "finish_reason" to "stop"))))
                val data = "data: $event\n\ndata: [DONE]\n\n".toByteArray()
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            }
            server.start()
            try {
                val request = NextEditAutocompleteEngine.NesRequest("Example.kt", file, original, recent, caret,
                    recentUserActions = listOf(NextEditAutocompleteEngine.UserAction("INSERT_CHAR", 59, caret - 1, "Example.kt")),
                    steering = if (manual) "next suggestion" else null)
                val response = NextEditAutocompleteEngine(LlamaServerClient("http://127.0.0.1:${server.address.port}")).fetchNextEdits(request)
                assertEquals(2, calls)
                if (manual) {
                    assertTrue(response.completions.isNotEmpty())
                    assertTrue(response.completions.any { it.startIndex < caret })
                } else assertTrue(response.completions.isEmpty())
            } finally { server.stop(0) }
        }
    }

}
