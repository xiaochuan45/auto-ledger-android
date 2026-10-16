package io.github.xiaochuan45.autoledger.capture

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.xiaochuan45.autoledger.core.PaymentChannel

class LedgerNotificationService : NotificationListenerService() {
    private lateinit var preferences: CapturePreferences
    private lateinit var dispatcher: CaptureDispatcher
    private var settingsObserver: AutoCloseable? = null

    override fun onCreate() {
        super.onCreate()
        CaptureServiceConnections.notificationConnected = false
        preferences = CapturePreferences(this)
        dispatcher = CaptureDispatcher(this)
        settingsObserver = preferences.observeChanges { dispatcher.cancelPending() }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationRecovery.gate.onConnected()
        CaptureServiceConnections.notificationConnected = true
        // Deliberately do not read activeNotifications: installation must not import old messages.
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val channel = PaymentChannel.fromPackage(sbn.packageName) ?: return
        if (!preferences.isCaptureEnabled(channel)) return
        try {
            val notification = sbn.notification ?: return
            val input = NotificationCaptureReader.read(sbn.packageName, notification,
                sbn.key, sbn.postTime, System.currentTimeMillis()) ?: return
            dispatcher.submit(input)
        } catch (_: RuntimeException) {
            CaptureDiagnostics.failed(CaptureFailure.NOTIFICATION_READ)
        }
    }

    override fun onListenerDisconnected() {
        CaptureServiceConnections.notificationConnected = false
        dispatcher.cancelPending()
        super.onListenerDisconnected()
        // An actual disconnect may be recoverable while our process remains alive. This shares
        // the foreground once-per-episode/30-second gate; unbind/destroy never start a retry loop.
        PermissionHelper.maybeRecoverNotificationsAfterDisconnect(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        CaptureServiceConnections.notificationConnected = false
        dispatcher.cancelPending()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        CaptureServiceConnections.notificationConnected = false
        settingsObserver?.close()
        dispatcher.close()
        super.onDestroy()
    }

}
