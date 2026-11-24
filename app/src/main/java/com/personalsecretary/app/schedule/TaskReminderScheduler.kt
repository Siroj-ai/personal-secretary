package com.personalsecretary.app.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.personalsecretary.app.data.SecretaryTask
import com.personalsecretary.app.data.TaskRecurrence
import java.time.Instant
import java.time.ZoneId

/**
 * Schedules and cancels exact-time ColorOS 17 / Android reminder notifications for work & personal tasks.
 * Falls back gracefully from exact to inexact alarms if exact-alarm permission is not enabled.
 */
object TaskReminderScheduler {

    const val EXTRA_TASK_ID = "extra_task_id"
    const val EXTRA_TASK_TITLE = "extra_task_title"
    const val EXTRA_TASK_NOTES = "extra_task_notes"
    const val EXTRA_TASK_CATEGORY = "extra_task_category"
    const val EXTRA_TASK_PRIORITY = "extra_task_priority"

    fun scheduleTaskReminder(context: Context, task: SecretaryTask) {
        val due = task.dueAtMillis ?: return
        if (task.isCompleted || !task.reminderEnabled || due <= System.currentTimeMillis()) {
            cancelTaskReminder(context, task.id)
            return
        }

        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = buildPendingIntent(context, task)

        runCatching {
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.canScheduleExactAlarms()
            } else {
                true
            }
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi)
            }
        }
    }

    fun cancelTaskReminder(context: Context, taskId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, TaskReminderReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { am.cancel(pi) }
    }

    fun rescheduleAllPending(context: Context, tasks: List<SecretaryTask>) {
        val now = System.currentTimeMillis()
        tasks.forEach { task ->
            val due = task.dueAtMillis
            if (!task.isCompleted && task.reminderEnabled && due != null && due > now) {
                scheduleTaskReminder(context, task)
            }
        }
    }

    fun computeNextOccurrenceMillis(
        dueAtMillis: Long,
        recurrence: TaskRecurrence,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long? {
        if (recurrence == TaskRecurrence.NONE) return null
        val zdt = Instant.ofEpochMilli(dueAtMillis).atZone(zone)
        val next = when (recurrence) {
            TaskRecurrence.DAILY -> zdt.plusDays(1)
            TaskRecurrence.WEEKLY -> zdt.plusWeeks(1)
            TaskRecurrence.MONTHLY -> zdt.plusMonths(1)
            TaskRecurrence.NONE -> return null
        }
        return next.toInstant().toEpochMilli()
    }

    private fun buildPendingIntent(context: Context, task: SecretaryTask): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, task.id)
            putExtra(EXTRA_TASK_TITLE, task.title)
            putExtra(EXTRA_TASK_NOTES, task.notes)
            putExtra(EXTRA_TASK_CATEGORY, task.category.label)
            putExtra(EXTRA_TASK_PRIORITY, task.priority.label)
        }
        return PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
