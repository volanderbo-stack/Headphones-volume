package com.headphones.volume.core

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PressDetectorTest {
    private lateinit var scheduler: FakeScheduler
    private lateinit var listener: RecordingListener
    private var config = PressConfig(longPressMs = 400, stepMs = 150, swapButtons = false)
    private lateinit var detector: PressDetector

    @Before
    fun setUp() {
        scheduler = FakeScheduler()
        listener = RecordingListener()
        detector = PressDetector(scheduler, { config }, listener)
    }

    @Test
    fun shortPress_skipsTrack_withoutVolumeChange() {
        detector.onDown(MediaButton.NEXT, 0)
        scheduler.advanceBy(200)
        detector.onUp(MediaButton.NEXT)

        assertEquals(listOf("skip NEXT 200"), listener.events)
    }

    @Test
    fun longPressNext_raisesVolumeEveryStep_untilReleased() {
        detector.onDown(MediaButton.NEXT, 0)
        scheduler.advanceBy(1000) // кроки на 400, 550, 700, 850, 1000 мс
        detector.onUp(MediaButton.NEXT)

        assertEquals(
            listOf("hold NEXT UP", "step UP", "step UP", "step UP", "step UP", "step UP", "holdEnd NEXT 1000"),
            listener.events,
        )
    }

    @Test
    fun longPressPrevious_lowersVolume() {
        detector.onDown(MediaButton.PREVIOUS, 0)
        scheduler.advanceBy(450)
        detector.onUp(MediaButton.PREVIOUS)

        assertEquals(listOf("hold PREVIOUS DOWN", "step DOWN", "holdEnd PREVIOUS 450"), listener.events)
    }

    @Test
    fun releaseExactlyBeforeThreshold_isShort() {
        detector.onDown(MediaButton.PREVIOUS, 0)
        scheduler.advanceBy(399)
        detector.onUp(MediaButton.PREVIOUS)

        assertEquals(listOf("skip PREVIOUS 399"), listener.events)
    }

    @Test
    fun swappedButtons_nextLowersVolume() {
        config = config.copy(swapButtons = true)
        detector.onDown(MediaButton.NEXT, 0)
        scheduler.advanceBy(400)
        detector.onUp(MediaButton.NEXT)

        assertEquals(listOf("hold NEXT DOWN", "step DOWN", "holdEnd NEXT 400"), listener.events)
    }

    @Test
    fun repeatedDownEvents_doNotRestartTimer() {
        detector.onDown(MediaButton.NEXT, 0)
        scheduler.advanceBy(100)
        detector.onDown(MediaButton.NEXT, 1)
        scheduler.advanceBy(100)
        detector.onDown(MediaButton.NEXT, 2)
        scheduler.advanceBy(200) // разом 400 мс від першого натискання

        assertEquals(listOf("hold NEXT UP", "step UP"), listener.events)
    }

    @Test
    fun stepping_stopsWhenVolumeCannotChange() {
        listener.stepsAllowed = 2
        detector.onDown(MediaButton.NEXT, 0)
        scheduler.advanceBy(2000)
        detector.onUp(MediaButton.NEXT)

        assertEquals(listOf("hold NEXT UP", "step UP", "step UP", "holdEnd NEXT 2000"), listener.events)
    }

    @Test
    fun stepSpeedChange_appliesWhileHolding() {
        detector.onDown(MediaButton.NEXT, 0)
        scheduler.advanceBy(400) // перший крок
        config = config.copy(stepMs = 300)
        scheduler.advanceBy(300) // наступний крок уже через 300 мс
        detector.onUp(MediaButton.NEXT)

        assertEquals(listOf("hold NEXT UP", "step UP", "step UP", "holdEnd NEXT 700"), listener.events)
    }

    @Test
    fun releaseOfOtherButton_isIgnored() {
        detector.onDown(MediaButton.NEXT, 0)
        scheduler.advanceBy(100)
        detector.onUp(MediaButton.PREVIOUS)
        scheduler.advanceBy(100)
        detector.onUp(MediaButton.NEXT)

        assertEquals(listOf("skip NEXT 200"), listener.events)
    }

    @Test
    fun lostUp_downAfterRepeatWindowStartsFreshPress() {
        detector.onDown(MediaButton.NEXT, 0)
        scheduler.advanceBy(PressDetector.REPEAT_WINDOW_MS + 1) // UP загубився, повторів теж немає
        detector.onDown(MediaButton.NEXT, 0)
        scheduler.advanceBy(350)
        detector.onUp(MediaButton.NEXT)

        assertEquals("skip NEXT 350", listener.events.last())
    }

    @Test
    fun bluetoothRepeats_swappedFunctions_holdSkipsTrackOnce() {
        config = config.copy(swapFunctions = true)
        detector.onDown(MediaButton.NEXT, 0)
        holdWithBluetoothRepeats(MediaButton.NEXT, untilMs = 1000)
        detector.onUp(MediaButton.NEXT)

        assertEquals(listOf("skip NEXT 400"), listener.events)
    }

    @Test
    fun bluetoothRepeats_normalMode_holdChangesVolume() {
        detector.onDown(MediaButton.PREVIOUS, 0)
        holdWithBluetoothRepeats(MediaButton.PREVIOUS, untilMs = 500)
        detector.onUp(MediaButton.PREVIOUS)

        assertEquals(listOf("hold PREVIOUS DOWN", "step DOWN", "holdEnd PREVIOUS 500"), listener.events)
    }

    /** Bluetooth (AVRCP) повторює DOWN з repeatCount 0 кожні 100 мс, поки кнопку тримають. */
    private fun holdWithBluetoothRepeats(button: MediaButton, untilMs: Long) {
        while (scheduler.now() < untilMs) {
            scheduler.advanceBy(100)
            if (scheduler.now() < untilMs) detector.onDown(button, 0)
        }
    }

    @Test
    fun missingUp_endsAfterSafetyTimeout() {
        detector.onDown(MediaButton.PREVIOUS, 0)
        scheduler.advanceBy(PressDetector.MAX_HOLD_MS + 1000)

        assertEquals("holdEnd PREVIOUS ${PressDetector.MAX_HOLD_MS}", listener.events.last())
        val stepsBefore = listener.events.size
        scheduler.advanceBy(5000)
        assertEquals(stepsBefore, listener.events.size)
    }

    @Test
    fun cancel_dropsPressWithoutAction() {
        detector.onDown(MediaButton.NEXT, 0)
        scheduler.advanceBy(100)
        detector.cancel()
        scheduler.advanceBy(1000)
        detector.onUp(MediaButton.NEXT)

        assertEquals(emptyList<String>(), listener.events)
    }

    @Test
    fun swappedFunctions_shortPressChangesVolumeOneStep() {
        config = config.copy(swapFunctions = true)
        detector.onDown(MediaButton.NEXT, 0)
        scheduler.advanceBy(150)
        detector.onUp(MediaButton.NEXT)
        detector.onDown(MediaButton.PREVIOUS, 0)
        scheduler.advanceBy(100)
        detector.onUp(MediaButton.PREVIOUS)

        assertEquals(listOf("tap UP 150", "tap DOWN 100"), listener.events)
    }

    @Test
    fun swappedFunctions_holdSkipsTrackOnceAtThreshold() {
        config = config.copy(swapFunctions = true)
        detector.onDown(MediaButton.PREVIOUS, 0)
        scheduler.advanceBy(400)
        assertEquals(listOf("skip PREVIOUS 400"), listener.events) // не чекаємо відпускання

        scheduler.advanceBy(2000)
        detector.onUp(MediaButton.PREVIOUS)
        assertEquals(listOf("skip PREVIOUS 400"), listener.events) // і нічого зайвого після
    }

    @Test
    fun swappedFunctions_withSwappedButtons_nextTapLowersVolume() {
        config = config.copy(swapFunctions = true, swapButtons = true)
        detector.onDown(MediaButton.NEXT, 0)
        scheduler.advanceBy(50)
        detector.onUp(MediaButton.NEXT)

        assertEquals(listOf("tap DOWN 50"), listener.events)
    }

    @Test
    fun modeIsFixedForWholePress() {
        detector.onDown(MediaButton.NEXT, 0) // почали у звичайному режимі
        config = config.copy(swapFunctions = true)
        scheduler.advanceBy(100)
        detector.onUp(MediaButton.NEXT)

        assertEquals(listOf("skip NEXT 100"), listener.events)
    }

    @Test
    fun directionFor_respectsSwap() {
        assertEquals(VolumeDirection.UP, PressDetector.directionFor(MediaButton.NEXT, false))
        assertEquals(VolumeDirection.DOWN, PressDetector.directionFor(MediaButton.PREVIOUS, false))
        assertEquals(VolumeDirection.DOWN, PressDetector.directionFor(MediaButton.NEXT, true))
        assertEquals(VolumeDirection.UP, PressDetector.directionFor(MediaButton.PREVIOUS, true))
    }

    private class RecordingListener : PressListener {
        val events = mutableListOf<String>()
        var stepsAllowed = Int.MAX_VALUE

        override fun onTrackSkip(button: MediaButton, heldMs: Long) {
            events += "skip $button $heldMs"
        }

        override fun onVolumeTap(direction: VolumeDirection, heldMs: Long) {
            events += "tap $direction $heldMs"
        }

        override fun onVolumeHoldStart(button: MediaButton, direction: VolumeDirection) {
            events += "hold $button $direction"
        }

        override fun onVolumeStep(direction: VolumeDirection): Boolean {
            if (stepsAllowed <= 0) return false
            stepsAllowed--
            events += "step $direction"
            return stepsAllowed > 0
        }

        override fun onVolumeHoldEnd(button: MediaButton, heldMs: Long) {
            events += "holdEnd $button $heldMs"
        }
    }

    private class FakeScheduler : Scheduler {
        private class Task(val at: Long, val order: Long, val run: () -> Unit) {
            var cancelled = false
        }

        private var time = 0L
        private var order = 0L
        private val tasks = mutableListOf<Task>()

        override fun now(): Long = time

        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val t = Task(time + delayMs, order++, task)
            tasks += t
            return Cancellable { t.cancelled = true }
        }

        fun advanceBy(ms: Long) {
            val target = time + ms
            while (true) {
                val next = tasks
                    .filter { !it.cancelled && it.at <= target }
                    .minWithOrNull(compareBy<Task>({ it.at }, { it.order }))
                    ?: break
                tasks.remove(next)
                time = next.at
                next.run()
            }
            time = target
        }
    }
}
