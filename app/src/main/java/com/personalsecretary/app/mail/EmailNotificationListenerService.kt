package com.personalsecretary.app.mail

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.personalsecretary.app.data.SecretaryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Listens strictly to allowlisted email apps (Gmail, Outlook, etc.) locally on-device,
 * strips OTP/security codes via [EmailNotificationClassifier], and stores alerts in private SQLite.
 */
class EmailNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var database: SecretaryDatabase

    override fun onCreate() {
        super.onCreate()
        database = SecretaryDatabase(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val statusBar = sbn ?: return
        val pkg = statusBar.packageName ?: return
        if (!EmailNotificationClassifier.isAllowedEmailPackage(pkg)) return

        val notification = statusBar.notification ?: return
        // Skip grouped summary headers and ongoing syncing notifications
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        val parsed = EmailNotificationClassifier.parseEmailNotification(
            packageName = pkg,
            title = title,
            text = text,
            bigText = bigText,
            subText = subText,
            postTimeMillis = statusBar.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
        ) ?: return

        scope.launch {
            runCatching {
                database.upsertEmailAlert(parsed)
                EmailNotificationEvents.notifyUpdated()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
