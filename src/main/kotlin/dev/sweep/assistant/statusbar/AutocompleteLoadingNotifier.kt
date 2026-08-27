package dev.sweep.assistant.statusbar

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared loading state for the status bar widget: tracks in-flight autocomplete
 * requests and notifies listeners when the loading state flips.
 *
 * Implemented as a process-wide counter so overlapping (stale) requests keep the
 * spinner visible until the last one settles.
 */
object AutocompleteLoadingNotifier {
    private val inFlight = AtomicInteger(0)

    @Volatile
    var isLoading: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** Registers a listener; returns a removal handle. */
    fun addListener(listener: (Boolean) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(isLoading)
        return { listeners.remove(listener) }
    }

    /** Called when an autocomplete request is sent to the server. */
    fun begin() {
        if (inFlight.incrementAndGet() == 1) {
            setLoading(true)
        }
    }

    /** Called when an autocomplete request settles (response, error, or cancellation). */
    fun end() {
        val remaining = inFlight.decrementAndGet()
        if (remaining <= 0) {
            inFlight.set(0)
            setLoading(false)
        }
    }

    private fun setLoading(loading: Boolean) {
        if (isLoading != loading) {
            isLoading = loading
            listeners.forEach { it(loading) }
        }
    }
}
