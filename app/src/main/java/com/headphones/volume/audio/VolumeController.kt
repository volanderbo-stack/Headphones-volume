package com.headphones.volume.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.headphones.volume.core.VolumeDirection

/** Гучність музики (STREAM_MUSIC). */
class VolumeController(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    val current: Int get() = audioManager.getStreamVolume(STREAM)
    val max: Int get() = audioManager.getStreamMaxVolume(STREAM)
    val min: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audioManager.getStreamMinVolume(STREAM) else 0

    /**
     * Найменша гучність, яку зазвичай чутно. На шкалах з багатьма кроками (наприклад, 30 з Bluetooth-навушниками)
     * крок 1 часто звучить як тиша, тому за замовчуванням там 2.
     */
    val autoLowestAudible: Int get() = maxOf(min, if (max >= FINE_SCALE_STEPS) 2 else 1)

    /**
     * Змінює гучність на один крок.
     * Повертає false, якщо гучність не змінилася: досягнуто межі, система показала
     * попередження «безпечна гучність» або зміну заборонено (наприклад, режим «Не турбувати»).
     *
     * Рівні між тишею і [lowestAudible] пропускаються: вони нечутні. [extraQuietDb] вмикає рівень «½»
     * між найменшою чутною гучністю і тишею: … → 3 → 2 → ½ → 0. На рівні ½ системна гучність
     * лишається [lowestAudible], а звук додатково приглушує [ExtraQuiet].
     */
    fun step(
        direction: VolumeDirection,
        showSystemUi: Boolean,
        extraQuietDb: Int? = null,
        lowestAudible: Int = autoLowestAudible,
    ): Boolean {
        val before = current
        val up = direction == VolumeDirection.UP
        val flags = if (showSystemUi) AudioManager.FLAG_SHOW_UI else 0
        val lowest = lowestAudible.coerceIn(maxOf(min, 1), max)

        if (ExtraQuiet.active.value) {
            ExtraQuiet.disable()
            // Гучніше: з ½ назад на найменшу чутну. Тихіше: з ½ у тишу, якщо система її дозволяє.
            if (up || min >= lowest) return true
            setSafely(min, flags)
            return true
        }
        if (!up && extraQuietDb != null && before == lowest && ExtraQuiet.enable(before, extraQuietDb)) {
            return true
        }
        if (up && before >= max || !up && before <= min) return false

        return try {
            when {
                // Нечутні рівні пропускаємо одразу.
                up && before < lowest -> audioManager.setStreamVolume(STREAM, lowest, flags)
                !up && before <= lowest -> audioManager.setStreamVolume(STREAM, min, flags)
                else -> {
                    audioManager.adjustStreamVolume(
                        STREAM,
                        if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                        flags,
                    )
                    // Деякі прошивки ігнорують ADJUST_LOWER на останньому кроці: доводимо вручну.
                    if (current == before) {
                        audioManager.setStreamVolume(STREAM, if (up) before + 1 else before - 1, flags)
                    }
                    // Крок системи буває більшим за 1 (3 → 1): не проскакуємо найменшу чутну гучність.
                    if (!up && current < lowest) audioManager.setStreamVolume(STREAM, lowest, flags)
                }
            }
            current != before
        } catch (e: SecurityException) {
            false
        }
    }

    private fun setSafely(value: Int, flags: Int) {
        try {
            audioManager.setStreamVolume(STREAM, value, flags)
        } catch (e: SecurityException) {
            // Режим «Не турбувати» або системне обмеження: залишаємо як є.
        }
    }

    fun set(value: Int) = setSafely(value.coerceIn(min, max), 0)

    /** Підпис для журналу й екрана: «½» на рівні приглушення. */
    fun label(): String =
        if (ExtraQuiet.active.value) {
            "½ (на ${ExtraQuiet.attenuationDb} дБ тихіше за ${ExtraQuiet.level}) з $max"
        } else {
            "$current з $max"
        }

    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC
        const val FINE_SCALE_STEPS = 25
    }
}
