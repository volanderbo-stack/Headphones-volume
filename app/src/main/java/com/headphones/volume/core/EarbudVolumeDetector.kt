package com.headphones.volume.core

/**
 * Для навушників, які при затисканні кнопки самі змінюють гучність і не надсилають телефону жодної кнопки.
 * Розпізнає таку зміну гучності як жест: гучніше → трек вперед, тихіше → трек назад, а гучність повертає назад.
 *
 * Не жест: зміни від самого застосунку ([expectOwnChange]), від кнопок телефону та під час
 * підключення навушників ([ignoreFor]). Поки навушники тримають, вони змінюють гучність кілька разів
 * поспіль: це одна серія, трек перемикається один раз, а гучність щоразу повертається.
 * Не залежить від Android, тому логіку легко тестувати.
 */
class EarbudVolumeDetector(
    private val clock: () -> Long,
    private val onGesture: (button: MediaButton, from: Int, to: Int) -> Unit,
    private val restore: (value: Int) -> Unit,
) {
    enum class Outcome { OWN, UNCHANGED, QUIET, CONTINUED, INACTIVE, GESTURE }

    private val ownChanges = mutableListOf<Pair<Int, Long>>()
    private var quietUntil = 0L
    private var baseline: Int? = null
    private var lastChangeAt = 0L

    /** Застосунок сам ставить гучність [value]: така зміна не є жестом. */
    fun expectOwnChange(value: Int) {
        ownChanges += value to clock()
    }

    /** Наступні [ms] мс зміни гучності не вважати жестами (кнопки телефону, підключення навушників). */
    fun ignoreFor(ms: Long) {
        quietUntil = maxOf(quietUntil, clock() + ms)
    }

    /** @param active чи можна зараз сприймати зміну гучності як жест навушників. */
    fun onVolumeChanged(prev: Int, new: Int, active: Boolean): Outcome {
        val now = clock()
        ownChanges.removeAll { now - it.second > OWN_WINDOW_MS }
        val own = ownChanges.firstOrNull { it.first == new }
        if (own != null) {
            ownChanges.remove(own)
            return Outcome.OWN
        }
        if (prev == new) return Outcome.UNCHANGED
        if (now < quietUntil) {
            baseline = null
            return Outcome.QUIET
        }

        val burstBaseline = baseline
        if (burstBaseline != null && now - lastChangeAt <= BURST_GAP_MS) {
            lastChangeAt = now
            if (new != burstBaseline) restore(burstBaseline)
            return Outcome.CONTINUED
        }
        baseline = null
        if (!active) return Outcome.INACTIVE

        baseline = prev
        lastChangeAt = now
        onGesture(if (new > prev) MediaButton.NEXT else MediaButton.PREVIOUS, prev, new)
        restore(prev)
        return Outcome.GESTURE
    }

    companion object {
        const val OWN_WINDOW_MS = 1_000L
        const val BURST_GAP_MS = 1_500L
    }
}
