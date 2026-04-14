package dev.sweep.assistant.autocomplete.edit.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP client for llama-server's OpenAI-compatible /v1/completions endpoint.
 *
 * Uses SSE streaming to enable early abort when the completion exceeds
 * the expected code block size, saving GPU time on bad generations.
 */
class LlamaServerClient(
    private val baseUrl: String,
    private val timeoutMs: Long = 10_000,
) {
    private val logger = Logger.getInstance(LlamaServerClient::class.java)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(5000))
        .build()
    private val gson = Gson()
    private val requestCounter = AtomicLong(0)
    private val currentRequestThread = AtomicReference<Thread?>(null)

    data class CompletionResult(
        val text: String,
        val elapsedMs: Long,
        val finishReason: String?,
    )

    class RequestCancelledException : Exception("Request cancelled by newer request")

    /**
     * Generate a completion from llama-server using SSE streaming.
     *
     * Streams tokens and aborts early if:
     * - The output exceeds maxOutputChars (estimated from code block size)
     * - A stop token is detected in the accumulated text
     * - A newer request has been enqueued (thread interrupt)
     *
     * @param maxOutputChars Abort if accumulated text exceeds this length.
     *   Set to 0 to disable early abort (use max_tokens only).
     */
    fun generateCompletion(
        prompt: String,
        stop: List<String> = NesConstants.STOP_TOKENS,
        maxTokens: Int = NesConstants.AUTOCOMPLETE_OUTPUT_MAX_TOKENS,
        temperature: Float = 0.0f,
        maxOutputChars: Int = 0,
    ): CompletionResult {
        val myId = requestCounter.incrementAndGet()

        // Cancel any in-flight request by interrupting its thread
        currentRequestThread.getAndSet(Thread.currentThread())?.interrupt()

        if (myId != requestCounter.get()) {
            throw RequestCancelledException()
        }

        val requestBody = mapOf(
            "prompt" to prompt,
            "stop" to stop,
            "max_tokens" to maxTokens,
            "temperature" to temperature,
            "n_predict" to maxTokens,
            "stream" to true,
        )

        val json = gson.toJson(requestBody)
        val url = "$baseUrl/v1/completions"

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()

        val start = System.currentTimeMillis()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: java.io.IOException) {
            if (Thread.interrupted() || myId != requestCounter.get()) {
                throw RequestCancelledException()
            }
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RequestCancelledException()
        } finally {
            currentRequestThread.compareAndSet(Thread.currentThread(), null)
        }

        Thread.interrupted() // clear interrupt flag

        if (response.statusCode() != 200) {
            val body = response.body().bufferedReader().readText()
            logger.warn("llama-server returned ${response.statusCode()}: $body")
            return CompletionResult("", System.currentTimeMillis() - start, "error")
        }

        // Stream SSE events, accumulate text, abort early if needed
        val accumulated = StringBuilder()
        var finishReason: String? = null
        var abortedEarly = false

        try {
            BufferedReader(InputStreamReader(response.body())).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (!l.startsWith("data: ")) continue
                    val data = l.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    try {
                        val event = JsonParser.parseString(data).asJsonObject
                        val choices = event.getAsJsonArray("choices")
                        if (choices != null && choices.size() > 0) {
                            val choice = choices[0].asJsonObject
                            val text = choice.get("text")?.asString ?: ""
                            accumulated.append(text)
                            finishReason = choice.get("finish_reason")?.let {
                                if (it.isJsonNull) null else it.asString
                            }
                        }
                    } catch (_: Exception) {
                        // Skip malformed SSE events
                    }

                    // Early abort: output exceeds expected code block size
                    if (maxOutputChars > 0 && accumulated.length > maxOutputChars) {
                        logger.info("Early abort: output ${accumulated.length} chars > limit $maxOutputChars")
                        abortedEarly = true
                        finishReason = "length"
                        break
                    }
                }
            }
        } catch (e: java.io.IOException) {
            if (Thread.interrupted() || myId != requestCounter.get()) {
                throw RequestCancelledException()
            }
            // Stream closed — use whatever we accumulated
        }

        val elapsedMs = System.currentTimeMillis() - start
        val text = accumulated.toString()

        logger.info("llama-server completion: ${text.length} chars, ${elapsedMs}ms, finish=$finishReason${if (abortedEarly) " (early abort)" else ""}")

        return CompletionResult(text, elapsedMs, finishReason)
    }

    /** Health check — returns true if llama-server is reachable. */
    fun isHealthy(): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/health"))
                .timeout(Duration.ofMillis(3000))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (e: Exception) {
            false
        }
    }
}
