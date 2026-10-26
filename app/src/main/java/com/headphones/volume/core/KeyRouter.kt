package com.headphones.volume.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.headphones.volume.audio.ExtraQuiet
import com.headphones.volume.audio.PlayerController
import com.headphones.volume.audio.VolumeController
import com.headphones.volume.service.BluetoothCompatService
import com.headphones.volume.settings.AppSettings
import com.headphones.volume.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Єдина точка, куди приходять кнопки навушників: і від сервісу Спеціальних можливостей,
 * і від медіасесії режиму сумісності. Вирішує, що робити, і виконує дію.
 */
object KeyRouter : PressListener {
    enum class Source(val label: String) {
        ACCESSIBILITY("Спец. можливості"),
        MEDIA_SESSION("Режим сумісності BT"),
    }

    private const val TAG = "KeyRouter"

    // Системна трансляція про зміну гучності; офіційно прихована, але надсилається на всіх версіях Android.
    private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    private const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    private const val EXTRA_PREV_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

    /** Скільки ігнорувати зміни гучності після кнопки гучності телефону. */
    private const val PHONE_KEY_QUIET_MS = 1_500L

    /** Скільки ігнорувати зміни гучності після підключення чи відключення навушників. */
    private const val DEVICE_QUIET_MS = 5_000L

    /** Скільки після дотику до екрана зміни гучності вважати ручними (наприклад, повзунком). */
    private const val TOUCH_QUIET_MS = 4_000L

    /** Чому зараз зміни гучності ігноруються: для журналу «Тесту». */
    private var quietReason = ""

    private lateinit var app: Context
    private lateinit var volume: VolumeController
    private lateinit var player: PlayerController
    private lateinit var vibrator: Vibrator
    private lateinit var power: PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var settings = AppSettings()
        private set

    private val detector = PressDetector(
        scheduler = HandlerScheduler(mainHandler),
        config = {
            val s = settings
            PressConfig(s.longPressMs.toLong(), s.stepMs.toLong(), s.swapButtons, s.swapFunctions)
        },
        listener = this,
    )

