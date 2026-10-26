package com.headphones.volume.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * «Ще тихіше за мінімум»: коли системна гучність уже на найменшому кроці, додатково приглушує звук
 * на кілька децибел. Android тихіше за один крок не вміє, тому тут глобальний аудіоефект (сесія 0):
 * DynamicsProcessing на Android 9+, або еквалайзер з усіма смугами вниз.
 * Працює не на всіх телефонах: якщо ефект не вдалося ввімкнути, [supported] стає false.
 */
object ExtraQuiet {
    private const val TAG = "ExtraQuiet"
    private const val PRIORITY = 1000
    private const val OUTPUT_MIX_SESSION = 0

    private var effect: AudioEffect? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    /** null: ще не пробували; false: телефон не дав увімкнути ефект. */
    val supported = MutableStateFlow<Boolean?>(null)

    /** Системна гучність, на якій увімкнули приглушення; інша гучність означає, що його треба зняти. */
    var level = -1
        private set

    var attenuationDb = 0
        private set

    fun enable(atLevel: Int, db: Int): Boolean {
        val e = effect ?: create()
        if (e == null) {
            supported.value = false
            return false
        }
        effect = e
        if (!apply(e, db) || e.setEnabled(true) != AudioEffect.SUCCESS) {
            release()
            supported.value = false
            return false
        }
        supported.value = true
        level = atLevel
        attenuationDb = db
        _active.value = true
        return true
    }

    /** Змінює силу приглушення наживо, якщо воно зараз увімкнене. */
    fun updateAttenuation(db: Int) {
        val e = effect ?: return
        if (_active.value && db != attenuationDb && apply(e, db)) attenuationDb = db
    }

    fun disable() {
        if (!_active.value) return
        _active.value = false
        level = -1
        release()
    }

    private fun create(): AudioEffect? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    false, 1, // pre-EQ
                    false, 1, // багатосмуговий компресор
                    false, 1, // post-EQ
                    false, // лімітер
                ).build()
                return DynamicsProcessing(PRIORITY, OUTPUT_MIX_SESSION, config)
            } catch (e: Exception) {
                Log.w(TAG, "DynamicsProcessing недоступний, пробую еквалайзер", e)
            }
        }
        return try {
            Equalizer(PRIORITY, OUTPUT_MIX_SESSION)
        } catch (e: Exception) {
            Log.w(TAG, "Глобальний еквалайзер недоступний", e)
            null
        }
    }

    private fun apply(effect: AudioEffect, db: Int): Boolean = try {
        when (effect) {
            is DynamicsProcessing -> effect.setInputGainAllChannelsTo(-db.toFloat())
            is Equalizer -> {
                val lowest = effect.bandLevelRange[0].toInt()
                val bandLevel = maxOf(lowest, -db * 100).toShort()
                for (band in 0 until effect.numberOfBands) effect.setBandLevel(band.toShort(), bandLevel)
            }
        }
        true
    } catch (e: Exception) {
        // Наприклад, ефектом керує інший застосунок з вищим пріоритетом.
        Log.w(TAG, "Не вдалося застосувати приглушення", e)
        false
    }

    private fun release() {
        try {
            effect?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Не вдалося звільнити ефект", e)
        }
        effect = null
    }
}
