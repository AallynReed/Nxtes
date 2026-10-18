package net.mildhope.nxtes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingTest {
    /** Recorded for 10 s from 1000, paused, then 5 s more from 20000. */
    private val recording = Recording("r", "Talk", "blob", listOf(AudioSpan(1_000, 10_000), AudioSpan(20_000, 5_000)))

    @Test
    fun clockTimesMapIntoTheAudioAcrossPauses() {
        assertEquals(15_000L, recording.duration)
        assertEquals(500L, recording.offsetOf(1_500))
        assertEquals(12_000L, recording.offsetOf(22_000))
        assertNull(recording.offsetOf(15_000))
        assertNull(recording.offsetOf(500))
    }

    @Test
    fun audioOffsetsMapBackToTheClock() {
        assertEquals(1_500L, recording.timeAt(500))
        assertEquals(22_000L, recording.timeAt(12_000))
        assertEquals(25_000L, recording.timeAt(99_000))
    }
}
