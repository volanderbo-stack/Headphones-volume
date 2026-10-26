package com.headphones.volume.core

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.headphones.volume.service.HeadsetAccessibilityService

data class PermissionState(
    val accessibility: Boolean,
    val notificationAccess: Boolean,
    val batteryUnrestricted: Boolean,
) {
    /** На Android 13+ APK не з Play Маркету спершу потребує «Дозволити обмежені налаштування». */
    val mayNeedRestrictedSettings: Boolean
        get() = !accessibility && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

object Permissions {
    fun read(context: Context) = PermissionState(
        accessibility = isAccessibilityEnabled(context),
        notificationAccess = hasNotificationAccess(context),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
    )

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val me = ComponentName(context, HeadsetAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
