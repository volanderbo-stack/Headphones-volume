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
        time += EarbudVolumeDetector.QUIET_EXTEND_MS + 1 // після ручної зміни тиша ще трохи триває
        assertEquals(Outcome.GESTURE, detector.onVolumeChanged(9, 10, active = true))
    }

    @Test
    fun sliderDrag_keepsExtendingQuietPeriod() {
        detector.ignoreFor(2_500) // дотик до екрана
        repeat(10) { step ->
            time += 1_000 // повзунок тягнуть 10 с, зміна щосекунди
            assertEquals(Outcome.QUIET, detector.onVolumeChanged(8 + step, 9 + step, active = true))
        }
        assertEquals(emptyList<String>(), gestures)

        time += EarbudVolumeDetector.QUIET_EXTEND_MS + 1
        assertEquals(Outcome.GESTURE, detector.onVolumeChanged(18, 19, active = true))
    }

    @Test
    fun holdTick_restoresBeforeSystemNotification() {
        detector.onVolumeChanged(8, 9, active = true)
        restores.clear()

        time += 16
        assertEquals(true, detector.holdTick(current = 8)) // гучність на місці: нічого не робимо
        assertEquals(emptyList<Int>(), restores)

        time += 16
        assertEquals(true, detector.holdTick(current = 10)) // навушники знову зсунули
        assertEquals(listOf(8), restores)
        assertEquals(listOf("NEXT 8→9"), gestures) // трек удруге не перемикається
    }

    @Test
    fun holdTick_stopsWhenBurstEnds() {
        assertEquals(false, detector.holdTick(current = 8)) // серії немає
        detector.onVolumeChanged(8, 9, active = true)
        time += EarbudVolumeDetector.BURST_GAP_MS + 1
        assertEquals(false, detector.holdTick(current = 12))
        assertEquals(listOf(8), restores)
    }

    @Test
    fun ownStepDuringBurst_becomesNewBaseline() {
        detector.onVolumeChanged(8, 9, active = true) // затискання: тримаємо 8
        time += 300
        detector.expectOwnChange(10) // коротке натискання: застосунок сам ставить 10
        assertEquals(Outcome.OWN, detector.onVolumeChanged(8, 10, active = true))
        restores.clear()

        time += 16
        detector.holdTick(current = 10)
        assertEquals(emptyList<Int>(), restores) // 10 не відкочуємо назад до 8

        time += 300
        detector.onVolumeChanged(10, 11, active = true) // навушники знову зсунули
        assertEquals(listOf(10), restores)
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
