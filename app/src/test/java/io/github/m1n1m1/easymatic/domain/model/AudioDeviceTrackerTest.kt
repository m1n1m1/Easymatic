package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.core.service.AudioDeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDeviceTrackerTest {
    private val headphones = AudioOutput(1, AudioDeviceType.BLUETOOTH, "Headphones")
    private val dock = AudioOutput(2, AudioDeviceType.DOCK, "Desk")

    @Test
    fun `startup inventory is silent but its later removal is reported`() {
        val tracker = AudioDeviceTracker()
        assertTrue(tracker.added(listOf(headphones)).isEmpty())
        assertEquals(listOf(headphones), tracker.removed(listOf(headphones.id)))
        assertTrue(tracker.removed(listOf(headphones.id)).isEmpty())
    }

    @Test
    fun `empty startup does not swallow the first real connection`() {
        val tracker = AudioDeviceTracker()
        assertTrue(tracker.added(emptyList()).isEmpty())
        assertEquals(listOf(headphones), tracker.added(listOf(headphones)))
        assertTrue(tracker.added(listOf(headphones)).isEmpty())
        assertEquals(listOf(dock), tracker.added(listOf(headphones, dock)))
        assertEquals(listOf(headphones), tracker.removed(listOf(headphones.id)))
        assertEquals(listOf(headphones), tracker.added(listOf(headphones)))
        assertEquals(listOf(dock), tracker.removed(listOf(dock.id)))
    }
}
