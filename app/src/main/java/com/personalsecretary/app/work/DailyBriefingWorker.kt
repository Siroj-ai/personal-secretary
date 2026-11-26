package com.personalsecretary.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.personalsecretary.app.MainActivity
import com.personalsecretary.app.R
import com.personalsecretary.app.data.ExecutiveAnalyticsEngine
import com.personalsecretary.app.data.SecretaryDatabase
import com.personalsecretary.app.health.OnePlusStepTracker
import com.personalsecretary.app.schedule.DeviceCalendarReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit

class DailyBriefingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = SecretaryDatabase(applicationContext)
        val profile = db.loadProfile()

        // Try refreshing Health Connect steps + watch vitals silently if background permission was granted
        val tracker = OnePlusStepTracker(applicationContext, db, CoroutineScope(Dispatchers.IO))
        tracker.syncStepsFromHealthConnect(days = 7)

        val calReader = DeviceCalendarReader(applicationContext)
        val events = calReader.loadUpcomingEvents(daysAhead = 7)
        val tasks = db.loadTasks()

        val snapshot = ExecutiveAnalyticsEngine.computeSnapshot(
            transactions = db.loadTransactions(),
            ssf = db.loadSsfSummary(),
            earnings = db.loadEarnings(),
            hygiene = db.loadHygieneReport(),
            steps = db.loadStepRecords(60),
            hourlyStepsByDate = db.loadHourlySteps(),
            profile = profile,
            healthVitals = db.loadHealthVitals(),
            calendarEvents = events,
            tasks = tasks,
            emailAlerts = db.loadEmailAlerts(60)
        )

        val remainingSteps = (profile.dailyStepGoal - snapshot.todaySteps).coerceAtLeast(0L)
        val summary = buildString {
            append("Balance ${ExecutiveAnalyticsEngine.formatMoney(profile.currencySymbol, snapshot.debitBalance)}")
            append(" · Steps ${snapshot.todaySteps}/${profile.dailyStepGoal}")
            if (remainingSteps > 0L) append(" ($remainingSteps to go)")
            if (snapshot.todayEvents.isNotEmpty() || snapshot.pendingTasks.isNotEmpty()) {
                append(" · ${snapshot.todayEvents.size} events, ${snapshot.pendingTasks.size} open tasks")
            }
            if (snapshot.unreadEmailsCount > 0) {
                append(" · ${snapshot.unreadEmailsCount} unread email${if (snapshot.unreadEmailsCount == 1) "" else "s"}")
            }
        }
        postNotification(
            title = "${profile.secretaryName} · Executive Check-In",
            body = summary
        )
        return Result.success()
    }

    private fun postNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val channelId = CHANNEL_ID
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "Executive Briefings",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Scheduled financial and daily step briefings"
        }
        nm.createNotificationChannel(channel)

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_BRIEFING)
        }
        val contentPi = PendingIntent.getActivity(
            applicationContext,
            NOTIF_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val CHANNEL_ID = "executive_briefings"
        private const val NOTIF_ID = 4102
        private const val WORK_NAME = "personal_secretary_periodic_briefing"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<DailyBriefingWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
