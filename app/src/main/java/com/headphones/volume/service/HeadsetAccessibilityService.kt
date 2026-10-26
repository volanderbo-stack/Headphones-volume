package com.headphones.volume.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.headphones.volume.core.Diagnostics
import com.headphones.volume.core.KeyRouter

/**
 * Отримує натискання кнопок раніше за будь-який плеєр (flagRequestFilterKeyEvents).
 * Працює у фоні та при вимкненому екрані, поки увімкнений у Спеціальних можливостях.
 */
class HeadsetAccessibilityService : AccessibilityService() {
    private var touchWatcher: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        KeyRouter.init(this)
        Diagnostics.accessibilityConnected.value = true
        // Підключений сервіс доступності дозволяє запустити режим сумісності навіть після перезавантаження.
        KeyRouter.syncCompatService()
        addTouchWatcher()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = KeyRouter.handle(event, KeyRouter.Source.ACCESSIBILITY)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = KeyRouter.reset()

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    /**
     * Невидима точка 1×1 пкс, яка дізнається про кожен дотик до екрана (ACTION_OUTSIDE),
     * не перехоплюючи його. Так зміну гучності пальцем на повзунку не сплутати з затисканням навушників.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addTouchWatcher() {
        if (touchWatcher != null) return
        val view = View(this).apply {
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_DOWN) {
                    KeyRouter.onScreenTouched()
                }
                false
            }
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSPARENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            touchWatcher = view
            Diagnostics.touchWatcherActive.value = true
        } catch (e: RuntimeException) {
            Log.w("HeadsetAccessibility", "Не вдалося додати відстеження дотиків", e)
        }
    }

    private fun removeTouchWatcher() {
        val view = touchWatcher ?: return
        touchWatcher = null
        Diagnostics.touchWatcherActive.value = false
        try {
            getSystemService(WindowManager::class.java).removeView(view)
        } catch (e: RuntimeException) {
            // Вікно вже прибрала система.
        }
    }

    private fun disconnect() {
        Diagnostics.accessibilityConnected.value = false
        removeTouchWatcher()
        KeyRouter.reset()
    }
}
