package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteRequest
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteResponse
import dev.sweep.assistant.utils.CompressionUtils
import dev.sweep.assistant.utils.defaultJson
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Client for the local `sweep-autocomplete` server. The cloud branch has been removed:
 * every request goes to `127.0.0.1:<autocompleteLocalPort>` managed by
 * [LocalAutocompleteServerManager].
 */
@Service(Service.Level.PROJECT)
class AutocompleteIpResolverService(
    @Suppress("UNUSED_PARAMETER") private val project: Project,
) : Disposable {
    companion object {
        private val logger = Logger.getInstance(AutocompleteIpResolverService::class.java)

        fun getInstance(project: Project): AutocompleteIpResolverService =
            project.getService(AutocompleteIpResolverService::class.java)

        private const val READ_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build()

    fun getSharedHttpClient(): HttpClient = httpClient

    fun getBaseUrl(): String = LocalAutocompleteServerManager.getInstance().getServerUrl()

    fun getLastLatencyMs(): Long = -1L

    fun updateLastUserActionTimestamp() {
        // no-op (no remote DNS / health-check loop in local-only mode)
    }

    @RequiresBackgroundThread
    suspend fun fetchNextEditAutocomplete(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? {
        return try {
            LocalAutocompleteServerManager.getInstance().ensureServerRunning()

            val postData = defaultJson.encodeToString(NextEditAutocompleteRequest.serializer(), request)
            val postDataBytes = postData.toByteArray(Charsets.UTF_8)

            val (finalData, useCompression) =
                if (CompressionUtils.isBrotliAvailable()) {
                    val compressed = CompressionUtils.compress(postDataBytes, CompressionUtils.CompressionType.BROTLI)
                    if (compressed.size < postDataBytes.size) Pair(compressed, true) else Pair(postDataBytes, false)
                } else {
                    Pair(postDataBytes, false)
                }

            val builder =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("${getBaseUrl()}/backend/next_edit_autocomplete"))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .header("Content-Type", "application/json")

            if (useCompression) {
                builder.header("Content-Encoding", CompressionUtils.CompressionType.BROTLI.encoding)
            }

            val httpRequest = builder.POST(HttpRequest.BodyPublishers.ofByteArray(finalData)).build()

            val response =
                httpClient
                    .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .await()

            if (response.statusCode() !in 200..299) {
                LocalAutocompleteServerManager.getInstance().reportFailure()
                logger.warn("Local autocomplete server returned ${response.statusCode()}")
                return null
            }

            var result: NextEditAutocompleteResponse? = null
            try {
                response.body().bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        if (l.isBlank()) continue
                        try {
                            val element = defaultJson.parseToJsonElement(l)
                            if (element is JsonObject && element.containsKey("status")) {
                                val status = element["status"]?.jsonPrimitive?.contentOrNull
                                if (status == "error") {
                                    val errorMsg = element["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                                    logger.warn("Local autocomplete server error: $errorMsg")
                                    continue
                                }
                            }
                            result = defaultJson.decodeFromString(NextEditAutocompleteResponse.serializer(), l)
                        } catch (e: Exception) {
                            logger.warn("Error parsing local server response: ${e.message}")
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                logger.info("Local server stream closed: ${e.message}")
            }

            if (result != null) {
                LocalAutocompleteServerManager.getInstance().reportSuccess()
            } else {
                LocalAutocompleteServerManager.getInstance().reportFailure()
            }

            result
        } catch (e: Exception) {
            logger.warn("Error fetching next edit autocomplete: ${e.message}")
            LocalAutocompleteServerManager.getInstance().reportFailure()
            throw e
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
