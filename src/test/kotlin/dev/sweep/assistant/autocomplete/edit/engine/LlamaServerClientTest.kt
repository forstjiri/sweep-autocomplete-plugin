package dev.sweep.assistant.autocomplete.edit.engine

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Cancellation semantics of the SSE client: a newer request must interrupt
 * EVERY older in-flight generation, not just the most recent one (the old
 * single-slot reference let zombie requests burn GPU slots for seconds).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LlamaServerClientTest {

    private lateinit var server: HttpServer
    private lateinit var client: LlamaServerClient

    private val outcomes = ConcurrentHashMap<String, String>()

    @BeforeEach
    fun startFakeSseServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/v1/completions") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            try {
                while (true) {
                    out.write("data: {\"choices\":[{\"text\":\"${"x".repeat(10)}\"}]}\n\n".toByteArray())
                    out.flush()
                    Thread.sleep(20)
                }
            } catch (_: IOException) {
                // client went away — stop streaming
            }
        }
        server.start()
        client = LlamaServerClient("http://127.0.0.1:${server.address.port}", timeoutMs = 10_000)
    }

    @AfterEach
    fun stopFakeSseServer() {
        server.stop(0)
    }

    private fun streamInBackground(name: String, maxOutputChars: Int): Thread {
        val result = ConcurrentLinkedQueue<String>()
        return thread(name = name, start = false) {
            try {
                val completion = client.generateCompletion(prompt = "p", maxOutputChars = maxOutputChars)
                result.add("ok:${completion.finishReason}")
            } catch (e: LlamaServerClient.RequestCancelledException) {
                result.add("cancelled")
            } catch (e: Throwable) {
                result.add("error:${e.javaClass.simpleName}")
            } finally {
                outcomes[name] = result.poll() ?: "none"
            }
        }
    }

    private fun assertFinishes(thread: Thread, name: String) {
        thread.join(5000)
        assertFalse(thread.isAlive, "$name should finish within 5s")
    }

    @Test
    fun `a newer request interrupts all older streaming requests`() {
        // Two long streams that would never finish on their own…
        val older1 = streamInBackground("older1", maxOutputChars = 1_000_000)
        val older2 = streamInBackground("older2", maxOutputChars = 1_000_000)
        older1.start()
        older2.start()
        Thread.sleep(150) // both are mid-stream now

        // …and a newer request that early-aborts on its own output limit.
        val newer = streamInBackground("newer", maxOutputChars = 200)
        newer.start()

        assertFinishes(newer, "newer")
        assertFinishes(older1, "older1")
        assertFinishes(older2, "older2")

        assertEquals("cancelled", outcomes["older1"])
        assertEquals("cancelled", outcomes["older2"])
        assertEquals("ok:length", outcomes["newer"])
    }

    @Test
    fun `a lone request streams until its output limit`() {
        val lone = streamInBackground("lone", maxOutputChars = 100)
        lone.start()
        assertFinishes(lone, "lone")
        assertEquals("ok:length", outcomes["lone"])
    }

    @Test
    fun `complete-window predicate stops streaming`() {
        val completion = client.generateCompletion(
            prompt = "p",
            maxOutputChars = 1_000_000,
            shouldStop = { it.length >= 30 },
        )

        assertEquals("sufficient", completion.finishReason)
        assertTrue(completion.text.length >= 30)
    }

    @Test
    fun `explicit cancellation interrupts active streaming requests`() {
        val older = streamInBackground("older", maxOutputChars = 1_000_000)
        older.start()
        Thread.sleep(150)

        client.cancelInFlightRequests()

        assertFinishes(older, "older")
        assertEquals("cancelled", outcomes["older"])
    }
    private fun silentServer(beforeHeaders: Boolean): java.util.concurrent.CountDownLatch {
        val arrived = java.util.concurrent.CountDownLatch(1)
        server.removeContext("/v1/completions")
        server.createContext("/v1/completions") { exchange ->
            arrived.countDown()
            try {
                if (beforeHeaders) Thread.sleep(2000)
                exchange.sendResponseHeaders(200, 0)
                if (!beforeHeaders) { exchange.responseBody.flush(); Thread.sleep(2000) }
            } catch (_: Exception) {
            } finally { exchange.close() }
        }
        return arrived
    }

    @Test
    fun `silent stream times out and discards partial output`() {
        silentServer(false)
        val start = System.nanoTime()
        org.junit.jupiter.api.Assertions.assertThrows(LlamaServerClient.RequestTimeoutException::class.java) {
            client.generateCompletion("silent", remainingMs = 150)
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500)
    }

    @Test
    fun `cancellation before headers releases caller`() {
        val arrived = silentServer(true)
        val pending = streamInBackground("headers", 10000)
        pending.start()
        assertTrue(arrived.await(2, java.util.concurrent.TimeUnit.SECONDS))
        client.cancelInFlightRequests()
        pending.join(1000)
        assertFalse(pending.isAlive)
        assertEquals("cancelled", outcomes["headers"])
    }

    @Test
    fun `cancellation closes a silent response stream`() {
        val arrived = silentServer(false)
        val pending = streamInBackground("silent", 10000)
        pending.start()
        assertTrue(arrived.await(2, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(100)
        client.cancelInFlightRequests()
        pending.join(1000)
        assertFalse(pending.isAlive)
        assertEquals("cancelled", outcomes["silent"])
    }

    @Test
    fun `http errors are inference errors rather than valid empty results`() {
        server.removeContext("/v1/completions")
        server.createContext("/v1/completions") { exchange ->
            exchange.sendResponseHeaders(503, 0)
            exchange.close()
        }
        org.junit.jupiter.api.Assertions.assertThrows(IOException::class.java) {
            client.generateCompletion("error")
        }
    }

}
