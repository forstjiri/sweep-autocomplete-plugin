package dev.sweep.assistant.autocomplete.edit.engine

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Opt-in engine replay: NES_REPLAY_LABEL and NES_REPLAY_ENDPOINT select an isolated server. */
class NesLatencyReplayTest {
    private data class Fixture(val name: String, val request: NextEditAutocompleteEngine.NesRequest, val expected: String)

    private fun getter(distant: Boolean = false): Fixture {
        val field = if (distant) "email" else "name"
        val original = "<?php\nclass Customer {\n    private int \$id;\n"
        val declaration = "    private string \$$field;\n"
        val tail = "    public function getId(): int {\n        return \$this->id;\n    }\n\n" +
            "    public function set${field.replaceFirstChar { it.uppercase() }}(string \$$field): void {\n        \$this->$field = \$$field;\n    }\n\n    \n}\n"
        val filler = if (distant) "    // filler preserving the distance to the declaration\n".repeat(500) else ""
        val current = original + declaration + filler + tail
        val before = original + filler + tail
        val cursor = current.lastIndexOf("    \n}") + 4
        return Fixture(if (distant) "distant-getter" else "getter",
            NextEditAutocompleteEngine.NesRequest("Customer.php", current, before,
                "File: Customer.php\n" + dev.sweep.assistant.autocomplete.edit.calculateDiff(before, current), cursor,
                recentUserActions = listOf(NextEditAutocompleteEngine.UserAction("INSERT_CHAR", 0, cursor - 1, "Customer.php"))),
            "get${field.replaceFirstChar { it.uppercase() }}")
    }

    private fun variable(postAccept: Boolean): Fixture {
        val simulator = NesLiveSimulationTest()
        val method = NesLiveSimulationTest::class.java.declaredMethods.single { it.name == "buildRequest" }
        method.isAccessible = true
        val request = method.invoke(simulator, null, emptyList<String>(), postAccept, false) as NextEditAutocompleteEngine.NesRequest
        return Fixture(if (postAccept) "variable-post-accept" else "variable-extraction", request, "productMax")
    }

    @Test fun `replay warm and cold automatic and manual requests`() {
        val label = System.getenv("NES_REPLAY_LABEL")
        assumeTrue(label != null, "Set NES_REPLAY_LABEL to opt into the latency replay")
        val endpoint = System.getenv("NES_REPLAY_ENDPOINT") ?: "http://127.0.0.1:18082"
        val engine = NextEditAutocompleteEngine(LlamaServerClient(endpoint))
        val fixtures = listOf(getter(), getter(true), variable(false), variable(true),
            getter().let { it.copy(name = "auxiliary-change", request = it.request.copy(
                fileChunks = listOf(NesPromptBuilder.FileChunkData("Other.php", "<?php class Other {}", 1, 1)))) })
        if (System.getenv("NES_REPLAY_COLD_ONLY") != "true") for (manual in listOf(false, true)) {
            for (fixture in fixtures) {
                // Prime each scenario independently; all six measured requests then share its prefix.
                val request = fixture.request.copy(steering = if (manual) "Provide a useful next edit." else null)
                engine.fetchNextEdits(request)
                repeat(6) { iteration ->
                    val fresh = request.copy(requestId = "$label-${fixture.name}-$manual-$iteration")
                    val started = System.nanoTime()
                    val result = engine.fetchNextEdits(fresh)
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    val rewritten = result.completions.sortedByDescending { it.startIndex }.fold(fresh.fileContents) { text, edit ->
                        text.replaceRange(edit.startIndex, edit.endIndex, edit.completion)
                    }
                    val valid = result.completions.isNotEmpty() && if (fixture.name.contains("variable")) {
                        result.completions.any { fixture.expected in it.completion }
                    } else {
                        Regex("function ${fixture.expected}\\s*\\(").findAll(rewritten).count() == 1 &&
                            Regex("function getId\\s*\\(").findAll(rewritten).count() == 1 &&
                            Regex("function set(Name|Email)\\s*\\(").findAll(rewritten).count() == 1 &&
                            rewritten.contains("return \$this->${if (fixture.name == "distant-getter") "email" else "name"}")
                    }
                    println("REPLAY " + Gson().toJson(mapOf("label" to label, "mode" to if (manual) "manual" else "automatic",
                        "scenario" to fixture.name, "iteration" to iteration, "elapsedMs" to elapsed,
                        "valid" to valid, "hunks" to result.completions.size, "requestId" to fresh.requestId)))
                }
            }
        }

        val proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        proxy.createContext("/v1/completions") { exchange ->
            val body = JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject
            body.addProperty("cache_prompt", false)
            val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("$endpoint/v1/completions"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { input ->
                exchange.responseHeaders.set("Connection", "close")
                exchange.sendResponseHeaders(response.statusCode(), 0)
                try {
                    exchange.responseBody.use { output ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            output.flush()
                        }
                    }
                } catch (_: java.io.IOException) { /* Engine deadline or complete-window stop. */ }
            }
        }
        proxy.start()
        try {
            for (manual in listOf(false, true)) {
                for ((iteration, fixture) in (fixtures + getter()).withIndex()) {
                    val fresh = fixture.request.copy(steering = if (manual) "Provide a useful next edit." else null,
                        requestId = "$label-cold-${fixture.name}-$manual-$iteration")
                    val started = System.nanoTime()
                    val response = NextEditAutocompleteEngine(LlamaServerClient("http://127.0.0.1:${proxy.address.port}")).fetchNextEdits(fresh)
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    println("REPLAY " + Gson().toJson(mapOf("label" to label, "cache" to "cold",
                        "mode" to if (manual) "manual" else "automatic", "scenario" to fixture.name,
                        "iteration" to iteration, "elapsedMs" to elapsed, "hunks" to response.completions.size,
                        "valid" to response.completions.any { fixture.expected in it.completion }, "requestId" to fresh.requestId)))
                }
            }
        } finally { proxy.stop(0) }
    }
}
