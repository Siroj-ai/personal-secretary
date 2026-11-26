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
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.personalsecretary.app.MainActivity
import com.personalsecretary.app.R
import com.personalsecretary.app.data.ExecutiveAnalyticsEngine
import com.personalsecretary.app.data.SecretaryDatabase
import com.personalsecretary.app.health.OnePlusStepTracker
import com.personalsecretary.app.market.NepalMarketClient
import com.personalsecretary.app.schedule.DeviceCalendarReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import java.util.concurrent.TimeUnit

class DailyBriefingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = SecretaryDatabase(applicationContext)
        val profile = db.loadProfile()

        // 1. Refresh Health Connect steps + watch vitals silently if background permission was granted
        val tracker = OnePlusStepTracker(applicationContext, db, CoroutineScope(Dispatchers.IO))
        tracker.syncStepsFromHealthConnect(days = 7)

        // 2. Refresh live Nepal IPOs & NEPSE secondary market snapshot from NepaliPaisa
        val marketClient = NepalMarketClient()
        val marketSnap = marketClient.fetchMarketSnapshot().getOrNull()?.also {
            db.saveMarketSnapshot(it)
        } ?: db.loadMarketSnapshot()

        val calReader = DeviceCalendarReader(applicationContext)
        val events = calReader.loadUpcomingEvents(daysAhead = 7)
        val tasks = db.loadTasks()
        val transactions = db.loadTransactions()

        val snapshot = ExecutiveAnalyticsEngine.computeSnapshot(
            transactions = transactions,
            ssf = db.loadSsfSummary(),
            earnings = db.loadEarnings(),
            hygiene = db.loadHygieneReport(),
            steps = db.loadStepRecords(60),
            hourlyStepsByDate = db.loadHourlySteps(),
            profile = profile,
            healthVitals = db.loadHealthVitals(),
            calendarEvents = events,
            tasks = tasks,
            emailAlerts = db.loadEmailAlerts(60),
            marketSnapshot = marketSnap
        )

        val cur = profile.currencySymbol
        val remainingSteps = (profile.dailyStepGoal - snapshot.todaySteps).coerceAtLeast(0L)

        // Notification 1: Executive Check-In -> opens Briefing
        val summary = buildString {
            append("Balance ${ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.debitBalance)}")
            if (snapshot.yesterdaySpend > 0.0) {
                append(" · Yesterday ${ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.yesterdaySpend)}")
            }
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
            channelId = CHANNEL_BRIEFING,
            channelName = "Executive Briefings",
            channelDesc = "Scheduled financial, schedule, and daily step check-ins",
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            notifId = NOTIF_ID_BRIEFING,
            targetTab = MainActivity.TAB_BRIEFING,
            title = "${profile.secretaryName} · Executive Check-In",
            body = summary
        )

        // Notification 2: Proactive Spending Advice (Tobacco/Alcohol, Budget Burn, or Uncategorized/Missing Details) -> opens Finances
        val spendingAlert = buildSpendingCoachAlert(snapshot, profile, transactions)
        if (spendingAlert != null) {
            postNotification(
                channelId = CHANNEL_SPENDING,
                channelName = "Spending & Clarity Alerts",
                channelDesc = "Tobacco/alcohol alerts, budget pace coaching, and prompts to clarify uncategorized entries",
                importance = NotificationManager.IMPORTANCE_HIGH,
                notifId = NOTIF_ID_SPENDING,
                targetTab = MainActivity.TAB_FINANCES,
                title = spendingAlert.first,
                body = spendingAlert.second
            )
        }

        // Notification 3: Nepal IPO & NEPSE Investment Opportunity (only when surplus cash is sufficient) -> opens Finances
        val investAlert = buildInvestmentOpportunityAlert(snapshot, profile)
        if (investAlert != null) {
            postNotification(
                channelId = CHANNEL_INVESTING,
                channelName = "Nepal IPO & NEPSE Radar",
                channelDesc = "Open/upcoming Nepal IPO alerts and promising liquid NEPSE stock setups when surplus cash is available",
                importance = NotificationManager.IMPORTANCE_DEFAULT,
                notifId = NOTIF_ID_INVESTING,
                targetTab = MainActivity.TAB_FINANCES,
                title = investAlert.first,
                body = investAlert.second
            )
        }

        return Result.success()
    }

    private fun buildSpendingCoachAlert(
        snapshot: com.personalsecretary.app.data.ExecutiveSnapshot,
        profile: com.personalsecretary.app.data.SecretaryProfile,
        transactions: List<com.personalsecretary.app.data.TransactionRecord>
    ): Pair<String, String>? {
        val cur = profile.currencySymbol
        val parts = mutableListOf<String>()

        if (snapshot.tobaccoAlcohol7d >= 200.0 || snapshot.tobaccoAlcohol30d >= 1200.0) {
            parts += "Tobacco & Alcohol hit ${ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.tobaccoAlcohol7d)} (7d) / ${ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.tobaccoAlcohol30d)} (30d) — cutting back 40% frees cash for IPOs."
        }
        if (snapshot.dailyBudgetAllowance > 0.0 && snapshot.avgDailySpend7d > snapshot.dailyBudgetAllowance * 1.15) {
            parts += "7-day daily avg is ${ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.avgDailySpend7d)}/day vs. your ${ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.dailyBudgetAllowance)}/day cap — aim for a low-spend reset day."
        }
        if (snapshot.budgetUsedPercent >= 85.0 && snapshot.currentMonthExpense > 0.0) {
            parts += "Active month spend is at ${snapshot.budgetUsedPercent.toInt()}% of your ${ExecutiveAnalyticsEngine.formatMoney(cur, profile.monthlySpendBudget)} budget — pause non-essential outflows."
        }
        if (snapshot.uncategorizedRecentCount > 0) {
            val latestUncat = transactions
                .filter { it.category.equals("Uncategorized", ignoreCase = true) }
                .maxByOrNull { it.dateIso }
                ?.normalizedDescription
                ?.take(28)
                ?.takeIf { it.isNotBlank() }
                ?.let { " (e.g. '$it')" }
                .orEmpty()
            parts += "${snapshot.uncategorizedRecentCount} recent entries (${ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.uncategorizedRecentAmount)})$latestUncat are uncategorized — tap to clarify what you spent/received."
        } else if (snapshot.hygieneReport.balanceAnomalies.isNotEmpty()) {
            val gap = snapshot.hygieneReport.balanceAnomalies.last()
            parts += "Missing income/expense gap of ${ExecutiveAnalyticsEngine.formatSignedMoney(cur, gap.difference)} detected on ${gap.dateIso} — tap to update."
        }

        if (parts.isEmpty()) return null
        val title = when {
            snapshot.tobaccoAlcohol7d >= 200.0 || snapshot.tobaccoAlcohol30d >= 1200.0 ->
                "${profile.secretaryName} · Spending & Habit Advice"
            snapshot.uncategorizedRecentCount > 0 ->
                "${profile.secretaryName} · Clarify Recent Spending Details"
            else ->
                "${profile.secretaryName} · Monthly Budget Alert"
        }
        return title to parts.joinToString(" ")
    }

    private fun buildInvestmentOpportunityAlert(
        snapshot: com.personalsecretary.app.data.ExecutiveSnapshot,
        profile: com.personalsecretary.app.data.SecretaryProfile
    ): Pair<String, String>? {
        val mkt = snapshot.marketSnapshot
        if (snapshot.investableSurplus < 2000.0 || !mkt.hasMarketData) return null
        val cur = profile.currencySymbol

        val openGeneral = mkt.openIpos.firstOrNull { it.isGeneralPublic }
        val upcomingGeneral = mkt.upcomingIpos.firstOrNull { it.isGeneralPublic }
        val topStocks = mkt.promisingStocks.take(2)
        val stocksSummary = if (topStocks.isNotEmpty()) {
            " Secondary watch: " + topStocks.joinToString(", ") {
                "${it.symbol} (${ExecutiveAnalyticsEngine.formatMoney(cur, it.ltp)}, ${String.format(Locale.US, "%+.1f%%", it.percentChange)})"
            } + "."
        } else ""

        return when {
            openGeneral != null -> {
                "${profile.secretaryName} · Open Nepal IPO (${openGeneral.stockSymbol})" to
                    "Surplus ${ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.investableSurplus)} available! ${openGeneral.companyName} (${openGeneral.sectorName}) is OPEN until ${openGeneral.closingDateAd} (min ${ExecutiveAnalyticsEngine.formatMoney(cur, openGeneral.minInvestmentNpr)}).$stocksSummary Tap to review."
            }
            upcomingGeneral != null -> {
                "${profile.secretaryName} · Upcoming IPO (${upcomingGeneral.stockSymbol})" to
                    "Surplus ${ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.investableSurplus)} ready. ${upcomingGeneral.companyName} opens ${upcomingGeneral.openingDateAd} (min ${ExecutiveAnalyticsEngine.formatMoney(cur, upcomingGeneral.minInvestmentNpr)}).$stocksSummary Tap to inspect."
            }
            topStocks.isNotEmpty() -> {
                "${profile.secretaryName} · NEPSE Investment Ideas" to
                    "You have ${ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.investableSurplus)} investable surplus.$stocksSummary Tap to review liquid NEPSE setups."
            }
            else -> null
        }
    }

    private fun postNotification(
        channelId: String,
        channelName: String,
        channelDesc: String,
        importance: Int,
        notifId: Int,
        targetTab: String,
        title: String,
        body: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, channelName, importance).apply {
            description = channelDesc
        }
        nm.createNotificationChannel(channel)

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, targetTab)
        }
        val contentPi = PendingIntent.getActivity(
            applicationContext,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val priority = if (importance >= NotificationManager.IMPORTANCE_HIGH) {
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_DEFAULT
        }

        val notif = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(notifId, notif)
        }
    }

    companion object {
        private const val CHANNEL_BRIEFING = "executive_briefings"
        private const val CHANNEL_SPENDING = "spending_coach_alerts"
        private const val CHANNEL_INVESTING = "nepal_ipo_nepse_alerts"
        private const val NOTIF_ID_BRIEFING = 4102
        private const val NOTIF_ID_SPENDING = 4103
        private const val NOTIF_ID_INVESTING = 4104
        private const val WORK_NAME = "personal_secretary_periodic_briefing"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<DailyBriefingWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<DailyBriefingWorker>().build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
