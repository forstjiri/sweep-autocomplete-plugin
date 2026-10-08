package dev.sweep.assistant.autocomplete.edit.engine

import com.google.gson.JsonParser
import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class NesRetryTest {
    private fun run(response: String, budgetMs: Long = 3000, status: Int = 200, manual: Boolean = false): List<String> {
        val prompts = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/completions") { exchange ->
            val body = JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject
            val prompt = body["prompt"].asString
            prompts.add(prompt)
            val text = if (response == "unchanged") prompt.substringAfter("<|file_sep|>current/").substringAfter('\n')
                .substringBefore("\n<|file_sep|>updated/").replace("<|cursor|>", "") else response
            val event = Gson().toJson(mapOf("choices" to listOf(mapOf("text" to text, "finish_reason" to "stop"))))
            val data = "data: $event\n\ndata: [DONE]\n\n".toByteArray()
            exchange.sendResponseHeaders(status, data.size.toLong())
            exchange.responseBody.use { it.write(data) }
        }
        server.start()
        try {
            val file = (1..60).joinToString("") { "val item$it = $it\n" }
            val request = NextEditAutocompleteEngine.NesRequest("test.kt", file, file,
                "File: test.kt\n@@ -1,1 +1,1 @@\n-val old = 0\n+val item1 = 1\n", file.indexOf("val item5"),
                steering = if (manual) "next suggestion" else null,
                deadlineNanos = NesDeadline.after(budgetMs).expiresAtNanos)
            val engine = NextEditAutocompleteEngine(LlamaServerClient("http://127.0.0.1:${server.address.port}"))
            assertTrue(engine.fetchNextEdits(request).completions.isEmpty())
            return prompts.toList()
        } finally { server.stop(0) }
    }

    @Test fun `automatic empty completion gets at most one distinct fallback`() {
        val prompts = run("")
        assertEquals(2, prompts.size)
        assertNotEquals(prompts[0], prompts[1])
    }
    @Test fun `unchanged and no usable hunk results qualify for fallback`() {
        assertEquals(2, run("unchanged").size)
    }
    @Test fun `validation failures and http errors do not retry`() {
        assertEquals(1, run("<|invalid|>").size)
        assertEquals(1, run("", status = 503).size)
    }
    @Test fun `less than one second remaining prevents fallback`() {
        assertEquals(1, run("", budgetMs = 900).size)
    }
    @Test fun `manual requests retain bounded matrix`() {
        val prompts = run("", budgetMs = 20000, manual = true)
        assertTrue(prompts.size in 3..6, "attempts=${prompts.size}")
    }
}
