package com.headphones.volume.audio

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import com.headphones.volume.core.MediaButton
import com.headphones.volume.service.MediaNotificationListener

/** Передає команди активному плеєру (Spotify, YouTube Music, VLC тощо). */
class PlayerController(private val context: Context) {
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /**
     * Перемикає трек. Без доступу до сповіщень надсилає системну медіакнопку,
     * але тільки якщо [allowKeyFallback]: у режимі сумісності така кнопка повернулася б до нас самих.
     */
    fun skip(button: MediaButton, allowKeyFallback: Boolean) {
        val controller = activeController()
        if (controller != null) {
            when (button) {
                MediaButton.NEXT -> controller.transportControls.skipToNext()
                MediaButton.PREVIOUS -> controller.transportControls.skipToPrevious()
            }
        } else if (allowKeyFallback) {
            val code = when (button) {
                MediaButton.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                MediaButton.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            }
            val now = SystemClock.uptimeMillis()
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        }
    }

    /** Пересилає будь-яку іншу медіакнопку (пауза, гра…) справжньому плеєру. */
    fun forward(event: KeyEvent): Boolean = activeController()?.dispatchMediaButtonEvent(event) ?: false

    private fun activeController(): MediaController? {
        val sessions = try {
            sessionManager.getActiveSessions(ComponentName(context, MediaNotificationListener::class.java))
        } catch (e: SecurityException) {
            return null // Немає доступу до сповіщень.
        }
        val players = sessions.filter { it.packageName != context.packageName }
        return players.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: players.firstOrNull()
    }
}
