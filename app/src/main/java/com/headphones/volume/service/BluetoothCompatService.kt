package com.headphones.volume.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.headphones.volume.MainActivity
import com.headphones.volume.R
import com.headphones.volume.audio.PlayerController
import com.headphones.volume.core.Diagnostics
import com.headphones.volume.core.KeyRouter

/**
 * Експериментальний режим сумісності для Bluetooth-навушників.
 *
 * На багатьох телефонах кнопки Bluetooth-навушників (AVRCP) не проходять через Спеціальні можливості:
 * система одразу віддає їх медіасесії застосунку, який останнім почав грати.
 * Тут ми тихо «граємо» тишу і тримаємо власну медіасесію, тож кнопки приходять до нас.
 * Наступний/Попередній обробляє [KeyRouter], решту кнопок пересилаємо справжньому плеєру.
 *
 * Служба працює постійно, але тишу грає й перехоплює кнопки лише поки підключені навушники:
 * підключили — одразу працює, відключили — нічого не витрачає.
 */
class BluetoothCompatService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private lateinit var player: PlayerController
    private lateinit var session: MediaSession
    private var silence: AudioTrack? = null
    private var listening = false
    private var lastActiveCount = 0
    private var lastRestartAt = 0L

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            // Інший плеєр почав грати і забрав кнопки: перезапускаємо тишу, щоб знову стати «останнім».
            val count = configs.size
            if (count > lastActiveCount && SystemClock.uptimeMillis() - lastRestartAt > RESTART_GUARD_MS) {
                handler.postDelayed(::restartSilence, 300)
            }
            lastActiveCount = count
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = updateListening()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = updateListening()
    }

    override fun onCreate() {
        super.onCreate()
        Diagnostics.compatRunning.value = true
        startInForeground(headphonesConnected = false)
        audioManager = getSystemService(AudioManager::class.java)
        player = PlayerController(this)

        session = MediaSession(this, "HeadphonesVolumeCompat").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = IntentCompat.getParcelableExtra(
                        mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java,
                    ) ?: return false
                    if (!KeyRouter.handle(event, KeyRouter.Source.MEDIA_SESSION)) player.forward(event)
                    return true
                }
            }, handler)
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                    )
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build(),
            )
        }

        // Колбек одразу повідомляє про вже підключені пристрої, тож стан визначиться сам.
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        updateListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        stopListening()
        handler.removeCallbacksAndMessages(null)
        session.release()
        Diagnostics.compatRunning.value = false
        super.onDestroy()
    }

    private fun updateListening() {
        val connected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }
        if (connected == listening) return
        if (connected) startListening() else stopListening()
        startInForeground(headphonesConnected = connected)
    }

    private fun startListening() {
        listening = true
        Diagnostics.compatListening.value = true
        session.isActive = true
        startSilence()
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        Diagnostics.compatListening.value = false
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        handler.removeCallbacksAndMessages(null)
        silence?.release()
        silence = null
        session.isActive = false
    }

    private fun startSilence() {
        val frames = SAMPLE_RATE // одна секунда тиші, що повторюється безкінечно
        silence = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build()
            .apply {
                write(ShortArray(frames), 0, frames)
                setLoopPoints(0, frames, -1)
                play()
            }
        lastRestartAt = SystemClock.uptimeMillis()
    }

    private fun restartSilence() {
        val track = silence ?: return
        lastRestartAt = SystemClock.uptimeMillis()
        track.pause()
        track.play()
    }

    private fun startInForeground(headphonesConnected: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Режим сумісності Bluetooth", NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (headphonesConnected) {
            "Навушники підключено: кнопки працюють"
        } else {
            "Чекаю на навушники"
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private companion object {
        const val CHANNEL_ID = "bluetooth_compat"
        const val NOTIFICATION_ID = 1
        const val SAMPLE_RATE = 8000
        const val RESTART_GUARD_MS = 1000L

        val HEADPHONE_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}
