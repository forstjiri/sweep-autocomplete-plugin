package dev.sweep.assistant.autocomplete.edit.engine

/** Internal budgets; all token counts are estimates, including serialized separators. */
data class NesRequestPolicy(
    val inputTokens: Int,
    val windowLines: Int,
    val outlineTokens: Int,
    val otherTokens: Int,
    val retrievalTokens: Int,
    val retrievalChunks: Int,
    val retrievalMs: Long,
    val deadlineMs: Long,
) {
    companion object {
        val AUTOMATIC = NesRequestPolicy(4096, 150, 768, 512, 512, 1, 75, 3000)
        val MANUAL = NesRequestPolicy(8192, 300, 1536, 1024, 2048, 3, 600, 20000)
        fun forRequest(steering: String?, avoided: List<String>) =
            if (steering != null || avoided.isNotEmpty()) MANUAL else AUTOMATIC
    }
}

class NesDeadline private constructor(val expiresAtNanos: Long) {
    fun remainingMs(): Long = ((expiresAtNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
    fun expired() = remainingMs() == 0L
    companion object {
        fun after(milliseconds: Long) = NesDeadline(System.nanoTime() + milliseconds * 1_000_000)
        fun at(nanos: Long) = NesDeadline(nanos)
    }
}
