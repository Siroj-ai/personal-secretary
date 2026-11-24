package com.personalsecretary.app.schedule

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.personalsecretary.app.MainActivity
import com.personalsecretary.app.R
import com.personalsecretary.app.data.SecretaryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TaskReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(TaskReminderScheduler.EXTRA_TASK_ID) ?: return
        val title = intent.getStringExtra(TaskReminderScheduler.EXTRA_TASK_TITLE) ?: "Scheduled Reminder"
        val notes = intent.getStringExtra(TaskReminderScheduler.EXTRA_TASK_NOTES).orEmpty()
        val category = intent.getStringExtra(TaskReminderScheduler.EXTRA_TASK_CATEGORY) ?: "Work"
        val priority = intent.getStringExtra(TaskReminderScheduler.EXTRA_TASK_PRIORITY) ?: "High"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Schedule & Work Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Exact-time alerts for work tasks, meetings, and bill due dates"
        }
        nm.createNotificationChannel(channel)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = if (notes.isNotBlank()) {
            "[$category · $priority] $notes"
        } else {
            "$category reminder ($priority priority) — tap to review with your secretary."
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Clara · $title")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(taskId.hashCode(), notif)
        }
    }

    companion object {
        private const val CHANNEL_ID = "secretary_reminders"
    }
}

class BootReminderRescheduler : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = SecretaryDatabase(context.applicationContext)
                val tasks = db.loadTasks()
                TaskReminderScheduler.rescheduleAllPending(context.applicationContext, tasks)
            } finally {
                pending.finish()
            }
        }
    }
}
