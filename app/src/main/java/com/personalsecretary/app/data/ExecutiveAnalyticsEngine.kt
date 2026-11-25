package com.personalsecretary.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object ExecutiveAnalyticsEngine {

    private val isoFmt = DateTimeFormatter.ISO_LOCAL_DATE
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val shortDateOnlyFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val shortDateTimeFmt = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

    fun computeSnapshot(
        transactions: List<TransactionRecord>,
        ssf: SsfSummary,
        earnings: List<EarningEntry>,
        hygiene: FinancialHygieneReport,
        steps: List<DailyStepRecord>,
        hourlyStepsByDate: Map<String, List<Long>>,
        profile: SecretaryProfile,
        today: LocalDate = LocalDate.now(),
        healthVitals: HealthVitalsSummary = HealthVitalsSummary(),
        calendarEvents: List<CalendarEventItem> = emptyList(),
        tasks: List<SecretaryTask> = emptyList(),
        emailAlerts: List<EmailAlertItem> = emptyList(),
        nowMillis: Long = System.currentTimeMillis()
    ): ExecutiveSnapshot {
        val todayIso = today.format(isoFmt)
        val yesterdayIso = today.minusDays(1).format(isoFmt)
        val currentMonthKey = todayIso.substring(0, 7)

        val debitTxns = transactions.filter { it.accountType == AccountType.DEBIT }
        val creditTxns = transactions.filter { it.accountType == AccountType.CREDIT }

        val debitBalance = debitTxns.lastOrNull()?.balance ?: 0.0
        val creditBalance = creditTxns.lastOrNull()?.balance ?: 0.0
        val earningsTotal = earnings.sumOf { it.amount }
        val earningsMonthLabel = earnings.firstOrNull()?.monthLabel.orEmpty()

        val netWorth = DayBookParserAndHygiene.round2(debitBalance + creditBalance + ssf.netEquity)

        val latestTxDate = debitTxns.lastOrNull()?.dateIso?.takeIf { it.length == 10 && it != "0000-00-00" }
        val anchorDate = runCatching { LocalDate.parse(latestTxDate ?: todayIso, isoFmt) }.getOrDefault(today)
        val activeMonthKey = anchorDate.format(isoFmt).substring(0, 7)
        val active30Start = anchorDate.minusDays(30).format(isoFmt)

        val monthTxns = debitTxns.filter { it.dateIso.startsWith(activeMonthKey) }
        val currentMonthIncome = DayBookParserAndHygiene.round2(monthTxns.sumOf { it.credit })
        val currentMonthExpense = DayBookParserAndHygiene.round2(monthTxns.sumOf { it.debit })

        val txns30d = debitTxns.filter { it.dateIso >= active30Start && it.dateIso != "0000-00-00" }
        val last30Income = DayBookParserAndHygiene.round2(txns30d.sumOf { it.credit })
        val last30Expense = DayBookParserAndHygiene.round2(txns30d.sumOf { it.debit })
        val dailyBurn30d = DayBookParserAndHygiene.round2(last30Expense / 30.0)
        val budgetUsedPct = if (profile.monthlySpendBudget > 0.0) {
            DayBookParserAndHygiene.round2((currentMonthExpense / profile.monthlySpendBudget) * 100.0)
        } else 0.0

        val catMap = mutableMapOf<String, Pair<Double, Int>>()
        var tobaccoAlcohol30d = 0.0
        for (tx in txns30d) {
            if (tx.debit <= 0.0) continue
            val cat = tx.category.ifBlank { "Uncategorized" }
            val prev = catMap[cat] ?: (0.0 to 0)
            catMap[cat] = (prev.first + tx.debit) to (prev.second + 1)
            if (cat.equals("Tobacco & Alcohol", ignoreCase = true)) {
                tobaccoAlcohol30d += tx.debit
            }
        }
        val topCategories = catMap.entries
            .sortedByDescending { it.value.first }
            .take(6)
            .map { (cat, pair) ->
                val share = if (last30Expense > 0.0) (pair.first / last30Expense) * 100.0 else 0.0
                CategorySpend(
                    category = cat,
                    amount = DayBookParserAndHygiene.round2(pair.first),
                    count = pair.second,
                    sharePercent = DayBookParserAndHygiene.round2(share)
                )
            }

        val monthlyFlows = debitTxns
            .filter { it.dateIso.length >= 7 && it.dateIso != "0000-00-00" }
            .groupBy { it.dateIso.substring(0, 7) }
            .entries
            .sortedBy { it.key }
            .takeLast(6)
            .map { (m, list) ->
                MonthlyCashFlow(
                    monthKey = m,
                    income = DayBookParserAndHygiene.round2(list.sumOf { it.credit }),
                    expense = DayBookParserAndHygiene.round2(list.sumOf { it.debit })
                )
            }

        // Physical steps summary
        val stepsByDate = steps.associateBy { it.dateIso }
        val recent7d = (6 downTo 0).map { offset ->
            val d = today.minusDays(offset.toLong()).format(isoFmt)
            stepsByDate[d] ?: DailyStepRecord(d, 0L, StepSource.HEALTH_CONNECT, 0L)
        }
        val todayStepRec = stepsByDate[todayIso] ?: recent7d.last()
        val yesterdaySteps = stepsByDate[yesterdayIso]?.steps ?: 0L
        val vsYesterdayPct = if (yesterdaySteps > 0L) {
            (((todayStepRec.steps - yesterdaySteps).toDouble() / yesterdaySteps.toDouble()) * 100.0).roundToInt()
        } else null

        val weeklyTotal = recent7d.sumOf { it.steps }
        val nonZeroDays = recent7d.filter { it.steps > 0L }
        val avgSteps7d = if (nonZeroDays.isNotEmpty()) nonZeroDays.map { it.steps }.average().toLong() else 0L

        var streak = 0
        for (offset in 0..30) {
            val d = today.minusDays(offset.toLong()).format(isoFmt)
            val s = stepsByDate[d]?.steps ?: 0L
            if (s >= profile.dailyStepGoal) {
                streak++
            } else if (offset > 0) {
                break
            }
        }

        val todayHourly = hourlyStepsByDate[todayIso].orEmpty()
        val peakHour = todayHourly
            .withIndex()
            .maxByOrNull { it.value }
            ?.takeIf { it.value > 0L }
            ?.index

        val todayMetrics = StepDerivedMetrics.fromSteps(todayStepRec.steps)

        // Schedule & Work Tasks summary
        val zone = ZoneId.systemDefault()
        val todayStartMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowStartMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val todayEvents = calendarEvents.filter { ev ->
            ev.startMillis < tomorrowStartMillis && ev.endMillis > todayStartMillis
        }.sortedBy { it.startMillis }

        val upcomingEvents7d = calendarEvents.filter { ev ->
            ev.endMillis >= nowMillis
        }.sortedBy { it.startMillis }

        val pendingTasks = tasks.filter { !it.isCompleted }.sortedWith(
            compareBy<SecretaryTask> { it.dueAtMillis == null }
                .thenBy { it.dueAtMillis ?: Long.MAX_VALUE }
                .thenBy { it.priority.ordinal }
        )
        val overdueTasksCount = pendingTasks.count { t ->
            val due = t.dueAtMillis
            due != null && due < nowMillis
        }
        val dueTodayTasksCount = pendingTasks.count { t ->
            val due = t.dueAtMillis
            due != null && due >= todayStartMillis && due < tomorrowStartMillis
        }

        val recentEmails = emailAlerts.sortedByDescending { it.receivedAtMillis }.take(40)
        val unreadEmailsCount = recentEmails.count { !it.isRead }
        val urgentEmailsCount = recentEmails.count { !it.isRead && it.isUrgent }

        val insights = buildInsights(
            debitBalance = debitBalance,
            creditBalance = creditBalance,
            ssf = ssf,
            currentMonthExpense = currentMonthExpense,
            last30Expense = last30Expense,
            tobaccoAlcohol30d = DayBookParserAndHygiene.round2(tobaccoAlcohol30d),
            hygiene = hygiene,
            todaySteps = todayStepRec.steps,
            avgSteps7d = avgSteps7d,
            todayMetrics = todayMetrics,
            healthVitals = healthVitals,
            todayEvents = todayEvents,
            pendingTasks = pendingTasks,
            overdueTasksCount = overdueTasksCount,
            dueTodayTasksCount = dueTodayTasksCount,
            recentEmails = recentEmails,
            unreadEmailsCount = unreadEmailsCount,
            urgentEmailsCount = urgentEmailsCount,
            profile = profile
        )

        return ExecutiveSnapshot(
            debitBalance = debitBalance,
            creditCardBalance = creditBalance,
            ssfSummary = ssf,
            earningsTotal = DayBookParserAndHygiene.round2(earningsTotal),
            earningsMonthLabel = earningsMonthLabel,
            netWorthEstimate = netWorth,
            currentMonthKey = activeMonthKey.ifBlank { currentMonthKey },
            currentMonthIncome = currentMonthIncome,
            currentMonthExpense = currentMonthExpense,
            last30DaysIncome = last30Income,
            last30DaysExpense = last30Expense,
            dailyBurnRate30d = dailyBurn30d,
            budgetUsedPercent = budgetUsedPct,
            tobaccoAlcohol30d = DayBookParserAndHygiene.round2(tobaccoAlcohol30d),
            topCategories30d = topCategories,
            monthlyCashFlows = monthlyFlows,
            hygieneReport = hygiene,
            todaySteps = todayStepRec.steps,
            todayStepSource = todayStepRec.source,
            todayMetrics = todayMetrics,
            stepsVsYesterdayPercent = vsYesterdayPct,
            avgSteps7d = avgSteps7d,
            weeklyStepsTotal = weeklyTotal,
            stepStreakDays = streak,
            recentSteps7d = recent7d,
            hourlyStepsByDate = hourlyStepsByDate,
            peakHourToday = peakHour,
            healthVitals = healthVitals,
            todayEvents = todayEvents,
            upcomingEvents7d = upcomingEvents7d,
            pendingTasks = pendingTasks,
            overdueTasksCount = overdueTasksCount,
            dueTodayTasksCount = dueTodayTasksCount,
            recentEmails = recentEmails,
            unreadEmailsCount = unreadEmailsCount,
            urgentEmailsCount = urgentEmailsCount,
            insights = insights
        )
    }

    private fun buildInsights(
        debitBalance: Double,
        creditBalance: Double,
        ssf: SsfSummary,
        currentMonthExpense: Double,
        last30Expense: Double,
        tobaccoAlcohol30d: Double,
        hygiene: FinancialHygieneReport,
        todaySteps: Long,
        avgSteps7d: Long,
        todayMetrics: StepDerivedMetrics,
        healthVitals: HealthVitalsSummary,
        todayEvents: List<CalendarEventItem>,
        pendingTasks: List<SecretaryTask>,
        overdueTasksCount: Int,
        dueTodayTasksCount: Int,
        recentEmails: List<EmailAlertItem>,
        unreadEmailsCount: Int,
        urgentEmailsCount: Int,
        profile: SecretaryProfile
    ): List<ExecutiveInsight> {
        val cur = profile.currencySymbol
        val list = mutableListOf<ExecutiveInsight>()

        if (urgentEmailsCount > 0) {
            val topMail = recentEmails.firstOrNull { !it.isRead && it.isUrgent }
            list += ExecutiveInsight(
                id = "emails_urgent",
                domain = InsightDomain.EMAIL,
                severity = InsightSeverity.CAUTION,
                title = "$urgentEmailsCount Urgent Email Alert${if (urgentEmailsCount > 1) "s" else ""}",
                detail = topMail?.let { "${it.sender}: '${it.subject}' (${it.tag.label})." }
                    ?: "Urgent incoming email alerts are waiting in your Inbox.",
                recommendation = "Review in Schedule -> Email Inbox and tap 'Add Task' on anything requiring follow-up."
            )
        } else if (unreadEmailsCount > 0) {
            val topMail = recentEmails.firstOrNull { !it.isRead }
            list += ExecutiveInsight(
                id = "emails_unread",
                domain = InsightDomain.EMAIL,
                severity = InsightSeverity.INFO,
                title = "$unreadEmailsCount Unread Inbox Alert${if (unreadEmailsCount > 1) "s" else ""}",
                detail = topMail?.let { "Latest from ${it.sender}: '${it.subject}'." }
                    ?: "New incoming email alerts captured on-device.",
                recommendation = "Ask Clara to summarize your inbox or convert key bills/requests into scheduled tasks."
            )
        }

        if (overdueTasksCount > 0) {
            val first = pendingTasks.firstOrNull { it.dueAtMillis != null }
            list += ExecutiveInsight(
                id = "tasks_overdue",
                domain = InsightDomain.SCHEDULE,
                severity = InsightSeverity.CAUTION,
                title = "$overdueTasksCount Overdue Work / Schedule Item${if (overdueTasksCount > 1) "s" else ""}",
                detail = if (first != null) "Top overdue item: '${first.title}' (${first.category.label} · ${first.priority.label})." else "You have overdue tasks awaiting attention.",
                recommendation = "Clear or reschedule overdue items in the Schedule tab before starting new tasks."
            )
        } else if (todayEvents.isNotEmpty() || dueTodayTasksCount > 0) {
            list += ExecutiveInsight(
                id = "schedule_today",
                domain = InsightDomain.SCHEDULE,
                severity = InsightSeverity.INFO,
                title = "Today's Desk: ${todayEvents.size} Event${if (todayEvents.size == 1) "" else "s"} · $dueTodayTasksCount Due Task${if (dueTodayTasksCount == 1) "" else "s"}",
                detail = todayEvents.firstOrNull()?.let { "Next on calendar: ${it.title} (${formatEventTimeRange(it)})." }
                    ?: "Open the Schedule tab to review today's priorities and reminders.",
                recommendation = "Time-block your top priority work item between calendar commitments."
            )
        }

        if (healthVitals.lastNightSleepMinutes in 1..359) {
            list += ExecutiveInsight(
                id = "sleep_short",
                domain = InsightDomain.PHYSICAL,
                severity = InsightSeverity.CAUTION,
                title = "Short Sleep Logged (${formatDurationHoursMins(healthVitals.lastNightSleepMinutes)})",
                detail = "Last night's tracked sleep was under 6 hours (7-day avg: ${formatDurationHoursMins(healthVitals.avgSleepMinutes7d)}).",
                recommendation = "Keep today's schedule focused on essential work and aim for an earlier wind-down tonight."
            )
        } else if (healthVitals.lastNightSleepMinutes >= 420) {
            list += ExecutiveInsight(
                id = "sleep_strong",
                domain = InsightDomain.PHYSICAL,
                severity = InsightSeverity.POSITIVE,
                title = "Solid Recovery Sleep (${formatDurationHoursMins(healthVitals.lastNightSleepMinutes)})",
                detail = buildString {
                    append("Well-rested at ${formatDurationHoursMins(healthVitals.lastNightSleepMinutes)}.")
                    healthVitals.restingHeartRateBpm?.let { append(" Resting HR: $it bpm.") }
                },
                recommendation = "Great recovery baseline for deep work and hitting your daily step goal."
            )
        }

        if (hygiene.duplicatesRemoved > 0) {
            list += ExecutiveInsight(
                id = "hygiene_dups",
                domain = InsightDomain.FINANCIAL,
                severity = InsightSeverity.INFO,
                title = "${hygiene.duplicatesRemoved} Duplicate Sheet Rows Filtered",
                detail = "Your Day Book contained ${hygiene.rawDebitRows} raw rows; your secretary isolated ${hygiene.uniqueDebitRows} unique transactions for accurate reporting.",
                recommendation = "Deduplicate the source Google Sheet so formulas and charts stay accurate."
            )
        }

        if (hygiene.balanceAnomalies.isNotEmpty()) {
            val latest = hygiene.balanceAnomalies.last()
            list += ExecutiveInsight(
                id = "balance_gap",
                domain = InsightDomain.FINANCIAL,
                severity = InsightSeverity.CAUTION,
                title = "Missing Entry Detected (${latest.dateIso})",
                detail = "Running balance shifted by ${formatSignedMoney(cur, latest.difference)} near '${latest.description}' (expected ${formatMoney(cur, latest.expectedBalance)}, recorded ${formatMoney(cur, latest.actualBalance)}).",
                recommendation = "Check your bank statement for ${latest.dateIso} to log the missing credit/debit."
            )
        }

        if (profile.monthlySpendBudget > 0 && currentMonthExpense > 0) {
            val pct = (currentMonthExpense / profile.monthlySpendBudget * 100.0).roundToInt()
            if (pct >= 85) {
                list += ExecutiveInsight(
                    id = "budget_pace",
                    domain = InsightDomain.FINANCIAL,
                    severity = InsightSeverity.CAUTION,
                    title = "Monthly Budget at $pct%",
                    detail = "Spending for the active month is ${formatMoney(cur, currentMonthExpense)} against your ${formatMoney(cur, profile.monthlySpendBudget)} cap.",
                    recommendation = "Freeze non-essential discretionary expenses for the next 5 days."
                )
            } else {
                list += ExecutiveInsight(
                    id = "budget_healthy",
                    domain = InsightDomain.FINANCIAL,
                    severity = InsightSeverity.POSITIVE,
                    title = "Budget Discipline On Track ($pct%)",
                    detail = "Active month spend is ${formatMoney(cur, currentMonthExpense)} vs. ${formatMoney(cur, profile.monthlySpendBudget)} target with ${formatMoney(cur, debitBalance)} liquid cash.",
                    recommendation = "Keep daily discretionary outflow under ${formatMoney(cur, (profile.monthlySpendBudget - currentMonthExpense).coerceAtLeast(0.0) / 10.0)}."
                )
            }
        }

        if (tobaccoAlcohol30d > 2000.0 && last30Expense > 0) {
            list += ExecutiveInsight(
                id = "habit_spend",
                domain = InsightDomain.CROSS_DOMAIN,
                severity = InsightSeverity.CAUTION,
                title = "Tobacco & Alcohol Spend: ${formatMoney(cur, tobaccoAlcohol30d)} (30d)",
                detail = "Habit spending represents ${String.format(Locale.US, "%.1f", (tobaccoAlcohol30d / last30Expense) * 100.0)}% of your 30-day debit outflow.",
                recommendation = "Cutting this by 30% preserves ~${formatMoney(cur, tobaccoAlcohol30d * 0.3)}/month and directly supports stamina and walking recovery."
            )
        }

        if (ssf.refundableAmount > 0 && ssf.totalDueAmount > 0) {
            list += ExecutiveInsight(
                id = "ssf_position",
                domain = InsightDomain.FINANCIAL,
                severity = InsightSeverity.POSITIVE,
                title = "SSF Net Equity: ${formatMoney(cur, ssf.netEquity)}",
                detail = "Savings (${formatMoney(cur, ssf.refundableAmount)}) exceed Special Loan due (${formatMoney(cur, ssf.totalDueAmount)}), and accrued interest beats loan interest by ${formatMoney(cur, ssf.interestSpread)}.",
                recommendation = if (ssf.refErrorDetected) "Note: Sheet cell K3 has #REF!; calculated accurately here." else "Maintain regular SSF contributions while servicing Credit Card balance (${formatMoney(cur, abs(creditBalance))})."
            )
        }

        val stepPct = if (profile.dailyStepGoal > 0) (todaySteps * 100 / profile.dailyStepGoal).toInt() else 0
        if (todaySteps == 0L && avgSteps7d == 0L) {
            list += ExecutiveInsight(
                id = "steps_setup",
                domain = InsightDomain.PHYSICAL,
                severity = InsightSeverity.INFO,
                title = "Connect OnePlus 13 Steps",
                detail = "Grant Health Connect or Physical Activity permission so your secretary can monitor daily movement automatically.",
                recommendation = "Open Health -> Health & Watch to authorize Health Connect and enable ColorOS 17 background activity."
            )
        } else if (stepPct >= 100) {
            list += ExecutiveInsight(
                id = "steps_hit",
                domain = InsightDomain.PHYSICAL,
                severity = InsightSeverity.POSITIVE,
                title = "Daily Step Goal Achieved ($todaySteps steps)",
                detail = "Covered ${String.format(Locale.US, "%.2f", todayMetrics.distanceKm)} km (~${todayMetrics.caloriesKcal} kcal, ${todayMetrics.activeMinutes} active mins). 7-day avg: $avgSteps7d steps.",
                recommendation = "Great physical discipline today — stay hydrated and keep the streak alive tomorrow."
            )
        } else {
            val remaining = (profile.dailyStepGoal - todaySteps).coerceAtLeast(0L)
            list += ExecutiveInsight(
                id = "steps_pace",
                domain = InsightDomain.PHYSICAL,
                severity = InsightSeverity.INFO,
                title = "$remaining Steps Remaining Today",
                detail = "Currently at $todaySteps / ${profile.dailyStepGoal} steps (${String.format(Locale.US, "%.2f", todayMetrics.distanceKm)} km, ${todayMetrics.caloriesKcal} kcal). A brisk 20-minute walk adds ~2,000 steps.",
                recommendation = "Schedule a short walk after your next work block to stay on pace for ${profile.dailyStepGoal} steps."
            )
        }

        return list.take(6)
    }

    fun buildGemmaDossier(
        snapshot: ExecutiveSnapshot,
        profile: SecretaryProfile,
        recentTransactions: List<TransactionRecord>
    ): String {
        val c = profile.currencySymbol
        val sb = StringBuilder()
        sb.appendLine("=== EXECUTIVE CLIENT DOSSIER (LIVE VERIFIED METRICS) ===")
        sb.appendLine("Client: ${profile.userName} | Currency: $c | Active Month: ${snapshot.currentMonthKey}")
        sb.appendLine("Authoritative Targets: Monthly Spend Budget = ${formatMoney(c, profile.monthlySpendBudget)} | Daily Step Goal = ${profile.dailyStepGoal} steps")
        sb.appendLine("Financial Priorities: ${profile.financialGoals}")
        sb.appendLine("Physical Activity Priorities: ${profile.healthGoals}")
        if (profile.customDossierNotes.isNotBlank()) {
            sb.appendLine("Client Notes: ${profile.customDossierNotes}")
        }
        sb.appendLine()
        sb.appendLine("[1. FINANCIAL HEALTH - DAY BOOK (DEDUPLICATED)]")
        sb.appendLine("- Liquid Debit Balance: ${formatMoney(c, snapshot.debitBalance)}")
        sb.appendLine("- Credit Card Balance: ${formatMoney(c, snapshot.creditCardBalance)}")
        if (snapshot.ssfSummary.refundableAmount > 0) {
            sb.appendLine("- SSF Savings: ${formatMoney(c, snapshot.ssfSummary.refundableAmount)} | Special Loan Due: ${formatMoney(c, snapshot.ssfSummary.totalDueAmount)} | Net Equity: ${formatMoney(c, snapshot.ssfSummary.netEquity)} | Interest Spread: +${formatMoney(c, snapshot.ssfSummary.interestSpread)}")
        }
        if (snapshot.earningsTotal > 0) {
            sb.appendLine("- Extra Earnings (${snapshot.earningsMonthLabel}): ${formatMoney(c, snapshot.earningsTotal)}")
        }
        sb.appendLine("- Active Month (${snapshot.currentMonthKey}): Income ${formatMoney(c, snapshot.currentMonthIncome)}, Expense ${formatMoney(c, snapshot.currentMonthExpense)} (${snapshot.budgetUsedPercent}% of ${formatMoney(c, profile.monthlySpendBudget)} budget)")
        sb.appendLine("- Rolling 30-Day: Income ${formatMoney(c, snapshot.last30DaysIncome)}, Expense ${formatMoney(c, snapshot.last30DaysExpense)}, Daily Burn ${formatMoney(c, snapshot.dailyBurnRate30d)}/day")
        if (snapshot.tobaccoAlcohol30d > 0) {
            sb.appendLine("- Tobacco & Alcohol (30d): ${formatMoney(c, snapshot.tobaccoAlcohol30d)}")
        }
        if (snapshot.topCategories30d.isNotEmpty()) {
            val cats = snapshot.topCategories30d.joinToString("; ") {
                "${it.category}: ${formatMoney(c, it.amount)} (${it.sharePercent}%)"
            }
            sb.appendLine("- Top Spend Categories (30d): $cats")
        }
        if (snapshot.hygieneReport.duplicatesRemoved > 0 || snapshot.hygieneReport.balanceAnomalies.isNotEmpty()) {
            sb.appendLine("- Data Hygiene: ${snapshot.hygieneReport.duplicatesRemoved} duplicate rows filtered; ${snapshot.hygieneReport.balanceAnomalies.size} balance gaps flagged.")
        }
        if (recentTransactions.isNotEmpty()) {
            sb.appendLine("- Latest 6 Transactions:")
            recentTransactions.takeLast(6).reversed().forEach { tx ->
                val kind = if (tx.debit > 0) "Debit ${formatMoney(c, tx.debit)}" else "Credit ${formatMoney(c, tx.credit)}"
                sb.appendLine("  * ${tx.dateIso} | ${tx.normalizedDescription.ifBlank { "(blank)" }} | $kind | Bal ${formatMoney(c, tx.balance)} | ${tx.category}")
            }
        }
        sb.appendLine()
        sb.appendLine("[2. PHYSICAL HEALTH & WATCH VITALS (ONEPLUS 13 / HEALTH CONNECT)]")
        sb.appendLine("- Today Steps: ${snapshot.todaySteps} / ${profile.dailyStepGoal} steps | ${String.format(Locale.US, "%.2f", snapshot.todayMetrics.distanceKm)} km | ${snapshot.todayMetrics.caloriesKcal} kcal | ${snapshot.todayMetrics.activeMinutes} active mins")
        sb.appendLine("- 7-Day Total: ${snapshot.weeklyStepsTotal} | 7-Day Average: ${snapshot.avgSteps7d} steps/day | Goal Streak: ${snapshot.stepStreakDays} days")
        snapshot.peakHourToday?.let { hr ->
            sb.appendLine("- Peak Activity Hour Today: ${formatHourLabel(hr)}")
        }
        val stepSeries = snapshot.recentSteps7d.joinToString(", ") { "${it.dateIso.takeLast(5)}:${it.steps}" }
        sb.appendLine("- Last 7 Days: $stepSeries")
        val vitals = snapshot.healthVitals
        if (vitals.hasAnyWatchData) {
            val parts = buildList {
                if (vitals.lastNightSleepMinutes > 0) add("Sleep last night ${formatDurationHoursMins(vitals.lastNightSleepMinutes)} (7d avg ${formatDurationHoursMins(vitals.avgSleepMinutes7d)})")
                vitals.restingHeartRateBpm?.let { add("Resting HR $it bpm") }
                vitals.latestHeartRateBpm?.let { add("Latest HR $it bpm") }
                vitals.latestSpo2Percent?.let { add("SpO2 ${String.format(Locale.US, "%.1f", it)}%") }
                if (vitals.workoutsThisWeek > 0) add("Workouts 7d: ${vitals.workoutsThisWeek} (${vitals.workoutMinutesThisWeek} min)")
            }
            sb.appendLine("- Watch Vitals: ${parts.joinToString(" | ")}")
        }

        sb.appendLine()
        sb.appendLine("[3. SCHEDULE, MEETINGS & WORK TASKS]")
        sb.appendLine("- Today's Calendar Events: ${snapshot.todayEvents.size} | Upcoming 7d Events: ${snapshot.upcomingEvents7d.size}")
        snapshot.todayEvents.take(6).forEach { ev ->
            val loc = if (ev.location.isNotBlank()) " @ ${ev.location}" else ""
            sb.appendLine("  * Today ${formatEventTimeRange(ev)} | ${ev.title}$loc")
        }
        val futureEvents = snapshot.upcomingEvents7d.filter { ev -> snapshot.todayEvents.none { it.id == ev.id && it.startMillis == ev.startMillis } }
        futureEvents.take(4).forEach { ev ->
            sb.appendLine("  * Upcoming ${formatMillisDateTime(ev.startMillis)} | ${ev.title}")
        }
        sb.appendLine("- Pending Work & Personal Tasks: ${snapshot.pendingTasks.size} (Overdue: ${snapshot.overdueTasksCount}, Due Today: ${snapshot.dueTodayTasksCount})")
        snapshot.pendingTasks.take(8).forEach { task ->
            val dueLbl = task.dueAtMillis?.let { formatMillisDateTime(it) } ?: "No deadline"
            sb.appendLine("  * [${task.priority.label}/${task.category.label}] ${task.title} (Due: $dueLbl)")
        }

        if (snapshot.recentEmails.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[4. RECENT EMAIL ALERTS (ON-DEVICE NOTIFICATION INBOX)]")
            sb.appendLine("- Captured Emails: ${snapshot.recentEmails.size} | Unread: ${snapshot.unreadEmailsCount} | Urgent: ${snapshot.urgentEmailsCount}")
            snapshot.recentEmails.take(8).forEach { mail ->
                val stateLbl = if (mail.isRead) "READ" else if (mail.isUrgent) "URGENT" else "UNREAD"
                val snip = if (mail.snippet.isNotBlank()) " — ${mail.snippet.take(120)}" else ""
                sb.appendLine("  * [$stateLbl/${mail.tag.label}] ${mail.sender}: ${mail.subject}$snip (${formatMillisDateTime(mail.receivedAtMillis)})")
            }
        }

        if (snapshot.insights.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[5. DETECTED EXECUTIVE ALERTS]")
            snapshot.insights.forEach { sb.appendLine("- [${it.domain}] ${it.title}: ${it.detail}") }
        }
        return sb.toString()
    }

    fun buildDeterministicBriefing(snapshot: ExecutiveSnapshot, profile: SecretaryProfile): String {
        val c = profile.currencySymbol
        val sb = StringBuilder()
        sb.appendLine("Executive Briefing for ${profile.userName}")
        sb.appendLine()
        sb.appendLine("1. Schedule & Work Priorities")
        if (snapshot.todayEvents.isEmpty() && snapshot.pendingTasks.isEmpty()) {
            sb.appendLine("• Calendar and task desk are currently clear. Connect your phone calendar or add work reminders in the Schedule tab.")
        } else {
            sb.appendLine("• Today's Calendar: ${snapshot.todayEvents.size} meeting${if (snapshot.todayEvents.size == 1) "" else "s"} (${snapshot.upcomingEvents7d.size} over the next 7 days).")
            snapshot.todayEvents.take(3).forEach { ev ->
                sb.appendLine("  – ${formatEventTimeRange(ev)}: ${ev.title}")
            }
            sb.appendLine("• Work Tasks: ${snapshot.pendingTasks.size} open (${snapshot.overdueTasksCount} overdue · ${snapshot.dueTodayTasksCount} due today).")
            snapshot.pendingTasks.take(3).forEach { t ->
                val due = t.dueAtMillis?.let { " · ${formatMillisDateTime(it)}" }.orEmpty()
                sb.appendLine("  – [${t.priority.label}] ${t.title}$due")
            }
        }
        if (snapshot.recentEmails.isNotEmpty()) {
            sb.appendLine("• Email Inbox: ${snapshot.unreadEmailsCount} unread (${snapshot.urgentEmailsCount} urgent) across Gmail/Outlook/Edison Mail alerts.")
            snapshot.recentEmails.filter { !it.isRead }.take(2).forEach { m ->
                sb.appendLine("  – [${m.tag.label}] ${m.sender}: ${m.subject}")
            }
        }
        sb.appendLine()
        sb.appendLine("2. Financial Position")
        if (snapshot.hygieneReport.uniqueDebitRows > 0) {
            sb.appendLine("• Liquid balance stands at ${formatMoney(c, snapshot.debitBalance)}, with Credit Card at ${formatMoney(c, snapshot.creditCardBalance)} and SSF net equity at ${formatMoney(c, snapshot.ssfSummary.netEquity)}.")
            sb.appendLine("• Active month (${snapshot.currentMonthKey}): ${formatMoney(c, snapshot.currentMonthExpense)} spent vs. ${formatMoney(c, snapshot.currentMonthIncome)} received (${snapshot.budgetUsedPercent}% of your ${formatMoney(c, profile.monthlySpendBudget)} budget; 30d burn ${formatMoney(c, snapshot.dailyBurnRate30d)}/day).")
            snapshot.topCategories30d.firstOrNull()?.let { top ->
                sb.appendLine("• Top 30-day category: ${top.category} at ${formatMoney(c, top.amount)} (${top.sharePercent}%).")
            }
        } else {
            sb.appendLine("• Day Book is not synced yet. Connect your Apps Script URL or import DayBook_Bootstrap.json in Setup to activate live financial auditing.")
        }
        sb.appendLine()
        sb.appendLine("3. Physical Health & Watch Vitals")
        val rem = (profile.dailyStepGoal - snapshot.todaySteps).coerceAtLeast(0L)
        val m = snapshot.todayMetrics
        sb.appendLine("• Today: ${snapshot.todaySteps} / ${profile.dailyStepGoal} steps (${String.format(Locale.US, "%.2f", m.distanceKm)} km · ${m.caloriesKcal} kcal · ${m.activeMinutes} min). ${if (rem == 0L) "Target achieved." else "$rem steps remaining."}")
        sb.appendLine("• 7-day average: ${snapshot.avgSteps7d} steps/day · Weekly total: ${snapshot.weeklyStepsTotal} · Streak: ${snapshot.stepStreakDays}d.")
        if (snapshot.healthVitals.hasAnyWatchData) {
            val v = snapshot.healthVitals
            val extras = buildList {
                if (v.lastNightSleepMinutes > 0) add("Sleep ${formatDurationHoursMins(v.lastNightSleepMinutes)}")
                v.restingHeartRateBpm?.let { add("Resting HR $it bpm") }
                v.latestSpo2Percent?.let { add("SpO2 ${String.format(Locale.US, "%.1f", it)}%") }
                if (v.workoutsThisWeek > 0) add("${v.workoutsThisWeek} workouts (${v.workoutMinutesThisWeek}m)")
            }
            sb.appendLine("• Watch Vitals: ${extras.joinToString(" · ")}")
        }
        sb.appendLine()
        sb.appendLine("4. Priority Actions")
        snapshot.insights.take(3).forEachIndexed { idx, ins ->
            sb.appendLine("${idx + 1}) ${ins.title} — ${ins.recommendation}")
        }
        return sb.toString().trim()
    }

    fun buildDeterministicReply(
        userQuery: String,
        snapshot: ExecutiveSnapshot,
        profile: SecretaryProfile
    ): String {
        val q = userQuery.lowercase(Locale.US)
        val c = profile.currencySymbol
        return when {
            q.contains("leak") || q.contains("category") || q.contains("spend") || q.contains("budget") -> {
                val cats = if (snapshot.topCategories30d.isEmpty()) {
                    "No categorized spending synced yet."
                } else {
                    snapshot.topCategories30d.joinToString("\n") {
                        "• ${it.category}: ${formatMoney(c, it.amount)} (${it.sharePercent}%, ${it.count} entries)"
                    }
                }
                """
                30-Day Spending Breakdown (${formatMoney(c, snapshot.last30DaysExpense)} total | ${formatMoney(c, snapshot.dailyBurnRate30d)}/day):
                $cats

                Active month (${snapshot.currentMonthKey}) budget status: ${snapshot.budgetUsedPercent}% of ${formatMoney(c, profile.monthlySpendBudget)}.
                ${if (snapshot.tobaccoAlcohol30d > 0) "Notable discretionary lever: Tobacco & Alcohol is ${formatMoney(c, snapshot.tobaccoAlcohol30d)} over 30 days." else ""}
                """.trimIndent()
            }
            q.contains("ssf") || q.contains("credit") || q.contains("loan") || q.contains("net worth") -> {
                val ssf = snapshot.ssfSummary
                """
                Balance Sheet Snapshot:
                • Debit Account: ${formatMoney(c, snapshot.debitBalance)}
                • Credit Card: ${formatMoney(c, snapshot.creditCardBalance)}
                • SSF Savings: ${formatMoney(c, ssf.refundableAmount)} (${ssf.contributedMonths} months) + ${formatMoney(c, ssf.accruedInterest)} accrued interest
                • SSF Special Loan Due: ${formatMoney(c, ssf.totalDueAmount)} (Principal ${formatMoney(c, ssf.duePrincipal)} + Interest ${formatMoney(c, ssf.accountInterest)})
                • SSF Net Equity: ${formatMoney(c, ssf.netEquity)} (Interest spread: +${formatMoney(c, ssf.interestSpread)})
                • Combined Net Estimate: ${formatMoney(c, snapshot.netWorthEstimate)}
                """.trimIndent()
            }
            q.contains("email") || q.contains("inbox") || q.contains("mail") || q.contains("gmail") || q.contains("outlook") -> {
                if (snapshot.recentEmails.isEmpty()) {
                    "No incoming email notifications captured yet. Enable Notification Access in Schedule -> Email Inbox so Clara can summarize incoming Gmail, Outlook, or Edison Mail alerts locally."
                } else {
                    val items = snapshot.recentEmails.take(8).joinToString("\n") { m ->
                        val status = if (m.isUrgent && !m.isRead) "URGENT" else if (!m.isRead) "UNREAD" else "Read"
                        "• [$status · ${m.tag.label}] ${m.sender}: ${m.subject} (${formatMillisDateTime(m.receivedAtMillis)})"
                    }
                    """
                    On-Device Email Inbox (${snapshot.unreadEmailsCount} unread · ${snapshot.urgentEmailsCount} urgent):
                    $items

                    Tip: Open Schedule -> Email Inbox to convert any bill or work email into a scheduled reminder in one tap.
                    """.trimIndent()
                }
            }
            q.contains("schedule") || q.contains("calendar") || q.contains("meeting") || q.contains("task") || q.contains("work") || q.contains("remind") -> {
                val evLines = if (snapshot.todayEvents.isEmpty()) {
                    "• No calendar meetings scheduled for today."
                } else {
                    snapshot.todayEvents.take(6).joinToString("\n") { ev ->
                        val loc = if (ev.location.isNotBlank()) " (${ev.location})" else ""
                        "• ${formatEventTimeRange(ev)} — ${ev.title}$loc"
                    }
                }
                val taskLines = if (snapshot.pendingTasks.isEmpty()) {
                    "• No pending tasks on your desk."
                } else {
                    snapshot.pendingTasks.take(6).joinToString("\n") { t ->
                        val due = t.dueAtMillis?.let { formatMillisDateTime(it) } ?: "No due time"
                        "• [${t.priority.label}/${t.category.label}] ${t.title} — $due"
                    }
                }
                """
                Schedule & Work Desk (${snapshot.todayEvents.size} events today · ${snapshot.pendingTasks.size} open tasks, ${snapshot.overdueTasksCount} overdue):

                Today's Calendar:
                $evLines

                Priority Work & Personal Tasks:
                $taskLines
                """.trimIndent()
            }
            q.contains("step") || q.contains("walk") || q.contains("physical") || q.contains("exercise") || q.contains("calor") || q.contains("sleep") || q.contains("heart") || q.contains("watch") -> {
                val rem = (profile.dailyStepGoal - snapshot.todaySteps).coerceAtLeast(0L)
                val m = snapshot.todayMetrics
                val series = snapshot.recentSteps7d.joinToString(" | ") { "${it.dateIso.takeLast(5)}: ${it.steps}" }
                val vitalsLine = if (snapshot.healthVitals.hasAnyWatchData) {
                    val v = snapshot.healthVitals
                    val parts = buildList {
                        if (v.lastNightSleepMinutes > 0) add("Sleep ${formatDurationHoursMins(v.lastNightSleepMinutes)}")
                        v.restingHeartRateBpm?.let { add("Resting HR $it bpm") }
                        v.latestHeartRateBpm?.let { add("Latest HR $it bpm") }
                        v.latestSpo2Percent?.let { add("SpO2 ${String.format(Locale.US, "%.1f", it)}%") }
                        if (v.workoutsThisWeek > 0) add("${v.workoutsThisWeek} workouts (${v.workoutMinutesThisWeek} min)")
                    }
                    "\n• Watch Vitals: ${parts.joinToString(" · ")}"
                } else {
                    "\n• Watch Vitals: Ready for Health Connect watch sync (OnePlus Watch, Fitbit, Amazfit, Xiaomi, Garmin)."
                }
                """
                Physical & Watch Health Audit:
                • Today: ${snapshot.todaySteps} / ${profile.dailyStepGoal} steps (${String.format(Locale.US, "%.2f", m.distanceKm)} km · ${m.caloriesKcal} kcal · ${m.activeMinutes} active min)
                • 7-Day Average: ${snapshot.avgSteps7d} steps/day | Weekly Total: ${snapshot.weeklyStepsTotal} | Streak: ${snapshot.stepStreakDays}d
                • Last 7 Days: $series$vitalsLine

                Recommendation: ${if (rem > 0) "Split the remaining $rem steps into a 20-minute brisk walk (~2,000 steps) to boost daily burn and hit your target." else "Goal reached today — solid consistency."}
                """.trimIndent()
            }
            else -> buildDeterministicBriefing(snapshot, profile)
        }
    }

    fun formatDurationHoursMins(totalMinutes: Int): String {
        val safe = totalMinutes.coerceAtLeast(0)
        val hrs = safe / 60
        val mins = safe % 60
        return when {
            hrs > 0 && mins > 0 -> "${hrs}h ${mins}m"
            hrs > 0 -> "${hrs}h"
            else -> "${mins}m"
        }
    }

    fun formatEventTimeRange(ev: CalendarEventItem, zone: ZoneId = ZoneId.systemDefault()): String {
        if (ev.allDay) return "All day"
        val start = Instant.ofEpochMilli(ev.startMillis).atZone(zone).format(timeFmt)
        val end = Instant.ofEpochMilli(ev.endMillis).atZone(zone).format(timeFmt)
        return "$start – $end"
    }

    fun formatMillisDateOnly(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        return Instant.ofEpochMilli(millis).atZone(zone).format(shortDateOnlyFmt)
    }

    fun formatMillisDateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        return Instant.ofEpochMilli(millis).atZone(zone).format(shortDateTimeFmt)
    }

    fun formatMoney(currency: String, amount: Double): String {
        val sign = if (amount < 0) "-" else ""
        return "$sign$currency ${String.format(Locale.US, "%,.2f", abs(amount))}"
    }

    fun formatSignedMoney(currency: String, amount: Double): String {
        val sign = if (amount >= 0) "+" else "-"
        return "$sign$currency ${String.format(Locale.US, "%,.2f", abs(amount))}"
    }

    fun formatHourLabel(hour24: Int): String {
        val h = hour24.coerceIn(0, 23)
        return when {
            h == 0 -> "12 AM"
            h < 12 -> "$h AM"
            h == 12 -> "12 PM"
            else -> "${h - 12} PM"
        }
    }
}