    private val earbuds = EarbudVolumeDetector(
        clock = SystemClock::uptimeMillis,
        onGesture = ::onEarbudGesture,
        restore = ::restoreVolume,
    )

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        volume = VolumeController(app)
        player = PlayerController(app)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        }
        power = app.getSystemService(PowerManager::class.java)
        scope.launch {
            SettingsRepository(app).settings.collect {
                settings = it
                syncCompatService()
                if (it.extraQuiet) ExtraQuiet.updateAttenuation(it.extraQuietDb) else ExtraQuiet.disable()
            }
        }
        watchEarbudVolume()
        // Після перезавантаження Android може не дати запустити режим сумісності одразу: пробуємо знову,
        // коли вмикається екран (а також при підключенні навушників і відкритті застосунку).
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = syncCompatService()
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun watchEarbudVolume() {
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                    val new = intent.getIntExtra(EXTRA_VALUE, -1)
                    val prev = intent.getIntExtra(EXTRA_PREV_VALUE, -1)
                    if (new >= 0 && prev >= 0) onSystemVolumeChanged(prev, new)
                }
            },
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Під час підключення навушників Android сам виставляє гучність: це не жест.
        app.getSystemService(AudioManager::class.java).registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    quiet(DEVICE_QUIET_MS, "підключення навушників")
                    syncCompatService()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    quiet(DEVICE_QUIET_MS, "відключення навушників")
                    // Без навушників гучність інша: рівень «½» більше не має сенсу.
                    ExtraQuiet.disable()
                }
            },
            mainHandler,
        )
    }

    /** Користувач торкнувся екрана: найближчі зміни гучності, ймовірно, від повзунка. */
    fun onScreenTouched() = quiet(TOUCH_QUIET_MS, "дотик до екрана")

    private fun quiet(ms: Long, reason: String) {
        quietReason = reason
        earbuds.ignoreFor(ms)
    }

    private fun onSystemVolumeChanged(prev: Int, new: Int) {
        val s = settings
        val testing = Diagnostics.testMode.value
        // При увімкненому екрані потрібне відстеження дотиків, щоб не сплутати з повзунком гучності.
        val screenOk = !power.isInteractive ||
            (s.earbudHoldScreenOn && Diagnostics.touchWatcherActive.value) || testing
        val active = s.enabled && s.swapFunctions && s.earbudHoldSkips && screenOk
        val outcome = earbuds.onVolumeChanged(prev, new, active)
        // Гучність змінили інакше (кнопки телефону, повзунок): рівень «½» знімаємо.
        // Жест навушників повертає гучність назад, тож тоді «½» лишається.
        if (ExtraQuiet.active.value && new != ExtraQuiet.level &&
            outcome != EarbudVolumeDetector.Outcome.GESTURE && outcome != EarbudVolumeDetector.Outcome.CONTINUED
        ) {
            ExtraQuiet.disable()
        }
        if (!testing) return
        when (outcome) {
            EarbudVolumeDetector.Outcome.QUIET ->
                Diagnostics.logAction("Гучність $prev → $new після: $quietReason. Вважаю ручною зміною, пропускаю")
            EarbudVolumeDetector.Outcome.INACTIVE ->
                Diagnostics.logAction("Гучність $prev → $new змінили навушники, але «Затискання перемикає трек» вимкнено")
            else -> Unit
        }
    }

    private fun onEarbudGesture(button: MediaButton, from: Int, to: Int) {
        if (Diagnostics.testMode.value) {
            Diagnostics.logAction(
                "Навушники змінили гучність $from → $to: ${button.trackLabel()}, гучність повернуто. " +
                    "У тесті трек не перемикається",
            )
            return
        }
        player.skip(button, allowKeyFallback = !Diagnostics.compatRunning.value)
    }

    private fun restoreVolume(value: Int) {
        earbuds.expectOwnChange(value)
        volume.set(value)
    }

    /** @return true, якщо подію перехоплено і далі її передавати не треба. */
    fun handle(event: KeyEvent, source: Source): Boolean {
        val testing = Diagnostics.testMode.value
        if (testing) Diagnostics.logKey(event, source)

        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            quiet(PHONE_KEY_QUIET_MS, "кнопка гучності телефону")
        }

        val button = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> MediaButton.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaButton.PREVIOUS
            else -> return false
        }
        if (!settings.enabled && !testing) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> detector.onDown(button, event.repeatCount)
            KeyEvent.ACTION_UP -> detector.onUp(button)
        }
        return true
    }

    fun reset() = detector.cancel()

    /** Кнопки «Тихіше/Гучніше» на екрані: той самий крок, що й з навушників, включно з рівнем «½». */
    fun stepVolumeFromUi(direction: VolumeDirection) {
        if (::app.isInitialized) onVolumeStep(direction)
    }

    /** Запускає або зупиняє режим сумісності відповідно до налаштувань і дозволів. */
    fun syncCompatService() {
        if (!::app.isInitialized) return
        val s = settings
        val shouldRun = s.enabled && s.bluetoothCompat && Permissions.hasNotificationAccess(app)
        if (shouldRun == Diagnostics.compatRunning.value) return
        val intent = Intent(app, BluetoothCompatService::class.java)
        try {
            if (shouldRun) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
        } catch (e: Exception) {
            // Android може не дозволити запуск із фону; повторимо, коли відкриється екран або підключиться сервіс.
            Log.w(TAG, "Не вдалося змінити стан режиму сумісності", e)
        }
    }

    override fun onTrackSkip(button: MediaButton, heldMs: Long) {
        if (Diagnostics.testMode.value) {
            Diagnostics.logAction("${pressLabel(heldMs)}: ${button.trackLabel()}. У тесті трек не перемикається")
            return
        }
        player.skip(button, allowKeyFallback = !Diagnostics.compatRunning.value)
    }

    override fun onVolumeTap(direction: VolumeDirection, heldMs: Long) {
        onVolumeStep(direction)
        if (Diagnostics.testMode.value) {
            Diagnostics.logAction("${pressLabel(heldMs)}: ${direction.label()}. Гучність ${volume.label()}")
        }
    }

    override fun onVolumeHoldStart(button: MediaButton, direction: VolumeDirection) {
        if (Diagnostics.testMode.value) {
            Diagnostics.logAction("Затискання: ${direction.label()}")
        }
    }

    override fun onVolumeStep(direction: VolumeDirection): Boolean {
        val s = settings
        val before = volume.current
        val changed = volume.step(
            direction,
            s.showSystemUi,
            extraQuietDb = if (s.extraQuiet) s.extraQuietDb else null,
            lowestAudible = if (s.lowestAudible > 0) s.lowestAudible else volume.autoLowestAudible,
        )
        if (volume.current != before) earbuds.expectOwnChange(volume.current)
        if (changed && settings.vibrate) {
            vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        return changed
    }

    override fun onVolumeHoldEnd(button: MediaButton, heldMs: Long) {
        if (Diagnostics.testMode.value) {
            Diagnostics.logAction("Відпущено через $heldMs мс. Гучність ${volume.label()}")
        }
    }

    private fun pressLabel(heldMs: Long) =
        if (heldMs >= settings.longPressMs) "Затискання ($heldMs мс)" else "Коротке натискання ($heldMs мс)"

    private fun MediaButton.trackLabel() = when (this) {
        MediaButton.NEXT -> "трек вперед"
        MediaButton.PREVIOUS -> "трек назад"
    }

    private fun VolumeDirection.label() = when (this) {
        VolumeDirection.UP -> "гучніше ▲"
        VolumeDirection.DOWN -> "тихіше ▼"
    }
}

private class HandlerScheduler(private val handler: Handler) : Scheduler {
    override fun now() = SystemClock.uptimeMillis()

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}
