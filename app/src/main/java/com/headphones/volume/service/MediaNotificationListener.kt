package com.headphones.volume.service

import android.service.notification.NotificationListenerService

/**
 * Порожній сервіс: сам дозвіл «Доступ до сповіщень» дає право бачити активні плеєри
 * (MediaSessionManager.getActiveSessions). Вміст сповіщень не читається.
 */
class MediaNotificationListener : NotificationListenerService()
