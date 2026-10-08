package dev.sweep.assistant.autocomplete.edit.engine

/** Ordered sources share one deadline; normalization happens before a result takes a slot. */
class NesContextCollector<T>(
    private val limit: Int,
    private val deadline: NesDeadline,
    private val cancelled: () -> Boolean,
    private val normalize: (T) -> T?,
    private val key: (T) -> Any,
) {
    val chunks = mutableListOf<T>()
    private val seen = mutableSetOf<Any>()
    fun collect(source: () -> List<T>) {
        if (cancelled() || deadline.expired() || chunks.size >= limit) return
        for (raw in source()) {
            if (cancelled() || deadline.expired()) break
            val chunk = normalize(raw) ?: continue
            if (seen.add(key(chunk))) chunks.add(chunk)
            if (chunks.size >= limit) break
        }
    }
}
