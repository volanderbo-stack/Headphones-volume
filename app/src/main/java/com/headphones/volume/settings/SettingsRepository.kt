package com.headphones.volume.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

data class AppSettings(
    val enabled: Boolean = true,
    val longPressMs: Int = 400,
    val stepMs: Int = 150,
    val swapButtons: Boolean = false,
    /** true: коротке натискання змінює гучність, затискання перемикає трек. */
    val swapFunctions: Boolean = true,
    /** Для навушників, що самі змінюють гучність при затисканні: таку зміну перетворювати на перемикання треку. */
    val earbudHoldSkips: Boolean = true,
    /** Те саме при увімкненому екрані (зміни гучності одразу після дотику до екрана не враховуються). */
    val earbudHoldScreenOn: Boolean = false,
    /** Рівень «½» нижче найменшої гучності. */
    val extraQuiet: Boolean = true,
    /** Наскільки рівень «½» тихіший за найменшу гучність. */
    val extraQuietDb: Int = 10,
    /** Найменша чутна гучність; 0 — визначити автоматично за шкалою гучності. */
    val lowestAudible: Int = 0,
    val vibrate: Boolean = false,
    val showSystemUi: Boolean = false,
    val bluetoothCompat: Boolean = false,
) {
    companion object {
        val LONG_PRESS_RANGE = 250..1000
        val STEP_RANGE = 80..400
        val EXTRA_QUIET_DB_RANGE = 3..20
        val LOWEST_AUDIBLE_RANGE = 1..5
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<AppSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[ENABLED] = s.enabled
            prefs[LONG_PRESS_MS] = s.longPressMs
            prefs[STEP_MS] = s.stepMs
            prefs[SWAP_BUTTONS] = s.swapButtons
            prefs[SWAP_FUNCTIONS] = s.swapFunctions
            prefs[EARBUD_HOLD_SKIPS] = s.earbudHoldSkips
            prefs[EARBUD_HOLD_SCREEN_ON] = s.earbudHoldScreenOn
            prefs[EXTRA_QUIET] = s.extraQuiet
            prefs[EXTRA_QUIET_DB] = s.extraQuietDb
            prefs[LOWEST_AUDIBLE] = s.lowestAudible
            prefs[VIBRATE] = s.vibrate
            prefs[SHOW_SYSTEM_UI] = s.showSystemUi
            prefs[BLUETOOTH_COMPAT] = s.bluetoothCompat
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            enabled = this[ENABLED] ?: defaults.enabled,
            longPressMs = (this[LONG_PRESS_MS] ?: defaults.longPressMs).coerceIn(AppSettings.LONG_PRESS_RANGE),
            stepMs = (this[STEP_MS] ?: defaults.stepMs).coerceIn(AppSettings.STEP_RANGE),
            swapButtons = this[SWAP_BUTTONS] ?: defaults.swapButtons,
            swapFunctions = this[SWAP_FUNCTIONS] ?: defaults.swapFunctions,
            earbudHoldSkips = this[EARBUD_HOLD_SKIPS] ?: defaults.earbudHoldSkips,
            earbudHoldScreenOn = this[EARBUD_HOLD_SCREEN_ON] ?: defaults.earbudHoldScreenOn,
            extraQuiet = this[EXTRA_QUIET] ?: defaults.extraQuiet,
            extraQuietDb = (this[EXTRA_QUIET_DB] ?: defaults.extraQuietDb).coerceIn(AppSettings.EXTRA_QUIET_DB_RANGE),
            lowestAudible = (this[LOWEST_AUDIBLE] ?: defaults.lowestAudible)
                .let { if (it == 0) 0 else it.coerceIn(AppSettings.LOWEST_AUDIBLE_RANGE) },
            vibrate = this[VIBRATE] ?: defaults.vibrate,
            showSystemUi = this[SHOW_SYSTEM_UI] ?: defaults.showSystemUi,
            bluetoothCompat = this[BLUETOOTH_COMPAT] ?: defaults.bluetoothCompat,
        )
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val LONG_PRESS_MS = intPreferencesKey("long_press_ms")
        val STEP_MS = intPreferencesKey("step_ms")
        val SWAP_BUTTONS = booleanPreferencesKey("swap_buttons")
        val SWAP_FUNCTIONS = booleanPreferencesKey("swap_functions")
        val EARBUD_HOLD_SKIPS = booleanPreferencesKey("earbud_hold_skips")
        // Новий ключ: у версії 1.4 режим був увімкнений за замовчуванням, тепер вимкнений.
        val EARBUD_HOLD_SCREEN_ON = booleanPreferencesKey("earbud_hold_screen_on_v2")
        val EXTRA_QUIET = booleanPreferencesKey("extra_quiet")
        val EXTRA_QUIET_DB = intPreferencesKey("extra_quiet_db")
        val LOWEST_AUDIBLE = intPreferencesKey("lowest_audible")
        val VIBRATE = booleanPreferencesKey("vibrate")
        val SHOW_SYSTEM_UI = booleanPreferencesKey("show_system_ui")
        val BLUETOOTH_COMPAT = booleanPreferencesKey("bluetooth_compat")
    }
}
