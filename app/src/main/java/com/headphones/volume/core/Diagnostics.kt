package com.headphones.volume.core

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Стан сервісів і журнал режиму «Тест». Сервіси й екран живуть в одному процесі. */
object Diagnostics {
    val testMode = MutableStateFlow(false)
    val accessibilityConnected = MutableStateFlow(false)
    val compatRunning = MutableStateFlow(false)

    /** Режим сумісності зараз перехоплює кнопки (навушники підключено). */
    val compatListening = MutableStateFlow(false)
    val touchWatcherActive = MutableStateFlow(false)

    data class Entry(val id: Long, val time: String, val text: String, val isAction: Boolean)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private var nextId = 0L
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun logKey(event: KeyEvent, source: KeyRouter.Source) {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "натиснуто"
            KeyEvent.ACTION_UP -> "відпущено"
            else -> "подія ${event.action}"
        }
        val repeat = if (event.repeatCount > 0) " (повтор ${event.repeatCount})" else ""
        val device = event.device?.name?.let { " · $it" } ?: ""
        add("${keyName(event.keyCode)}: $action$repeat · ${source.label}$device", isAction = false)
    }

    fun logAction(text: String) = add("→ $text", isAction = true)

    fun clear() = _entries.update { emptyList() }

    private fun add(text: String, isAction: Boolean) {
        val entry = Entry(nextId++, timeFormat.format(Date()), text, isAction)
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
    }

    private fun keyName(code: Int) = when (code) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> "⏭ Наступний трек"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "⏮ Попередній трек"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> "⏯ Пауза/Гра"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "▶ Гра"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "⏸ Пауза"
        KeyEvent.KEYCODE_VOLUME_UP -> "🔊 Гучніше"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "🔉 Тихіше"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
    }

    private const val MAX_ENTRIES = 40
}
