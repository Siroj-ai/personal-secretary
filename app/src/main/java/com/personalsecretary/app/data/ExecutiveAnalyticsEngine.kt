package com.personalsecretary.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
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
        marketSnapshot: NepalMarketSnapshot = NepalMarketSnapshot(),
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
            if (isTobaccoOrAlcoholCategory(cat)) {
                tobaccoAlcohol30d += tx.debit
            }
        }
        val todaySpend = DayBookParserAndHygiene.round2(
            debitTxns.filter { it.dateIso == todayIso }.sumOf { it.debit }
        )
        val yesterdaySpend = DayBookParserAndHygiene.round2(
            debitTxns.filter { it.dateIso == yesterdayIso }.sumOf { it.debit }
        )
        val last7Start = today.minusDays(6).format(isoFmt)
        val tobaccoAlcohol7d = DayBookParserAndHygiene.round2(
            debitTxns.filter {
                it.dateIso >= last7Start && it.dateIso != "0000-00-00" &&
                    it.debit > 0.0 && isTobaccoOrAlcoholCategory(it.category)
            }.sumOf { it.debit }
        )
        val recent14Start = today.minusDays(13).format(isoFmt)
        val uncategorizedRecent = debitTxns.filter {
            it.dateIso >= recent14Start && it.dateIso != "0000-00-00" &&
                it.category.equals("Uncategorized", ignoreCase = true) &&
                (it.debit > 0.0 || it.credit > 0.0)
        }
        val uncategorizedRecentCount = uncategorizedRecent.size
        val uncategorizedRecentAmount = DayBookParserAndHygiene.round2(
            uncategorizedRecent.sumOf { if (it.debit > 0.0) it.debit else it.credit }
        )
        val emergencyBuffer = maxOf(profile.monthlySpendBudget, 35000.0)
        val investableSurplus = DayBookParserAndHygiene.round2(
            (debitBalance - abs(creditBalance) - emergencyBuffer).coerceAtLeast(0.0)
        )

        // Daily Expense Analytics & Personalized Daily Coaching (14-day window)
        val daysInMonth = today.lengthOfMonth().coerceAtLeast(28)
        val dailyBudgetAllowance = DayBookParserAndHygiene.round2(
            (profile.monthlySpendBudget / daysInMonth.toDouble()).coerceAtLeast(0.0)
        )
        val remainingDaysInMonth = (daysInMonth - today.dayOfMonth + 1).coerceAtLeast(1)
        val remainingSafeDailyCap = DayBookParserAndHygiene.round2(
            ((profile.monthlySpendBudget - currentMonthExpense) / remainingDaysInMonth.toDouble()).coerceAtLeast(0.0)
        )
        val dailyExpenses14d = buildDailyExpenses14d(
            transactions = transactions,
            dailyCap = dailyBudgetAllowance,
            currency = profile.currencySymbol,
            today = today
        )
        val last7DaysExpenses = dailyExpenses14d.takeLast(7)
        val activeSpendDays7d = last7DaysExpenses.filter { it.totalSpend > 0.0 }
        val avgDailySpend7d = if (last7DaysExpenses.isNotEmpty()) {
            DayBookParserAndHygiene.round2(last7DaysExpenses.sumOf { it.totalSpend } / last7DaysExpenses.size.toDouble())
        } else 0.0
        val (dailyHeadline, dailySuggestions) = buildDailyCoaching(
            dailyExpenses7d = last7DaysExpenses,
            activeSpendDays7d = activeSpendDays7d,
            todaySpend = todaySpend,
            yesterdaySpend = yesterdaySpend,
            dailyCap = dailyBudgetAllowance,
            remainingSafeCap = remainingSafeDailyCap,
            avgDailySpend7d = avgDailySpend7d,
            tobaccoAlcohol7d = tobaccoAlcohol7d,
            uncategorizedRecentCount = uncategorizedRecentCount,
            investableSurplus = investableSurplus,
            currency = profile.currencySymbol
        )
        val topCategories = catMap.entries
            .sortedByDescending { it.value.first }
            .map { (cat, pair) ->
                val share = if (last30Expense > 0.0) (pair.first / last30Expense) * 100.0 else 0.0
                CategorySpend(
                    category = cat,
                    amount = DayBookParserAndHygiene.round2(pair.first),
                    count = pair.second,
                    sharePercent = DayBookParserAndHygiene.round2(share)
                )
            }

        // All-time debit category breakdown matching Google Sheets Category distribution
        val allCatMap = LinkedHashMap<String, Pair<Double, Int>>()
        var allTimeDebitTotal = 0.0
        for (tx in debitTxns) {
            if (tx.debit > 0.0) {
                allTimeDebitTotal += tx.debit
                val prev = allCatMap[tx.category] ?: (0.0 to 0)
                allCatMap[tx.category] = (prev.first + tx.debit) to (prev.second + 1)
            }
        }
        val allTimeCategories = allCatMap.entries
            .sortedByDescending { it.value.first }
            .map { (cat, pair) ->
                val share = if (allTimeDebitTotal > 0.0) (pair.first / allTimeDebitTotal) * 100.0 else 0.0
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
            tobaccoAlcohol7d = tobaccoAlcohol7d,
            tobaccoAlcohol30d = DayBookParserAndHygiene.round2(tobaccoAlcohol30d),
            uncategorizedRecentCount = uncategorizedRecentCount,
            uncategorizedRecentAmount = uncategorizedRecentAmount,
            latestUncategorizedSample = uncategorizedRecent.lastOrNull()?.normalizedDescription.orEmpty(),
            investableSurplus = investableSurplus,
            marketSnapshot = marketSnapshot,
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
            dailyBudgetAllowance = dailyBudgetAllowance,
            remainingSafeDailyCap = remainingSafeDailyCap,
            avgDailySpend7d = avgDailySpend7d,
            todaySpend = todaySpend,
            yesterdaySpend = yesterdaySpend,
            dailyExpenses14d = dailyExpenses14d,
            dailyCoachingHeadline = dailyHeadline,
            dailyCoachingSuggestions = dailySuggestions,
            budgetUsedPercent = budgetUsedPct,
            tobaccoAlcohol7d = tobaccoAlcohol7d,
            tobaccoAlcohol30d = DayBookParserAndHygiene.round2(tobaccoAlcohol30d),
            uncategorizedRecentCount = uncategorizedRecentCount,
            uncategorizedRecentAmount = uncategorizedRecentAmount,
            investableSurplus = investableSurplus,
            marketSnapshot = marketSnapshot,
            topCategories30d = topCategories,
            allTimeCategories = allTimeCategories,
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

    private fun buildDailyExpenses14d(
        transactions: List<TransactionRecord>,
        dailyCap: Double,
        currency: String,
        today: LocalDate
    ): List<DailyExpenseSummary> {
        val byDate = transactions
            .filter { it.dateIso != "0000-00-00" }
            .groupBy { it.dateIso }

        return (13L downTo 0L).map { offset ->
            val date = today.minusDays(offset)
            val dateIso = date.format(isoFmt)
            val dayLabel = when (offset) {
                0L -> "Today"
                1L -> "Yest"
                else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
            }
            val dayRows = byDate[dateIso].orEmpty()
            val debitRows = dayRows.filter { it.debit > 0.0 }
            val totalSpend = DayBookParserAndHygiene.round2(debitRows.sumOf { it.debit })
            val totalIncome = DayBookParserAndHygiene.round2(dayRows.sumOf { it.credit })
            val topCategory = debitRows
                .groupBy { it.category.ifBlank { "Uncategorized" } }
                .maxByOrNull { (_, list) -> list.sumOf { it.debit } }
                ?.key
                .orEmpty()
            val tobaccoSpend = DayBookParserAndHygiene.round2(
                debitRows.filter { isTobaccoOrAlcoholCategory(it.category) }.sumOf { it.debit }
            )
            val uncatCount = dayRows.count {
                it.category.equals("Uncategorized", ignoreCase = true) && (it.debit > 0.0 || it.credit > 0.0)
            }

            val status = when {
                totalSpend <= 0.0 -> DailySpendStatus.NO_SPEND
                dailyCap <= 0.0 -> DailySpendStatus.UNDER_BUDGET
                totalSpend > dailyCap * 1.25 -> DailySpendStatus.OVER_BUDGET
                totalSpend > dailyCap * 0.85 -> DailySpendStatus.WATCH
                else -> DailySpendStatus.UNDER_BUDGET
            }

            val advice = when {
                totalSpend <= 0.0 && offset == 0L ->
                    if (dailyCap > 0.0) {
                        "No expenses logged today yet — aim to stay under ${formatMoney(currency, dailyCap)} or tap + Log for cash/QR spends."
                    } else {
                        "No expenses logged today yet. Tap + Log if you paid via cash or QR."
                    }
                totalSpend <= 0.0 ->
                    "Zero-spend day — strong cash preservation toward your savings & IPO buffer."
                tobaccoSpend > 0.0 -> {
                    val monthlyProj = DayBookParserAndHygiene.round2(tobaccoSpend * 15.0)
                    "Included ${formatMoney(currency, tobaccoSpend)} on Tobacco & Alcohol. Skipping habit days frees ~${formatMoney(currency, monthlyProj)}/mo for investments."
                }
                status == DailySpendStatus.OVER_BUDGET -> {
                    val overBy = DayBookParserAndHygiene.round2((totalSpend - dailyCap).coerceAtLeast(0.0))
                    val driver = if (topCategory.isNotBlank()) " (top driver: $topCategory)" else ""
                    "Exceeded daily cap by ${formatMoney(currency, overBy)}$driver — balance with a low-spend reset day."
                }
                uncatCount > 0 ->
                    "$uncatCount uncategorized entr${if (uncatCount == 1) "y" else "ies"} on this day — tap below to label what it was for."
                status == DailySpendStatus.WATCH ->
                    "Close to your ${formatMoney(currency, dailyCap)} daily cap — pause impulse or discretionary add-ons."
                else -> {
                    val saved = DayBookParserAndHygiene.round2((dailyCap - totalSpend).coerceAtLeast(0.0))
                    if (saved > 0.0) {
                        "Under daily cap by ${formatMoney(currency, saved)} — disciplined pace."
                    } else {
                        "Steady daily spending pace."
                    }
                }
            }

            DailyExpenseSummary(
                dateIso = dateIso,
                dayShortLabel = dayLabel,
                totalSpend = totalSpend,
                totalIncome = totalIncome,
                txCount = debitRows.size,
                topCategory = topCategory,
                tobaccoAlcoholSpend = tobaccoSpend,
                uncategorizedCount = uncatCount,
                status = status,
                advice = advice,
                transactions = dayRows.reversed()
            )
        }
    }

    private fun buildDailyCoaching(
        dailyExpenses7d: List<DailyExpenseSummary>,
        activeSpendDays7d: List<DailyExpenseSummary>,
        todaySpend: Double,
        yesterdaySpend: Double,
        dailyCap: Double,
        remainingSafeCap: Double,
        avgDailySpend7d: Double,
        tobaccoAlcohol7d: Double,
        uncategorizedRecentCount: Int,
        investableSurplus: Double,
        currency: String
    ): Pair<String, List<String>> {
        val suggestions = mutableListOf<String>()
        val overCapDays = dailyExpenses7d.count { it.status == DailySpendStatus.OVER_BUDGET }
        val zeroDays = dailyExpenses7d.count { it.status == DailySpendStatus.NO_SPEND }
        val peakDay = activeSpendDays7d.maxByOrNull { it.totalSpend }

        val headline = when {
            activeSpendDays7d.isEmpty() ->
                "No expenses recorded in the last 7 days — sync your Day Book or tap + Log to track daily outflows."
            dailyCap > 0.0 && todaySpend > dailyCap * 1.2 ->
                "Today's spend (${formatMoney(currency, todaySpend)}) is above your ${formatMoney(currency, dailyCap)}/day cap — freeze discretionary outlays."
            dailyCap > 0.0 && avgDailySpend7d > dailyCap * 1.15 ->
                "7-day pace is ${formatMoney(currency, avgDailySpend7d)}/day vs. your ${formatMoney(currency, dailyCap)}/day target ($overCapDays spike day${if (overCapDays == 1) "" else "s"})."
            yesterdaySpend > 0.0 && dailyCap > 0.0 && yesterdaySpend <= dailyCap ->
                "Yesterday (${formatMoney(currency, yesterdaySpend)}) stayed under your ${formatMoney(currency, dailyCap)}/day target — keep the streak going today."
            else ->
                "7-day daily average is ${formatMoney(currency, avgDailySpend7d)}/day across ${activeSpendDays7d.size} active spending day${if (activeSpendDays7d.size == 1) "" else "s"}."
        }

        if (dailyCap > 0.0) {
            if (remainingSafeCap <= 0.0) {
                suggestions += "Monthly budget is exhausted — switch to strict essentials-only days until next month's cycle resets."
            } else if (avgDailySpend7d > dailyCap) {
                val trimPerDay = DayBookParserAndHygiene.round2(avgDailySpend7d - dailyCap)
                suggestions += "Trim ~${formatMoney(currency, trimPerDay)}/day (staying under ${formatMoney(currency, remainingSafeCap)}/day for remaining days) to bring your month back on target."
            } else {
                val headroomToday = DayBookParserAndHygiene.round2((dailyCap - todaySpend).coerceAtLeast(0.0))
                suggestions += "Safe spending headroom left for today: ${formatMoney(currency, headroomToday)} (7-day avg ${formatMoney(currency, avgDailySpend7d)}/day)."
            }
        }

        if (tobaccoAlcohol7d > 0.0) {
            val habitDays = dailyExpenses7d.count { it.tobaccoAlcoholSpend > 0.0 }
            val monthlySavings = DayBookParserAndHygiene.round2((tobaccoAlcohol7d / 7.0) * 30.0 * 0.5)
            suggestions += "Tobacco & Alcohol showed up on $habitDays of the last 7 days (${formatMoney(currency, tobaccoAlcohol7d)}). Cutting in half saves ~${formatMoney(currency, monthlySavings)}/month."
        }

        if (peakDay != null && dailyCap > 0.0 && peakDay.totalSpend > dailyCap * 1.25) {
            val topCat = peakDay.topCategory.ifBlank { "Uncategorized" }
            suggestions += "Biggest spike this week was ${peakDay.dateIso} (${formatMoney(currency, peakDay.totalSpend)}, led by $topCat) — review if it was one-off or recurring."
        }

        if (uncategorizedRecentCount > 0) {
            suggestions += "$uncategorizedRecentCount recent entries are still Uncategorized — tap any gold row to label it so daily suggestions get sharper."
        } else if (zeroDays >= 2 && investableSurplus >= 2000.0) {
            suggestions += "$zeroDays zero-spend days in the past week! Channel surplus cash (${formatMoney(currency, investableSurplus)}) into open Nepal IPOs or liquid NEPSE picks."
        }

        return headline to suggestions.take(4)
    }

    private fun buildInsights(
        debitBalance: Double,
        creditBalance: Double,
        ssf: SsfSummary,
        currentMonthExpense: Double,
        last30Expense: Double,
        tobaccoAlcohol7d: Double,
        tobaccoAlcohol30d: Double,
        uncategorizedRecentCount: Int,
        uncategorizedRecentAmount: Double,
        latestUncategorizedSample: String,
        investableSurplus: Double,
        marketSnapshot: NepalMarketSnapshot,
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

        if (tobaccoAlcohol7d >= 200.0 || tobaccoAlcohol30d >= 1200.0) {
            val pctStr = if (last30Expense > 0) {
                " (${String.format(Locale.US, "%.1f", (tobaccoAlcohol30d / last30Expense) * 100.0)}% of 30d outflow)"
            } else ""
            val annualSavings = DayBookParserAndHygiene.round2(maxOf(tobaccoAlcohol30d, tobaccoAlcohol7d * 4.0) * 0.4 * 12.0)
            list += ExecutiveInsight(
                id = "habit_spend",
                domain = InsightDomain.CROSS_DOMAIN,
                severity = InsightSeverity.CAUTION,
                title = "Tobacco & Alcohol Alert: ${formatMoney(cur, tobaccoAlcohol30d)} (30d)",
                detail = "Spent ${formatMoney(cur, tobaccoAlcohol7d)} in the last 7 days and ${formatMoney(cur, tobaccoAlcohol30d)} over 30 days$pctStr.",
                recommendation = "Trimming this by 40% redirects ~${formatMoney(cur, annualSavings)}/year into IPOs or NEPSE shares while improving stamina."
            )
        }

        if (uncategorizedRecentCount > 0) {
            val sampleHint = latestUncategorizedSample.takeIf { it.isNotBlank() }?.let { " (e.g. '$it')" }.orEmpty()
            list += ExecutiveInsight(
                id = "uncategorized_clarify",
                domain = InsightDomain.FINANCIAL,
                severity = InsightSeverity.INFO,
                title = "$uncategorizedRecentCount Uncategorized Recent Entr${if (uncategorizedRecentCount == 1) "y" else "ies"} (${formatMoney(cur, uncategorizedRecentAmount)})",
                detail = "Some recent entries$sampleHint lack clear descriptions or categories, which limits exact spending advice.",
                recommendation = "Open Finances -> Uncategorized and tap any entry to label what it was for."
            )
        }

        if (investableSurplus >= 2000.0 && marketSnapshot.hasMarketData) {
            val generalOpen = marketSnapshot.openIpos.firstOrNull { it.isGeneralPublic }
            val generalUpcoming = marketSnapshot.upcomingIpos.firstOrNull { it.isGeneralPublic }
            val topStock = marketSnapshot.promisingStocks.firstOrNull()
            val (title, detail, rec) = when {
                generalOpen != null -> Triple(
                    "Open Nepal IPO: ${generalOpen.companyName} (${generalOpen.stockSymbol})",
                    "Investable surplus is ${formatMoney(cur, investableSurplus)} after Credit Card & monthly buffer. ${generalOpen.companyName} (${generalOpen.sectorName}) is Open until ${generalOpen.closingDateAd} (min ${formatMoney(cur, generalOpen.minInvestmentNpr)}).",
                    "Review rating/prospectus in Finances -> IPO & NEPSE Radar and apply via MeroShare if it fits your risk profile."
                )
                generalUpcoming != null -> Triple(
                    "Upcoming Nepal IPO: ${generalUpcoming.companyName} (${generalUpcoming.stockSymbol})",
                    "You have ${formatMoney(cur, investableSurplus)} investable surplus. ${generalUpcoming.companyName} opens ${generalUpcoming.openingDateAd} (min ${formatMoney(cur, generalUpcoming.minInvestmentNpr)}${if (generalUpcoming.rating.isNotBlank()) " · ${generalUpcoming.rating}" else ""}).",
                    "Keep ${formatMoney(cur, generalUpcoming.minInvestmentNpr)} parked in MeroShare/ASBA and review secondary picks in Finances."
                )
                topStock != null -> Triple(
                    "Investable Surplus Ready: ${formatMoney(cur, investableSurplus)}",
                    "Live NEPSE screen highlights ${topStock.symbol} (LTP ${formatMoney(cur, topStock.ltp)}, ${String.format(Locale.US, "%+.2f%%", topStock.percentChange)}, ${topStock.signalTag}).",
                    "Open Finances -> IPO & NEPSE Radar or ask Clara for a disciplined SIP/secondary allocation plan."
                )
                else -> Triple("", "", "")
            }
            if (title.isNotBlank()) {
                list += ExecutiveInsight(
                    id = "nepal_ipo_opportunity",
                    domain = InsightDomain.FINANCIAL,
                    severity = InsightSeverity.POSITIVE,
                    title = title,
                    detail = detail,
                    recommendation = rec
                )
            }
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
        recentTransactions: List<TransactionRecord>,
        userQuery: String = "",
        today: LocalDate = LocalDate.now()
    ): String {
        val c = profile.currencySymbol
        val todayIso = today.format(isoFmt)
        val yesterdayDate = today.minusDays(1)
        val yesterdayIso = yesterdayDate.format(isoFmt)
        val sb = StringBuilder()
        sb.appendLine("=== EXECUTIVE CLIENT DOSSIER (LIVE VERIFIED METRICS) ===")
        sb.appendLine("Client: ${profile.userName} | Currency: $c | Today: $todayIso (${today.dayOfWeek}) | Yesterday: $yesterdayIso (${yesterdayDate.dayOfWeek}) | Active Month: ${snapshot.currentMonthKey}")
        sb.appendLine("Authoritative Targets: Monthly Spend Budget = ${formatMoney(c, profile.monthlySpendBudget)} | Daily Step Goal = ${profile.dailyStepGoal} steps")
        sb.appendLine("Financial Priorities: ${profile.financialGoals}")
        sb.appendLine("Physical Activity Priorities: ${profile.healthGoals}")
        if (profile.customDossierNotes.isNotBlank()) {
            sb.appendLine("Client Notes: ${profile.customDossierNotes}")
        }
        sb.appendLine()
        sb.appendLine("[1. FINANCIAL HEALTH - DAY BOOK (DEDUPLICATED)]")
        sb.appendLine("- Liquid Debit Balance: ${formatMoney(c, snapshot.debitBalance)} | Credit Card: ${formatMoney(c, snapshot.creditCardBalance)} | Investable Surplus (after CC & monthly buffer): ${formatMoney(c, snapshot.investableSurplus)}")
        if (snapshot.ssfSummary.refundableAmount > 0) {
            val ssf = snapshot.ssfSummary
            sb.appendLine(
                "- SSF Savings (${ssf.contributedMonths} months): Refundable ${formatMoney(c, ssf.refundableAmount)} " +
                    "(Pension ${formatMoney(c, ssf.pensionAmount)} + Retirement ${formatMoney(c, ssf.retirementAmount)} + Return ${formatMoney(c, ssf.interestAmount)}) | " +
                    "FY Accrued Interest ${formatMoney(c, ssf.accruedInterest)}"
            )
            sb.appendLine(
                "- SSF Special Loan (Applied Period: ${ssf.loanAppliedPeriod}): Due Principal ${formatMoney(c, ssf.duePrincipal)} + " +
                    "Account Interest ${formatMoney(c, ssf.accountInterest)} = Total Due ${formatMoney(c, ssf.totalDueAmount)} | " +
                    "Net Equity ${formatMoney(c, ssf.netEquity)} (w/ Accrued: ${formatMoney(c, ssf.netEquityWithAccrued)}) | " +
                    "Interest Spread: ${formatSignedMoney(c, ssf.interestSpread)}"
            )
        }
        if (snapshot.earningsTotal > 0) {
            sb.appendLine("- Extra Earnings (${snapshot.earningsMonthLabel}): ${formatMoney(c, snapshot.earningsTotal)}")
        }
        sb.appendLine("- Active Month (${snapshot.currentMonthKey}): Income ${formatMoney(c, snapshot.currentMonthIncome)}, Expense ${formatMoney(c, snapshot.currentMonthExpense)} (${snapshot.budgetUsedPercent}% of ${formatMoney(c, profile.monthlySpendBudget)} budget)")
        sb.appendLine("- Rolling 30-Day: Income ${formatMoney(c, snapshot.last30DaysIncome)}, Expense ${formatMoney(c, snapshot.last30DaysExpense)}, Daily Burn ${formatMoney(c, snapshot.dailyBurnRate30d)}/day")
        sb.appendLine("- Daily Cap: ${formatMoney(c, snapshot.dailyBudgetAllowance)}/day | 7d Avg: ${formatMoney(c, snapshot.avgDailySpend7d)}/day | Coach: ${snapshot.dailyCoachingHeadline}")
        if (snapshot.tobaccoAlcohol7d > 0 || snapshot.tobaccoAlcohol30d > 0) {
            sb.appendLine("- Tobacco & Alcohol: 7d ${formatMoney(c, snapshot.tobaccoAlcohol7d)} | 30d ${formatMoney(c, snapshot.tobaccoAlcohol30d)}")
        }
        if (snapshot.topCategories30d.isNotEmpty()) {
            val cats = snapshot.topCategories30d.joinToString("; ") {
                "${it.category}: ${formatMoney(c, it.amount)} (${it.sharePercent}%)"
            }
            sb.appendLine("- Top Spend Categories (30d): $cats")
        }
        if (snapshot.hygieneReport.duplicatesRemoved > 0 || snapshot.hygieneReport.balanceAnomalies.isNotEmpty() || snapshot.uncategorizedRecentCount > 0) {
            sb.appendLine("- Data Hygiene: ${snapshot.hygieneReport.duplicatesRemoved} duplicate rows filtered; ${snapshot.hygieneReport.balanceAnomalies.size} balance gaps flagged; ${snapshot.uncategorizedRecentCount} recent uncategorized entries (${formatMoney(c, snapshot.uncategorizedRecentAmount)}).")
        }

        // Chronological daily spending breakdown (fixes old Credit Card tail bug!)
        val chronologicalDesc = recentTransactions
            .withIndex()
            .filter { it.value.dateIso != "0000-00-00" }
            .sortedWith(compareByDescending<IndexedValue<TransactionRecord>> { it.value.dateIso }.thenByDescending { it.index })
            .map { it.value }

        val todayTxns = chronologicalDesc.filter { it.dateIso == todayIso }
        val yesterdayTxns = chronologicalDesc.filter { it.dateIso == yesterdayIso }
        sb.appendLine("- Today ($todayIso) Spending: ${formatMoney(c, snapshot.todaySpend)} (${todayTxns.size} txns)${if (todayTxns.isEmpty()) " — none synced yet" else ""}")
        todayTxns.take(10).forEach { tx -> sb.appendLine(formatDossierTxnLine(tx, c)) }
        sb.appendLine("- Yesterday ($yesterdayIso) Spending: ${formatMoney(c, snapshot.yesterdaySpend)} (${yesterdayTxns.size} txns)")
        yesterdayTxns.take(10).forEach { tx -> sb.appendLine(formatDossierTxnLine(tx, c)) }

        val last7DaysSummary = (0L..6L).mapNotNull { offset ->
            val dIso = today.minusDays(offset).format(isoFmt)
            val dayTxns = chronologicalDesc.filter { it.dateIso == dIso && it.accountType == AccountType.DEBIT }
            if (dayTxns.isEmpty()) null else {
                val spend = DayBookParserAndHygiene.round2(dayTxns.sumOf { it.debit })
                val inc = DayBookParserAndHygiene.round2(dayTxns.sumOf { it.credit })
                val incStr = if (inc > 0) "/+${inc.toInt()}" else ""
                "${dIso.takeLast(5)}:${spend.toInt()}$incStr"
            }
        }
        if (last7DaysSummary.isNotEmpty()) {
            sb.appendLine("- Last 7d Daily Debit/Credit ($c): ${last7DaysSummary.joinToString(" | ")}")
        }

        val printedIds = (todayTxns.take(10) + yesterdayTxns.take(10)).map { it.id }.toMutableSet()
        val recentEarlier = chronologicalDesc.filter { it.id !in printedIds }.take(10)
        if (recentEarlier.isNotEmpty()) {
            sb.appendLine("- Prior Recent Transactions (Newest First):")
            recentEarlier.forEach { tx ->
                printedIds += tx.id
                sb.appendLine(formatDossierTxnLine(tx, c))
            }
        }

        // Uncategorized entries needing user clarification (exclude already printed)
        val recent14Start = today.minusDays(13).format(isoFmt)
        val uncatRecent = chronologicalDesc
            .filter { it.id !in printedIds && it.dateIso >= recent14Start && it.category.equals("Uncategorized", ignoreCase = true) }
            .take(3)
        if (uncatRecent.isNotEmpty()) {
            sb.appendLine("- Uncategorized Entries to Clarify:")
            uncatRecent.forEach { tx ->
                printedIds += tx.id
                sb.appendLine(formatDossierTxnLine(tx, c))
            }
        }

        // Query-aware lookup across the full ledger so specific date/merchant/category questions always get exact rows
        val matched = findQueryMatchedTransactions(chronologicalDesc, userQuery, alreadyIncluded = printedIds).take(6)
        if (matched.isNotEmpty()) {
            sb.appendLine("- Query-Matched Historical Entries:")
            matched.forEach { tx -> sb.appendLine(formatDossierTxnLine(tx, c)) }
        }
        sb.appendLine()
        sb.appendLine("[2. PHYSICAL HEALTH & WATCH VITALS]")
        val peakStr = snapshot.peakHourToday?.let { " | Peak ${formatHourLabel(it)}" }.orEmpty()
        sb.appendLine("- Today Steps: ${snapshot.todaySteps} / ${profile.dailyStepGoal} (${String.format(Locale.US, "%.2f", snapshot.todayMetrics.distanceKm)} km, ${snapshot.todayMetrics.caloriesKcal} kcal)$peakStr | 7d Avg: ${snapshot.avgSteps7d} | Streak: ${snapshot.stepStreakDays}d")
        val vitals = snapshot.healthVitals
        if (vitals.hasAnyWatchData) {
            val parts = buildList {
                if (vitals.lastNightSleepMinutes > 0) add("Sleep ${formatDurationHoursMins(vitals.lastNightSleepMinutes)}")
                vitals.restingHeartRateBpm?.let { add("Resting HR $it bpm") }
                vitals.latestHeartRateBpm?.let { add("Latest HR $it bpm") }
                vitals.latestSpo2Percent?.let { add("SpO2 ${String.format(Locale.US, "%.1f", it)}%") }
                if (vitals.workoutsThisWeek > 0) add("Workouts 7d: ${vitals.workoutsThisWeek}")
            }
            sb.appendLine("- Watch Vitals: ${parts.joinToString(" | ")}")
        }

        sb.appendLine()
        sb.appendLine("[3. SCHEDULE & TASKS]")
        sb.appendLine("- Calendar Today: ${snapshot.todayEvents.size} | 7d: ${snapshot.upcomingEvents7d.size} | Pending Tasks: ${snapshot.pendingTasks.size} (Overdue: ${snapshot.overdueTasksCount})")
        snapshot.todayEvents.take(4).forEach { ev ->
            sb.appendLine("  * Today ${formatEventTimeRange(ev)} | ${ev.title.take(48)}")
        }
        snapshot.pendingTasks.take(5).forEach { task ->
            val dueLbl = task.dueAtMillis?.let { formatMillisDateTime(it) } ?: "No deadline"
            sb.appendLine("  * [${task.priority.label}] ${task.title.take(48)} ($dueLbl)")
        }

        if (snapshot.recentEmails.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[4. RECENT EMAIL ALERTS (${snapshot.unreadEmailsCount} unread, ${snapshot.urgentEmailsCount} urgent)]")
            snapshot.recentEmails.take(5).forEach { mail ->
                val stateLbl = if (mail.isRead) "READ" else if (mail.isUrgent) "URGENT" else "UNREAD"
                val snip = if (mail.snippet.isNotBlank()) " — ${mail.snippet.take(55)}" else ""
                sb.appendLine("  * [$stateLbl/${mail.tag.label}] ${mail.sender.take(28)}: ${mail.subject.take(50)}$snip")
            }
        }

        if (snapshot.insights.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[5. KEY ALERTS]")
            snapshot.insights.take(4).forEach { sb.appendLine("- ${it.title}: ${it.detail.take(120)}") }
        }

        val mkt = snapshot.marketSnapshot
        if (mkt.hasMarketData || snapshot.investableSurplus > 0.0) {
            sb.appendLine()
            sb.appendLine("[6. NEPAL IPO & NEPSE INVESTMENT RADAR]")
            sb.appendLine("- Investable Surplus: ${formatMoney(c, snapshot.investableSurplus)}${if (mkt.marketAsOfLabel.isNotBlank()) " | ${mkt.marketAsOfLabel}" else ""}")
            if (mkt.openIpos.isNotEmpty()) {
                mkt.openIpos.take(3).forEach { ipo ->
                    val aud = if (ipo.isGeneralPublic) "General" else ipo.shareType
                    val rat = if (ipo.rating.isNotBlank()) " | ${ipo.rating}" else ""
                    sb.appendLine("  * IPO OPEN ($aud): ${ipo.companyName} (${ipo.stockSymbol}) Min ${formatMoney(c, ipo.minInvestmentNpr)} Closes ${ipo.closingDateAd}$rat")
                }
            }
            if (mkt.upcomingIpos.isNotEmpty()) {
                mkt.upcomingIpos.take(2).forEach { ipo ->
                    val aud = if (ipo.isGeneralPublic) "General" else ipo.shareType
                    sb.appendLine("  * IPO UPCOMING ($aud): ${ipo.companyName} (${ipo.stockSymbol}) ${ipo.openingDateAd}..${ipo.closingDateAd}")
                }
            }
            if (mkt.promisingStocks.isNotEmpty()) {
                mkt.promisingStocks.take(4).forEach { st ->
                    val cr = String.format(Locale.US, "%.1f", st.turnoverNpr / 10_000_000.0)
                    sb.appendLine("  * NEPSE ${st.symbol}: ${formatMoney(c, st.ltp)} (${String.format(Locale.US, "%+.2f%%", st.percentChange)}) ${cr}Cr | ${st.signalTag}")
                }
            }
        }
        return sb.toString()
    }

    private fun formatDossierTxnLine(tx: TransactionRecord, currency: String): String {
        val kind = if (tx.debit > 0) "-${formatMoney(currency, tx.debit)}" else "+${formatMoney(currency, tx.credit)}"
        val acct = if (tx.accountType == AccountType.CREDIT) "[CC] " else ""
        return "  * ${tx.dateIso} | $acct${tx.normalizedDescription.ifBlank { "(blank)" }.take(34)} | $kind | ${tx.category}"
    }

    private fun findQueryMatchedTransactions(
        chronologicalDesc: List<TransactionRecord>,
        userQuery: String,
        alreadyIncluded: Set<String>
    ): List<TransactionRecord> {
        val q = userQuery.trim().lowercase(Locale.US)
        if (q.isEmpty()) return emptyList()
        val stopWords = setOf(
            "what", "when", "where", "how", "much", "did", "spend", "spent", "spending", "expense",
            "expenses", "today", "yesterday", "week", "month", "last", "about", "check", "show",
            "tell", "give", "please", "with", "from", "have", "that", "this", "your", "mine"
        )
        val tokens = Regex("[a-z0-9\\-]{3,}").findAll(q).map { it.value }.filterNot { it in stopWords }.toList()
        if (tokens.isEmpty()) return emptyList()
        return chronologicalDesc
            .filter { tx ->
                tx.id !in alreadyIncluded && tokens.any { tok ->
                    tx.dateIso.contains(tok, ignoreCase = true) ||
                        tx.normalizedDescription.contains(tok, ignoreCase = true) ||
                        tx.description.contains(tok, ignoreCase = true) ||
                        tx.category.contains(tok, ignoreCase = true)
                }
            }
            .take(12)
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

    /**
     * Produces a 100% deterministic, exact-figures summary directly from SQLite/live data
     * to display above Gemma 4's qualitative advice so financial amounts & dates are never garbled.
     */
    fun buildVerifiedFactsBlock(
        userQuery: String,
        snapshot: ExecutiveSnapshot,
        profile: SecretaryProfile,
        transactions: List<TransactionRecord>,
        today: LocalDate = LocalDate.now()
    ): String {
        val q = userQuery.lowercase(Locale.US)
        val c = profile.currencySymbol
        val matchesSpecificDomain = listOf(
            "daily expense", "daily spend", "day by day", "daily plan",
            "yesterday", "today's spend", "spent today", "spend today",
            "ipo", "nepse", "invest", "share", "stock",
            "leak", "category", "spend", "budget", "alcohol", "cigar", "tobacco",
            "ssf", "credit", "loan", "net worth", "balance",
            "email", "inbox", "mail", "gmail", "outlook",
            "schedule", "calendar", "meeting", "task", "work", "remind",
            "step", "walk", "physical", "exercise", "calor", "sleep", "heart", "watch"
        ).any { q.contains(it) }

        if (matchesSpecificDomain) {
            return buildDeterministicReply(
                userQuery = userQuery,
                snapshot = snapshot,
                profile = profile,
                transactions = transactions,
                today = today
            )
        }

        // General executive question: show compact exact key figures
        return buildString {
            appendLine("• Liquid Debit: ${formatMoney(c, snapshot.debitBalance)} | Credit Card: ${formatMoney(c, snapshot.creditCardBalance)} | Investable Surplus: ${formatMoney(c, snapshot.investableSurplus)}")
            appendLine("• Today (${today.format(isoFmt)}): ${formatMoney(c, snapshot.todaySpend)} | Yesterday: ${formatMoney(c, snapshot.yesterdaySpend)} | Daily Cap: ${formatMoney(c, snapshot.dailyBudgetAllowance)}/d")
            appendLine("• Active Month (${snapshot.currentMonthKey}): ${formatMoney(c, snapshot.currentMonthExpense)} spent (${snapshot.budgetUsedPercent.toInt()}% of ${formatMoney(c, profile.monthlySpendBudget)})")
            if (snapshot.ssfSummary.refundableAmount > 0.0) {
                appendLine("• SSF Net Equity: ${formatMoney(c, snapshot.ssfSummary.netEquity)} (Saved ${formatMoney(c, snapshot.ssfSummary.refundableAmount)} − Loan ${formatMoney(c, snapshot.ssfSummary.totalDueAmount)})")
            }
            append("• Steps: ${snapshot.todaySteps}/${profile.dailyStepGoal} | Unread Emails: ${snapshot.unreadEmailsCount} | Open Tasks: ${snapshot.pendingTasks.size}")
        }.trim()
    }

    private fun isTobaccoOrAlcoholCategory(category: String): Boolean =
        category.contains("Tobacco", ignoreCase = true) || category.contains("Alcohol", ignoreCase = true)

    /**
     * Repairs quantized GPU FP16 digit-token stutters (e.g. "Rs. 1,7300.00" -> "Rs. 1,730.00",
     * "Rs.1,1666.7" -> "Rs. 1,166.67", "2026-1111-25" -> "2026-11-25", "Rs. 0.000.00" -> "Rs. 0.00")
     * using both reference-grounded amount snapping and structural grammar fixes.
     */
    fun sanitizeGemmaNumericGlitches(raw: String, referenceText: String = ""): String {
        if (raw.isBlank()) return raw
        var out = raw

        // 1. Fix stuttered ISO dates like 2026-1111-25 or 202026-11-25
        out = out.replace(Regex("\\b202026\\b"), "2026")
        out = out.replace(Regex("\\b(20\\d{2})-111+-(0?\\d|[12]\\d|3[01])\\b"), "$1-11-$2")
        out = out.replace(Regex("\\b(20\\d{2})-122+-(0?\\d|[12]\\d|3[01])\\b"), "$1-12-$2")
        out = out.replace(Regex("\\b(20\\d{2})-00+(\\d)-(0?\\d|[12]\\d|3[01])\\b"), "$1-0$2-$3")

        // 2. Context-aware & structural fix for Rs. currency tokens
        val amountRegex = Regex("Rs\\.\\s*([\\d,]+(?:\\.\\d+)*)")
        val knownAmounts = if (referenceText.isNotBlank()) {
            amountRegex.findAll(referenceText).map { it.groupValues[1] }.distinct().toList()
        } else {
            emptyList()
        }
        val knownBySig = knownAmounts.groupBy { digitSignature(it) }
        val knownByIntSig = knownAmounts.groupBy { digitSignature(it.substringBefore(".")) }

        out = amountRegex.replace(out) { m ->
            val rawNum = m.groupValues[1]
            // Already an exact verified amount from reference text
            if (rawNum in knownAmounts) {
                return@replace "Rs. $rawNum"
            }

            // Detect malformed FP16 GPU digit stutters:
            // - 4+ digits after any comma (e.g. 1,7300.00 or 1,1666.7 or 1,0000.00)
            // - 4+ digits before a comma (e.g. 1000,1839.898)
            // - double decimal point (e.g. 930.0.000 or 0.000.00 or 145,108.4.47)
            // - 1 or 3+ digits after decimal point (e.g. 1,166.7 or 930.000)
            val isMalformed = rawNum.contains(Regex(",\\d{4,}")) ||
                rawNum.contains(Regex("\\d{4,},")) ||
                rawNum.count { it == '.' } > 1 ||
                rawNum.contains(Regex("\\.\\d{3,}$")) ||
                rawNum.contains(Regex("\\.\\d$"))

            if (isMalformed) {
                // (a) Try exact collapsed-digit signature match against verified reference amounts
                val sig = digitSignature(rawNum)
                val sigMatches = knownBySig[sig].orEmpty()
                if (sigMatches.size == 1) {
                    return@replace "Rs. ${sigMatches.first()}"
                }
                val intPart = rawNum.substringBefore(".")
                val intMatches = knownByIntSig[digitSignature(intPart)].orEmpty()
                if (intMatches.size == 1) {
                    return@replace "Rs. ${intMatches.first()}"
                }
                // Try best close match in knownAmounts when comma-group is malformed
                val bestKnown = findClosestReferenceAmount(rawNum, knownAmounts)
                if (bestKnown != null) {
                    return@replace "Rs. $bestKnown"
                }
                // (b) Structural fallback when reference doesn't contain this exact figure
                return@replace "Rs. ${repairMalformedAmountStructurally(rawNum)}"
            }
            m.value
        }

        // 3. Generic double-decimal & 3-zero decimal cleanup outside Rs. tokens
        out = out.replace(Regex("(\\d[\\d,]*)\\.0+\\.0+\\b"), "$1.00")
        out = out.replace(Regex("(\\d[\\d,]*)\\.(\\d)\\.\\2(\\d)\\b"), "$1.$2$3")
        out = out.replace(Regex("(\\d[\\d,]*)\\.000+\\b"), "$1.00")
        // Fix stuttered 4+ repeated leading digits before % (e.g. 44445.2% -> 445.2%)
        out = out.replace(Regex("\\b(\\d)\\1{3,}(\\d(?:\\.\\d+)?%)"), "$1$1$2")
        return out
    }

    private fun digitSignature(numStr: String): String {
        val digits = numStr.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        val sb = StringBuilder()
        var prev = '\u0000'
        for (ch in digits) {
            if (ch != prev) {
                sb.append(ch)
                prev = ch
            }
        }
        return sb.toString()
    }

    private fun findClosestReferenceAmount(malformed: String, knownAmounts: List<String>): String? {
        if (knownAmounts.isEmpty()) return null
        val badDigits = malformed.filter { it.isDigit() }
        val badSig = digitSignature(malformed)
        if (badDigits.isEmpty()) return null
        val badLeadLen = malformed.substringBefore(",").substringBefore(".").length
        var best: String? = null
        var bestScore = Int.MAX_VALUE
        for (cand in knownAmounts) {
            val candSig = digitSignature(cand)
            val candDigits = cand.filter { it.isDigit() }
            if (candDigits.isEmpty() || candDigits.first() != badDigits.first()) continue
            val sigLev = levenshteinSmall(badSig, candSig)
            val lenDiff = abs(badDigits.length - candDigits.length)
            val eligible = candSig == badSig ||
                (candDigits.length >= 4 && isSubsequence(candSig, badSig) && (badSig.length - candSig.length) <= 2 && lenDiff <= 4) ||
                (badDigits.length >= 4 && isSubsequence(badSig, candSig) && (candSig.length - badSig.length) <= 1 && lenDiff <= 2) ||
                (sigLev <= 1 && lenDiff <= 2)
            if (eligible) {
                val candLeadLen = cand.substringBefore(",").substringBefore(".").length
                val leadPenalty = if (malformed.contains(",") && cand.contains(",") && badLeadLen <= 3 && badLeadLen != candLeadLen) 2 else 0
                val score = sigLev * 2 + lenDiff + leadPenalty
                if (score < bestScore) {
                    bestScore = score
                    best = cand
                }
            }
        }
        return if (bestScore <= 8) best else null
    }

    private fun isSubsequence(short: String, long: String): Boolean {
        var i = 0
        for (ch in long) {
            if (i < short.length && short[i] == ch) i++
        }
        return i == short.length
    }

    private fun levenshteinSmall(a: String, b: String): Int {
        if (a == b) return 0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[b.length]
    }

    private fun repairMalformedAmountStructurally(rawNum: String): String {
        var s = rawNum
        // Collapse double decimals like 930.0.000 -> 930.00 or 145,108.4.47 -> 145,108.47
        s = s.replace(Regex("\\.0+\\.0+$"), ".00")
        s = s.replace(Regex("\\.(\\d)\\.\\1(\\d)$"), ".$1$2")
        // Collapse 3+ trailing zero decimals
        s = s.replace(Regex("\\.000+$"), ".00")
        // Fix 4-digit group after comma (e.g. 1,7300.00 -> 1,730.00, 1,1666.7 -> 1,166.67, 1,0000.00 -> 1,000.00)
        s = Regex("(\\d+),(\\d{4})(\\.\\d+)?").replace(s) { m ->
            val lead = m.groupValues[1]
            val four = m.groupValues[2]
            val dec = m.groupValues[3]
            val fixedThree = Regex("(\\d)\\1").replaceFirst(four, "$1").take(3)
            val fixedDec = when {
                dec.isEmpty() -> ""
                dec.length == 2 -> "${dec}0" // .7 -> .70 fallback
                dec.length > 3 -> dec.take(3)
                else -> dec
            }
            "$lead,$fixedThree$fixedDec"
        }
        // Fix 4-digit group before comma (e.g. 1000,183.98 -> 100,183.98)
        s = Regex("\\b(\\d{4}),(\\d{3})").replace(s) { m ->
            val fixedLead = Regex("(\\d)\\1").replaceFirst(m.groupValues[1], "$1")
            "$fixedLead,${m.groupValues[2]}"
        }
        return s
    }

    fun buildDeterministicReply(
        userQuery: String,
        snapshot: ExecutiveSnapshot,
        profile: SecretaryProfile,
        transactions: List<TransactionRecord> = emptyList(),
        today: LocalDate = LocalDate.now()
    ): String {
        val q = userQuery.lowercase(Locale.US)
        val c = profile.currencySymbol
        return when {
            q.contains("daily expense") || q.contains("daily spend") || q.contains("day by day") || q.contains("daily plan") -> {
                val recent7 = snapshot.dailyExpenses14d.takeLast(7).reversed()
                val dayLines = recent7.joinToString("\n") { d ->
                    val catHint = if (d.topCategory.isNotBlank()) " · ${d.topCategory}" else ""
                    "• ${d.dateIso} (${d.dayShortLabel}): ${formatMoney(c, d.totalSpend)} [${d.status.label}]$catHint"
                }
                val adviceLines = snapshot.dailyCoachingSuggestions.joinToString("\n") { "• $it" }
                buildString {
                    appendLine("Daily Expense Coach (Target ${formatMoney(c, snapshot.dailyBudgetAllowance)}/day · 7d Avg ${formatMoney(c, snapshot.avgDailySpend7d)}/day):")
                    if (snapshot.dailyCoachingHeadline.isNotBlank()) {
                        appendLine(snapshot.dailyCoachingHeadline)
                    }
                    appendLine()
                    appendLine("Last 7 Days:")
                    appendLine(dayLines)
                    if (adviceLines.isNotBlank()) {
                        appendLine()
                        appendLine("Suggestions:")
                        append(adviceLines)
                    }
                }.trim()
            }
            q.contains("yesterday") || q.contains("today's spend") || q.contains("spent today") || q.contains("spend today") -> {
                val targetDate = if (q.contains("yesterday")) today.minusDays(1) else today
                val targetIso = targetDate.format(isoFmt)
                val label = if (q.contains("yesterday")) "Yesterday ($targetIso)" else "Today ($targetIso)"
                val rows = transactions.filter { it.dateIso == targetIso }
                if (rows.isEmpty()) {
                    "No transactions are recorded for $label in your current Day Book snapshot. Tap Sync or Log in Finances if you have new entries to add."
                } else {
                    val totalDebit = DayBookParserAndHygiene.round2(rows.sumOf { it.debit })
                    val totalCredit = DayBookParserAndHygiene.round2(rows.sumOf { it.credit })
                    val lines = rows.joinToString("\n") { tx ->
                        val amt = if (tx.debit > 0) "-${formatMoney(c, tx.debit)}" else "+${formatMoney(c, tx.credit)}"
                        "• ${tx.normalizedDescription.ifBlank { "(No description)" }}: $amt (${tx.category})"
                    }
                    val uncatCount = rows.count { it.category.equals("Uncategorized", ignoreCase = true) }
                    val clarifyNote = if (uncatCount > 0) {
                        "\n\nClarification needed: $uncatCount entr${if (uncatCount == 1) "y is" else "ies are"} uncategorized — tap them in Finances to label what they were for."
                    } else ""
                    buildString {
                        appendLine("$label Spending Breakdown (${formatMoney(c, totalDebit)} spent${if (totalCredit > 0) " · ${formatMoney(c, totalCredit)} received" else ""}):")
                        append(lines)
                        if (clarifyNote.isNotEmpty()) append(clarifyNote)
                    }.trim()
                }
            }
            q.contains("ipo") || q.contains("nepse") || q.contains("invest") || q.contains("share") || q.contains("stock") -> {
                val mkt = snapshot.marketSnapshot
                val ipoLines = (mkt.openIpos + mkt.upcomingIpos).take(5).joinToString("\n") { ipo ->
                    val aud = if (ipo.isGeneralPublic) "General Public" else ipo.shareType
                    val rat = if (ipo.rating.isNotBlank()) " · ${ipo.rating}" else ""
                    "• [${ipo.status.uppercase(Locale.US)} · $aud] ${ipo.companyName} (${ipo.stockSymbol}) — Min ${formatMoney(c, ipo.minInvestmentNpr)}, closes ${ipo.closingDateAd}$rat"
                }.ifBlank { "• Tap 'Scan Web' in Finances -> Nepal IPO & NEPSE to load live Nepal IPO issues." }
                val stockLines = mkt.promisingStocks.take(5).joinToString("\n") { st ->
                    val cr = String.format(Locale.US, "%.2f", st.turnoverNpr / 10_000_000.0)
                    "• ${st.symbol} (${st.companyName}): ${formatMoney(c, st.ltp)} (${String.format(Locale.US, "%+.2f%%", st.percentChange)}) · Rs. ${cr} Cr · ${st.signalTag}"
                }.ifBlank { "• No secondary screen loaded yet — tap 'Scan Web' in Finances." }
                buildString {
                    appendLine("Nepal IPO & NEPSE Investment Radar (${mkt.marketAsOfLabel.ifBlank { "Live Screen" }}):")
                    appendLine("• Investable Surplus: ${formatMoney(c, snapshot.investableSurplus)} (Liquid ${formatMoney(c, snapshot.debitBalance)} − CC ${formatMoney(c, abs(snapshot.creditCardBalance))} − Monthly Buffer)")
                    appendLine()
                    appendLine("Open & Upcoming IPOs:")
                    appendLine(ipoLines)
                    appendLine()
                    appendLine("Liquid NEPSE Secondary Candidates:")
                    appendLine(stockLines)
                    appendLine()
                    append("Note: Automated NepaliPaisa/NEPSE screening for research only — verify prospectus, EPS/PE, and risk before investing.")
                }.trim()
            }
            q.contains("leak") || q.contains("category") || q.contains("spend") || q.contains("budget") || q.contains("alcohol") || q.contains("cigar") || q.contains("tobacco") -> {
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
                Balance Sheet & SSF Snapshot:
                • Debit Account: ${formatMoney(c, snapshot.debitBalance)}
                • Credit Card: ${formatMoney(c, snapshot.creditCardBalance)}
                • SSF Refundable Savings: ${formatMoney(c, ssf.refundableAmount)} (${ssf.contributedMonths} months)
                  – Pension: ${formatMoney(c, ssf.pensionAmount)} | Retirement: ${formatMoney(c, ssf.retirementAmount)} | Posted Return: ${formatMoney(c, ssf.interestAmount)}
                  – FY Accrued Interest: ${formatMoney(c, ssf.accruedInterest)}
                • SSF Special Loan (Applied Period: ${ssf.loanAppliedPeriod}): Total Due ${formatMoney(c, ssf.totalDueAmount)} (Principal ${formatMoney(c, ssf.duePrincipal)} + Interest ${formatMoney(c, ssf.accountInterest)})
                • SSF Net Equity: ${formatMoney(c, ssf.netEquity)} (With Accrued: ${formatMoney(c, ssf.netEquityWithAccrued)} | Interest Spread: ${formatSignedMoney(c, ssf.interestSpread)})
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
