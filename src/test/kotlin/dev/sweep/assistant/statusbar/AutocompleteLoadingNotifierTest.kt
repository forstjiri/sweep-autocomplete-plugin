package dev.sweep.assistant.statusbar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutocompleteLoadingNotifierTest {
    @Test
    fun `overlapping requests keep loading until last settles`() {
        assertFalse(AutocompleteLoadingNotifier.isLoading)

        AutocompleteLoadingNotifier.begin()
        assertTrue(AutocompleteLoadingNotifier.isLoading)

        // a second (stale) request arrives before the first settles
        AutocompleteLoadingNotifier.begin()
        assertTrue(AutocompleteLoadingNotifier.isLoading)

        AutocompleteLoadingNotifier.end()
        assertTrue(AutocompleteLoadingNotifier.isLoading)

        AutocompleteLoadingNotifier.end()
        assertFalse(AutocompleteLoadingNotifier.isLoading)
    }

    @Test
    fun `extra end calls cannot break the counter`() {
        AutocompleteLoadingNotifier.end()
        AutocompleteLoadingNotifier.end()
        assertFalse(AutocompleteLoadingNotifier.isLoading)

        AutocompleteLoadingNotifier.begin()
        assertTrue(AutocompleteLoadingNotifier.isLoading)
        AutocompleteLoadingNotifier.end()
        assertFalse(AutocompleteLoadingNotifier.isLoading)
    }

    @Test
    fun `listeners are notified on flips and can be removed`() {
        val initial = AutocompleteLoadingNotifier.isLoading
        val events = mutableListOf<Boolean>()
        val remove = AutocompleteLoadingNotifier.addListener { events.add(it) }

        AutocompleteLoadingNotifier.begin()
        AutocompleteLoadingNotifier.end()
        remove()
        AutocompleteLoadingNotifier.begin()
        AutocompleteLoadingNotifier.end()

        assertEquals(listOf(initial, true, false), events)
    }
}
