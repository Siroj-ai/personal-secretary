package com.personalsecretary.app.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object ExecutiveAnalyticsEngine {

    private val isoFmt = DateTimeFormatter.ISO_LOCAL_DATE

    fun computeSnapshot(
        transactions: List<TransactionRecord>,
        ssf: SsfSummary,
        earnings: List<EarningEntry>,
        hygiene: FinancialHygieneReport,
        steps: List<DailyStepRecord>,
        checkIns: List<MentalCheckIn>,
        profile: SecretaryProfile,
        today: LocalDate = LocalDate.now()
    ): ExecutiveSnapshot {
        val todayIso = today.format(isoFmt)
        val thirtyDaysAgoIso = today.minusDays(30).format(isoFmt)
        val fourteenDaysAgoIso = today.minusDays(14).format(isoFmt)
        val sevenDaysAgoIso = today.minusDays(6).format(isoFmt)
        val currentMonthKey = todayIso.substring(0, 7)

        val debitTxns = transactions.filter { it.accountType == AccountType.DEBIT }
        val creditTxns = transactions.filter { it.accountType == AccountType.CREDIT }

        val debitBalance = debitTxns.lastOrNull()?.balance ?: 0.0
        val creditBalance = creditTxns.lastOrNull()?.balance ?: 0.0
        val earningsTotal = earnings.sumOf { it.amount }
        val earningsMonthLabel = earnings.firstOrNull()?.monthLabel.orEmpty()

        val netWorth = DayBookParserAndHygiene.round2(debitBalance + creditBalance + ssf.netEquity)

        // Anchor 30d window to the latest transaction date if today is far ahead of imported data
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
            stepsByDate[d] ?: DailyStepRecord(d, 0L, StepSource.HARDWARE_SENSOR, 0L)
        }
        val todayStepRec = stepsByDate[todayIso] ?: recent7d.last()
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

        // Mental check-ins summary
        val recent7CheckIns = checkIns.filter { it.dateIso >= sevenDaysAgoIso }
        val recent14CheckIns = checkIns.filter { it.dateIso >= fourteenDaysAgoIso }
        val avgMood = if (recent7CheckIns.isNotEmpty()) recent7CheckIns.map { it.mood }.average().toFloat() else 0f
        val avgEnergy = if (recent7CheckIns.isNotEmpty()) recent7CheckIns.map { it.energy }.average().toFloat() else 0f
        val avgStress = if (recent7CheckIns.isNotEmpty()) recent7CheckIns.map { it.stress }.average().toFloat() else 0f
        val avgSleep = if (recent7CheckIns.isNotEmpty()) recent7CheckIns.map { it.sleepHours.toDouble() }.average().toFloat() else 0f

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
            recentCheckIns = recent14CheckIns,
            debitTxns = debitTxns,
            stepsByDate = stepsByDate,
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
            avgSteps7d = avgSteps7d,
            stepStreakDays = streak,
            recentSteps7d = recent7d,
            latestCheckIn = checkIns.firstOrNull(),
            avgMood7d = avgMood,
            avgEnergy7d = avgEnergy,
            avgStress7d = avgStress,
            avgSleep7d = avgSleep,
            checkInCount14d = recent14CheckIns.size,
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
        recentCheckIns: List<MentalCheckIn>,
        debitTxns: List<TransactionRecord>,
        stepsByDate: Map<String, DailyStepRecord>,
        profile: SecretaryProfile
    ): List<ExecutiveInsight> {
        val cur = profile.currencySymbol
        val list = mutableListOf<ExecutiveInsight>()

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
                recommendation = "Cutting this by 30% would preserve ~${formatMoney(cur, tobaccoAlcohol30d * 0.3)}/month and support lung & stress recovery."
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

        // Physical insight
        val stepPct = if (profile.dailyStepGoal > 0) (todaySteps * 100 / profile.dailyStepGoal).toInt() else 0
        if (todaySteps == 0L && avgSteps7d == 0L) {
            list += ExecutiveInsight(
                id = "steps_setup",
                domain = InsightDomain.PHYSICAL,
                severity = InsightSeverity.INFO,
                title = "Connect OnePlus 13 Steps",
                detail = "Grant Health Connect or Activity Recognition permission so your secretary can monitor daily movement automatically.",
                recommendation = "Open Health & Desk -> Physical to authorize Health Connect and enable ColorOS 17 background activity."
            )
        } else if (stepPct >= 100) {
            list += ExecutiveInsight(
                id = "steps_hit",
                domain = InsightDomain.PHYSICAL,
                severity = InsightSeverity.POSITIVE,
                title = "Daily Step Goal Achieved ($todaySteps steps)",
                detail = "You have met your ${profile.dailyStepGoal}-step target today (7-day avg: $avgSteps7d steps).",
                recommendation = "Stay hydrated and protect your sleep window tonight."
            )
        } else {
            val remaining = (profile.dailyStepGoal - todaySteps).coerceAtLeast(0L)
            list += ExecutiveInsight(
                id = "steps_pace",
                domain = InsightDomain.PHYSICAL,
                severity = InsightSeverity.INFO,
                title = "$remaining Steps Remaining Today",
                detail = "Currently at $todaySteps / ${profile.dailyStepGoal} steps ($stepPct%). A brisk 20-minute walk adds roughly 2,000 steps.",
                recommendation = "Schedule a short walk after your next work block to clear cortisol and hit target."
            )
        }

        // Cross-domain Stress vs Spending correlation
        if (recentCheckIns.size >= 2 && debitTxns.isNotEmpty()) {
            val dailySpend = debitTxns.groupBy { it.dateIso }.mapValues { (_, txs) -> txs.sumOf { it.debit } }
            val highStressDates = recentCheckIns.filter { it.stress >= 4 }.map { it.dateIso }.distinct()
            val calmDates = recentCheckIns.filter { it.stress <= 2 }.map { it.dateIso }.distinct()
            if (highStressDates.isNotEmpty() && calmDates.isNotEmpty()) {
                val highAvg = highStressDates.mapNotNull { dailySpend[it] }.average().takeIf { !it.isNaN() }
                val calmAvg = calmDates.mapNotNull { dailySpend[it] }.average().takeIf { !it.isNaN() }
                if (highAvg != null && calmAvg != null && highAvg > calmAvg * 1.2) {
                    list += ExecutiveInsight(
                        id = "stress_spend_corr",
                        domain = InsightDomain.CROSS_DOMAIN,
                        severity = InsightSeverity.CAUTION,
                        title = "Stress-Linked Spending Pattern",
                        detail = "On high-stress days your average debit spend is ${formatMoney(cur, highAvg)} vs. ${formatMoney(cur, calmAvg)} on calm days.",
                        recommendation = "When stress hits 4+, pause non-essential purchases for 2 hours and take a 10-minute walk instead."
                    )
                }
            }

            // Steps vs Mood correlation
            val activeMoods = recentCheckIns.filter { (stepsByDate[it.dateIso]?.steps ?: 0L) >= profile.dailyStepGoal * 0.7 }.map { it.mood }
            val lowStepMoods = recentCheckIns.filter { (stepsByDate[it.dateIso]?.steps ?: 0L) in 1L..(profile.dailyStepGoal * 0.4).toLong() }.map { it.mood }
            if (activeMoods.isNotEmpty() && lowStepMoods.isNotEmpty() && activeMoods.average() > lowStepMoods.average() + 0.4) {
                list += ExecutiveInsight(
                    id = "steps_mood_corr",
                    domain = InsightDomain.CROSS_DOMAIN,
                    severity = InsightSeverity.POSITIVE,
                    title = "Movement Lifts Your Mood",
                    detail = "Your average mood is ${String.format(Locale.US, "%.1f", activeMoods.average())}/5 on active days vs. ${String.format(Locale.US, "%.1f", lowStepMoods.average())}/5 on low-step days.",
                    recommendation = "Treat a midday walk as a cognitive reset, not just exercise."
                )
            }
        }

        // High stress alert
        val latest = recentCheckIns.firstOrNull()
        if (latest != null && latest.stress >= 4) {
            list += ExecutiveInsight(
                id = "mental_stress_high",
                domain = InsightDomain.MENTAL,
                severity = InsightSeverity.CAUTION,
                title = "Elevated Stress Logged (${latest.stress}/5)",
                detail = "Recent check-in shows mood ${latest.mood}/5, energy ${latest.energy}/5, sleep ${latest.sleepHours}h.",
                recommendation = "Trim non-urgent tasks today and aim for ${profile.sleepGoalHours}h sleep tonight."
            )
        }

        return list.take(6)
    }

    fun buildGemmaDossier(
        snapshot: ExecutiveSnapshot,
        profile: SecretaryProfile,
        recentTransactions: List<TransactionRecord>,
        recentCheckIns: List<MentalCheckIn>
    ): String {
        val c = profile.currencySymbol
        val sb = StringBuilder()
        sb.appendLine("=== EXECUTIVE CLIENT DOSSIER (LIVE VERIFIED METRICS) ===")
        sb.appendLine("Client: ${profile.userName} | Currency: $c | Active Month: ${snapshot.currentMonthKey}")
        sb.appendLine("Financial Goals: ${profile.financialGoals}")
        sb.appendLine("Health Goals: ${profile.healthGoals}")
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
        sb.appendLine("[2. PHYSICAL HEALTH - ONEPLUS 13 STEPS]")
        sb.appendLine("- Today Steps: ${snapshot.todaySteps} / ${profile.dailyStepGoal} (source: ${snapshot.todayStepSource.name})")
        sb.appendLine("- 7-Day Average: ${snapshot.avgSteps7d} steps/day | Goal Streak: ${snapshot.stepStreakDays} days")
        val stepSeries = snapshot.recentSteps7d.joinToString(", ") { "${it.dateIso.takeLast(5)}:${it.steps}" }
        sb.appendLine("- Last 7 Days: $stepSeries")
        sb.appendLine()
        sb.appendLine("[3. MENTAL HEALTH & ENERGY]")
        val latest = snapshot.latestCheckIn
        if (latest != null) {
            sb.appendLine("- Latest Check-in (${latest.dateIso}): Mood ${latest.mood}/5, Energy ${latest.energy}/5, Stress ${latest.stress}/5, Sleep ${latest.sleepHours}h, Tags: ${latest.tags.joinToString(",")}, Note: ${latest.note.take(140)}")
            sb.appendLine("- 7-Day Averages: Mood ${fmt1(snapshot.avgMood7d)}/5, Energy ${fmt1(snapshot.avgEnergy7d)}/5, Stress ${fmt1(snapshot.avgStress7d)}/5, Sleep ${fmt1(snapshot.avgSleep7d)}h")
        } else {
            sb.appendLine("- No mental check-ins logged yet.")
        }
        if (recentCheckIns.size > 1) {
            sb.appendLine("- Recent Check-ins: " + recentCheckIns.take(4).joinToString(" | ") {
                "${it.dateIso}(M${it.mood}/E${it.energy}/S${it.stress}/${it.sleepHours}h)"
            })
        }
        if (snapshot.insights.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[4. DETECTED EXECUTIVE ALERTS]")
            snapshot.insights.forEach { sb.appendLine("- [${it.domain}] ${it.title}: ${it.detail}") }
        }
        return sb.toString()
    }

    fun buildDeterministicBriefing(snapshot: ExecutiveSnapshot, profile: SecretaryProfile): String {
        val c = profile.currencySymbol
        val sb = StringBuilder()
        sb.appendLine("Executive Briefing for ${profile.userName}")
        sb.appendLine()
        sb.appendLine("1. Financial Position")
        if (snapshot.hygieneReport.uniqueDebitRows > 0) {
            sb.appendLine("• Liquid balance stands at ${formatMoney(c, snapshot.debitBalance)}, with Credit Card at ${formatMoney(c, snapshot.creditCardBalance)} and SSF net equity at ${formatMoney(c, snapshot.ssfSummary.netEquity)}.")
            sb.appendLine("• Active month (${snapshot.currentMonthKey}): ${formatMoney(c, snapshot.currentMonthExpense)} spent vs. ${formatMoney(c, snapshot.currentMonthIncome)} received (${snapshot.budgetUsedPercent}% of your ${formatMoney(c, profile.monthlySpendBudget)} budget; 30d burn ${formatMoney(c, snapshot.dailyBurnRate30d)}/day).")
            snapshot.topCategories30d.firstOrNull()?.let { top ->
                sb.appendLine("• Top 30-day category: ${top.category} at ${formatMoney(c, top.amount)} (${top.sharePercent}%).")
            }
        } else {
            sb.appendLine("• Day Book is not synced yet. Connect your Apps Script URL or import a CSV in Setup to activate live financial auditing.")
        }
        sb.appendLine()
        sb.appendLine("2. Physical Activity (OnePlus 13)")
        val rem = (profile.dailyStepGoal - snapshot.todaySteps).coerceAtLeast(0L)
        sb.appendLine("• Today: ${snapshot.todaySteps} / ${profile.dailyStepGoal} steps (7d avg: ${snapshot.avgSteps7d}, streak: ${snapshot.stepStreakDays}d). ${if (rem == 0L) "Target achieved." else "$rem steps remaining."}")
        sb.appendLine()
        sb.appendLine("3. Mental Bandwidth")
        val latest = snapshot.latestCheckIn
        if (latest != null) {
            sb.appendLine("• Latest check-in (${latest.dateIso}): Mood ${latest.mood}/5, Energy ${latest.energy}/5, Stress ${latest.stress}/5, Sleep ${latest.sleepHours}h.")
        } else {
            sb.appendLine("• No mental check-in recorded yet today. Log a 15-second check-in so I can correlate stress and energy with spending and steps.")
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
            q.contains("step") || q.contains("walk") || q.contains("physical") || q.contains("exercise") -> {
                val rem = (profile.dailyStepGoal - snapshot.todaySteps).coerceAtLeast(0L)
                val series = snapshot.recentSteps7d.joinToString(" | ") { "${it.dateIso.takeLast(5)}: ${it.steps}" }
                """
                OnePlus 13 Activity Audit:
                • Today: ${snapshot.todaySteps} / ${profile.dailyStepGoal} steps (${if (rem == 0L) "Goal achieved" else "$rem remaining"})
                • 7-Day Average: ${snapshot.avgSteps7d} steps/day | Active Streak: ${snapshot.stepStreakDays} days
                • Last 7 Days: $series

                Recommendation: ${if (rem > 0) "Split the remaining $rem steps into a 15–20 minute brisk walk (~2,000 steps) to boost energy and focus." else "Great consistency today — prioritize recovery and ${profile.sleepGoalHours}h sleep tonight."}
                """.trimIndent()
            }
            q.contains("stress") || q.contains("mental") || q.contains("mood") || q.contains("anxious") || q.contains("overwhelm") || q.contains("tired") -> {
                val latest = snapshot.latestCheckIn
                val status = if (latest != null) {
                    "Latest check-in (${latest.dateIso}): Mood ${latest.mood}/5, Energy ${latest.energy}/5, Stress ${latest.stress}/5, Sleep ${latest.sleepHours}h."
                } else {
                    "You haven't logged a check-in today yet."
                }
                """
                Let's slow down and reset methodically.
                $status

                Immediate 10-minute executive reset:
                1. Step away from screens and take 6 slow breaths (4s inhale, 6s exhale).
                2. Walk for 10 minutes (~1,000 steps) — your liquid balance is ${formatMoney(c, snapshot.debitBalance)}, so no urgent decision needs to be forced right now.
                3. Pick only the single highest-impact task for the next hour and defer the rest.

                (Load gemma-4-E4B-it.litertlm in Setup for deeper, conversational on-device coaching.)
                """.trimIndent()
            }
            else -> buildDeterministicBriefing(snapshot, profile)
        }
    }

    fun formatMoney(currency: String, amount: Double): String {
        val sign = if (amount < 0) "-" else ""
        return "$sign$currency ${String.format(Locale.US, "%,.2f", abs(amount))}"
    }

    fun formatSignedMoney(currency: String, amount: Double): String {
        val sign = if (amount >= 0) "+" else "-"
        return "$sign$currency ${String.format(Locale.US, "%,.2f", abs(amount))}"
    }

    private fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)
}
