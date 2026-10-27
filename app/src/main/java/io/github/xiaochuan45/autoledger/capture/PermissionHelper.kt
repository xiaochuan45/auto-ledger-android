package io.github.xiaochuan45.autoledger.capture

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.StateFlow

data class PermissionSnapshot(
    val notificationAccess: Boolean,
    val accessibilityAccess: Boolean,
    val notificationConnected: Boolean,
    val accessibilityConnected: Boolean,
)

object PermissionHelper {
    /** Updated by actual service callbacks, including a connection completed after Activity.onResume. */
    val connectionState: StateFlow<CaptureConnectionSnapshot> = CaptureServiceConnections.changes

    fun snapshot(context: Context): PermissionSnapshot {
        val notificationComponent = ComponentName(context, LedgerNotificationService::class.java)
        val notificationAccess = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                context.getSystemService(NotificationManager::class.java)
                    .isNotificationListenerAccessGranted(notificationComponent)
            } else {
                Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                    ?.split(':')
                    ?.any { ComponentName.unflattenFromString(it) == notificationComponent } == true
            }
        } catch (_: RuntimeException) { false }

        val accessibilityAccess = try {
            val manager = context.getSystemService(AccessibilityManager::class.java)
            manager.isEnabled && manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any {
                    val service = it.resolveInfo.serviceInfo
                    ComponentName(service.packageName, service.name) ==
                        ComponentName(context, LedgerAccessibilityService::class.java)
                }
        } catch (_: RuntimeException) { false }

        return PermissionSnapshot(
            notificationAccess,
            accessibilityAccess,
            notificationAccess && CaptureServiceConnections.notificationConnected,
            accessibilityAccess && CaptureServiceConnections.accessibilityConnected,
        )
    }

    fun openNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, LedgerNotificationService::class.java).flattenToString())
            if (launch(context, detail)) return true
        }
        return launch(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** Explicit recovery, without changing authorization or claiming synchronous success. */
    fun recoverNotifications(context: Context): NotificationRecoveryResult =
        requestNotificationRecovery(context, automatic = false)

    /** Call once when the app enters the foreground; this method starts no timers or background work. */
    fun maybeRecoverNotificationsOnForeground(context: Context): NotificationRecoveryResult =
        requestNotificationRecovery(context, automatic = true)

    /** One event-driven attempt shares the foreground gate; never call this from a timer. */
    internal fun maybeRecoverNotificationsAfterDisconnect(context: Context): NotificationRecoveryResult =
        requestNotificationRecovery(context, automatic = true)

    private fun requestNotificationRecovery(context: Context, automatic: Boolean): NotificationRecoveryResult {
        val permissions = snapshot(context)
        NotificationRecovery.gate.acquire(
            notificationAccess = permissions.notificationAccess,
            captureEnabled = CapturePreferences(context).hasNotificationSources(),
            connected = permissions.notificationConnected,
            automatic = automatic,
            elapsedRealtime = SystemClock.elapsedRealtime(),
        )?.let { return it }
        return NotificationRecovery.submitRequest(
            requestRebind = {
                // API 24+, supported by our API 26 minimum. Android/OEM retains final binding control.
                NotificationListenerService.requestRebind(ComponentName(context, LedgerNotificationService::class.java))
            },
            requestedAt = System::currentTimeMillis,
        )
    }

    fun openAccessibility(context: Context): Boolean = launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openAppSettings(context: Context): Boolean = launch(context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    fun openBatterySettings(context: Context): Boolean =
        launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || openAppSettings(context)

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: RuntimeException) {
        CaptureDiagnostics.failed(CaptureFailure.SYSTEM_SETTINGS)
        false
    }
}
