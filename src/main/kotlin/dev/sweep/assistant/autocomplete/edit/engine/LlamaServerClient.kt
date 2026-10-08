package dev.sweep.assistant.autocomplete.edit.engine

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.InputStream

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

    /**
     * Every in-flight generation keyed by its request id. A new request
     * cancels ALL older ones — a single-slot reference used to leave older
     * requests streaming for seconds on the shared GPU slots.
     */
    private class Operation {
        @Volatile var future: CompletableFuture<HttpResponse<InputStream>>? = null
        @Volatile var stream: InputStream? = null
        @Volatile var cancelled = false
        @Volatile var timedOut = false
        @Synchronized fun stop(timeout: Boolean = false) {
            if (timeout) timedOut = true else cancelled = true
            future?.cancel(true)
            runCatching { stream?.close() }
        }
        @Synchronized fun register(body: InputStream) {
            stream = body
            if (cancelled || timedOut) body.close()
        }
    }
    private val operations = ConcurrentHashMap<Long, Operation>()
    companion object {
        private val deadlines = Executors.newScheduledThreadPool(1) { runnable ->
            Thread(runnable, "nes-http-deadline").apply { isDaemon = true }
        }
    }

    data class CompletionResult(
        val text: String,
        val elapsedMs: Long,
        val finishReason: String?,
        val firstTextMs: Long? = null,
        val serverTiming: String = "unavailable",
        val serverMetrics: String = "unavailable",
    )

    class RequestTimeoutException : Exception("Inference deadline exceeded")

    class RequestCancelledException : Exception("Request cancelled by newer request")

    /** Cancel every HTTP operation and close its stream before new context collection. */
    fun cancelInFlightRequests() {
        val cancellationId = requestCounter.incrementAndGet()
        cancelRequestsBefore(cancellationId)
    }

    private fun cancelRequestsBefore(requestId: Long) {
        operations.forEach { (id, operation) -> if (id < requestId) operation.stop() }
    }

    /**
     * Generate a completion from llama-server using SSE streaming.
     *
     * Streams tokens and aborts early if:
     * - The output exceeds maxOutputChars (estimated from code block size)
     * - A stop token is detected in the accumulated text
     * - A newer request has been enqueued or the attempt deadline expires
     *
     * @param maxOutputChars Abort if accumulated text exceeds this length.
     *   Set to 0 to disable early abort (use max_tokens only).
     * @param shouldStop Abort after receiving a complete, actionable rewrite.
     */
    fun generateCompletion(
        prompt: String,
        stop: List<String> = NesConstants.STOP_TOKENS,
        maxTokens: Int = NesConstants.AUTOCOMPLETE_OUTPUT_MAX_TOKENS,
        temperature: Float = 0.0f,
        maxOutputChars: Int = 0,
        shouldStop: (String) -> Boolean = { false },
        remainingMs: Long = timeoutMs,
    ): CompletionResult {
        val myId = requestCounter.incrementAndGet()
        val operation = Operation()
        operations[myId] = operation
        val attemptMs = minOf(timeoutMs, remainingMs)
        if (attemptMs <= 0) { operations.remove(myId); throw RequestTimeoutException() }
        val alarm = deadlines.schedule({ operation.stop(timeout = true) }, attemptMs, TimeUnit.MILLISECONDS)
        fun checkActive() {
            if (operation.cancelled || myId != requestCounter.get()) throw RequestCancelledException()
            if (operation.timedOut) throw RequestTimeoutException()
        }
        try {
            cancelRequestsBefore(myId)

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
                .timeout(Duration.ofMillis(attemptMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build()

            val start = System.nanoTime()

            val response = try {
                synchronized(operation) {
                    checkActive()
                    operation.future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    // Register even if cancellation wins the race with future.get().
                    operation.future!!.thenAccept { operation.register(it.body()) }
                }
                operation.future!!.get(attemptMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                checkActive()
                if (e is java.util.concurrent.TimeoutException || e.cause is java.net.http.HttpTimeoutException) throw RequestTimeoutException()
                throw e
            }
            operation.register(response.body())
            checkActive()
            if (response.statusCode() != 200) {
                throw java.io.IOException("llama-server HTTP ${response.statusCode()}")
            }

            // Stream SSE events, accumulate text, abort early if needed
            val accumulated = StringBuilder()
            var finishReason: String? = null
            var abortedEarly = false
            var firstTextMs: Long? = null
            var complete = false
            var serverMetrics = "unavailable"

            try {
                BufferedReader(InputStreamReader(response.body())).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        // A newer request superseded this one — stop streaming now
                        checkActive()
                        val l = line ?: continue
                        if (!l.startsWith("data: ")) continue
                        val data = l.removePrefix("data: ").trim()
                        if (data == "[DONE]") { complete = true; break }

                        try {
                            val event = JsonParser.parseString(data).asJsonObject
                            event.get("timings")?.let { serverMetrics = it.toString() }
                            val choices = event.getAsJsonArray("choices")
                            if (choices != null && choices.size() > 0) {
                                val choice = choices[0].asJsonObject
                                val text = choice.get("text")?.asString ?: ""
                                if (text.isNotEmpty() && firstTextMs == null) firstTextMs = (System.nanoTime() - start) / 1_000_000
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
                        if (shouldStop(accumulated.toString())) {
                            logger.info("Early abort: completion contains a complete changed window")
                            abortedEarly = true
                            finishReason = "sufficient"
                            break
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                if (Thread.interrupted() || myId != requestCounter.get()) {
                    throw RequestCancelledException()
                }
                checkActive()
                throw e
            }

            checkActive()
            if (!complete && !abortedEarly && finishReason == null) throw java.io.IOException("Incomplete SSE response")
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val text = accumulated.toString()

            logger.info("llama-server completion: ${text.length} chars, ${elapsedMs}ms, finish=$finishReason${if (abortedEarly) " (early abort)" else ""}")

            return CompletionResult(text, elapsedMs, finishReason, firstTextMs, response.headers().firstValue("server-timing").orElse("unavailable"), serverMetrics)
        } finally {
            alarm.cancel(false)
            runCatching { operation.stream?.close() }
            operation.future?.cancel(true)
            operations.remove(myId, operation)
        }
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
