package com.headphones.volume.core

import com.headphones.volume.core.EarbudVolumeDetector.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class EarbudVolumeDetectorTest {
    private var time = 0L
    private val gestures = mutableListOf<String>()
    private val restores = mutableListOf<Int>()
    private val detector = EarbudVolumeDetector(
        clock = { time },
        onGesture = { button, from, to -> gestures += "$button $from→$to" },
        restore = { restores += it },
    )

    @Test
    fun volumeUp_skipsForward_andRestoresVolume() {
        time = 10_000
        assertEquals(Outcome.GESTURE, detector.onVolumeChanged(prev = 8, new = 9, active = true))
        assertEquals(listOf("NEXT 8→9"), gestures)
        assertEquals(listOf(8), restores)
    }

    @Test
    fun volumeDown_skipsBack() {
        detector.onVolumeChanged(prev = 8, new = 7, active = true)
        assertEquals(listOf("PREVIOUS 8→7"), gestures)
        assertEquals(listOf(8), restores)
    }

    @Test
    fun holdBurst_skipsOnce_andKeepsRestoring() {
        detector.onVolumeChanged(8, 9, active = true)
        time += 400
        detector.onVolumeChanged(8, 9, active = true) // навушники крокують знову після нашого повернення
        time += 400
        assertEquals(Outcome.CONTINUED, detector.onVolumeChanged(9, 10, active = true))

        assertEquals(listOf("NEXT 8→9"), gestures)
        assertEquals(listOf(8, 8, 8), restores)
    }

    @Test
    fun newHoldAfterPause_skipsAgain() {
        detector.onVolumeChanged(8, 9, active = true)
        time += EarbudVolumeDetector.BURST_GAP_MS + 1
        detector.onVolumeChanged(8, 9, active = true)

        assertEquals(listOf("NEXT 8→9", "NEXT 8→9"), gestures)
    }

    @Test
    fun ownChange_isNotGesture() {
        detector.expectOwnChange(9)
        time += 100
        assertEquals(Outcome.OWN, detector.onVolumeChanged(8, 9, active = true))
        assertEquals(emptyList<String>(), gestures)
    }

    @Test
    fun restoreEcho_isRecognisedAsOwn() {
        val echoing = EarbudVolumeDetector(
            clock = { time },
            onGesture = { _, _, _ -> },
            restore = { restores += it },
        )
        // Повернення гучності відлунює окремою подією; застосунок позначає його як власне.
        echoing.onVolumeChanged(8, 9, active = true)
        echoing.expectOwnChange(8)
        time += 50
        assertEquals(Outcome.OWN, echoing.onVolumeChanged(9, 8, active = true))
    }

    @Test
    fun oldOwnChange_expires() {
        detector.expectOwnChange(9)
        time += EarbudVolumeDetector.OWN_WINDOW_MS + 1
        assertEquals(Outcome.GESTURE, detector.onVolumeChanged(8, 9, active = true))
    }

    @Test
    fun phoneVolumeKey_quietPeriodIgnoresChanges() {
        detector.ignoreFor(1_500)
        time += 1_000
        assertEquals(Outcome.QUIET, detector.onVolumeChanged(8, 9, active = true))
        time += 600
        assertEquals(Outcome.GESTURE, detector.onVolumeChanged(9, 10, active = true))
    }

    @Test
    fun inactive_doesNothing() {
        assertEquals(Outcome.INACTIVE, detector.onVolumeChanged(8, 9, active = false))
        assertEquals(emptyList<String>(), gestures)
        assertEquals(emptyList<Int>(), restores)
    }

    @Test
    fun unchangedVolume_isIgnored() {
        assertEquals(Outcome.UNCHANGED, detector.onVolumeChanged(8, 8, active = true))
    }
}
