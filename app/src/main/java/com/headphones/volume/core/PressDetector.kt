package com.headphones.volume.core

enum class MediaButton { NEXT, PREVIOUS }

enum class VolumeDirection { UP, DOWN }

data class PressConfig(
    val longPressMs: Long,
    val stepMs: Long,
    val swapButtons: Boolean,
    /** true: коротке натискання змінює гучність на крок, затискання перемикає трек. */
    val swapFunctions: Boolean = false,
)

interface PressListener {
    fun onTrackSkip(button: MediaButton, heldMs: Long)

    /** Коротке натискання в режимі [PressConfig.swapFunctions]: один крок гучності. */
    fun onVolumeTap(direction: VolumeDirection, heldMs: Long)
    fun onVolumeHoldStart(button: MediaButton, direction: VolumeDirection)

    /** Повертає false, коли гучність більше не змінюється (межа або системне обмеження). */
    fun onVolumeStep(direction: VolumeDirection): Boolean
    fun onVolumeHoldEnd(button: MediaButton, heldMs: Long)
}

fun interface Cancellable {
    fun cancel()
}

interface Scheduler {
    fun now(): Long
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

/**
 * Розпізнає коротке й довге натискання кнопок «Наступний/Попередній трек».
 * Звичайний режим: коротке перемикає трек, затискання плавно змінює гучність.
 * Режим [PressConfig.swapFunctions]: коротке змінює гучність на крок, затискання перемикає трек.
 * Не залежить від Android: час і таймери приходять через [Scheduler], тому логіку легко тестувати.
 * Налаштування читаються через [config] на кожну подію, тож зміни діють одразу.
 */
class PressDetector(
    private val scheduler: Scheduler,
    private val config: () -> PressConfig,
    private val listener: PressListener,
) {
    private var pressed: MediaButton? = null
    private var downAt = 0L
    private var lastDownAt = 0L
    private var longActive = false
    private var swapFunctions = false
    private var direction = VolumeDirection.UP
    private var pending: Cancellable? = null
    private var safety: Cancellable? = null

    fun onDown(button: MediaButton, repeatCount: Int) {
        val now = scheduler.now()
        // Поки кнопку тримають, приходять повтори DOWN: від дротових навушників з repeatCount > 0,
        // від Bluetooth (AVRCP) з repeatCount 0. Вони продовжують поточне натискання, а не починають нове.
        if (pressed == button && (repeatCount > 0 || now - lastDownAt <= REPEAT_WINDOW_MS)) {
            lastDownAt = now
            return
        }
        if (repeatCount > 0) return
        // Новий DOWN без UP і без свіжих повторів означає, що попередній UP загубився: скидаємо старе натискання.
        cancel()

        val cfg = config()
        pressed = button
        downAt = now
        lastDownAt = now
        // Режим фіксуємо на все натискання, щоб зміна налаштувань посеред утримання нічого не плутала.
        swapFunctions = cfg.swapFunctions
        direction = directionFor(button, cfg.swapButtons)
        pending = scheduler.schedule(cfg.longPressMs) { startLongPress() }
        // Якщо UP так і не прийде (буває з Bluetooth), не крутимо гучність безкінечно.
        safety = scheduler.schedule(MAX_HOLD_MS) { onUp(button) }
    }

    fun onUp(button: MediaButton) {
        if (pressed != button) return
        val heldMs = scheduler.now() - downAt
        val wasLong = longActive
        val swapped = swapFunctions
        cancel()
        when {
            // Трек уже перемкнули, щойно минув поріг; відпускання нічого не робить.
            wasLong && swapped -> Unit
            wasLong -> listener.onVolumeHoldEnd(button, heldMs)
            swapped -> listener.onVolumeTap(direction, heldMs)
            else -> listener.onTrackSkip(button, heldMs)
        }
    }

    /** Скидає поточне натискання без жодної дії. */
    fun cancel() {
        pending?.cancel()
        safety?.cancel()
        pending = null
        safety = null
        pressed = null
        longActive = false
    }

    private fun startLongPress() {
        val button = pressed ?: return
        longActive = true
        if (swapFunctions) {
            pending = null
            listener.onTrackSkip(button, scheduler.now() - downAt)
        } else {
            listener.onVolumeHoldStart(button, direction)
            step()
        }
    }

    private fun step() {
        pending = if (listener.onVolumeStep(direction)) {
            scheduler.schedule(config().stepMs) { step() }
        } else {
            null
        }
    }

    companion object {
        const val MAX_HOLD_MS = 15_000L

        /** AVRCP повторює «натиснуто» щонайменше раз на 2 с, поки кнопку тримають. */
        const val REPEAT_WINDOW_MS = 2_000L

        fun directionFor(button: MediaButton, swapButtons: Boolean): VolumeDirection {
            val up = (button == MediaButton.NEXT) != swapButtons
            return if (up) VolumeDirection.UP else VolumeDirection.DOWN
        }
    }
}
