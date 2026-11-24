package com.personalsecretary.app.data

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
enum class AccountType {
    DEBIT,
    CREDIT
}

@Serializable
data class TransactionRecord(
    val id: String,
    val accountType: AccountType,
    val dateIso: String,
    val rawDate: String,
    val description: String,
    val normalizedDescription: String,
    val debit: Double,
    val credit: Double,
    val balance: Double,
    val category: String,
    val autoCategorized: Boolean = false,
    val isBalanceAnomaly: Boolean = false,
    val balanceDiff: Double = 0.0
)

@Serializable
data class SsfSummary(
    val refundableAmount: Double = 0.0,
    val contributedMonths: Int = 0,
    val pensionAmount: Double = 0.0,
    val retirementAmount: Double = 0.0,
    val interestAmount: Double = 0.0,
    val accruedInterest: Double = 0.0,
    val loanAppliedPeriod: Int = 0,
    val duePrincipal: Double = 0.0,
    val accountInterest: Double = 0.0,
    val totalDueAmount: Double = 0.0,
    val netEquity: Double = 0.0,
    val netEquityWithAccrued: Double = 0.0,
    val interestSpread: Double = 0.0,
    val refErrorDetected: Boolean = false
)

@Serializable
data class EarningEntry(
    val monthLabel: String,
    val source: String,
    val amount: Double
)

@Serializable
data class AbbreviationRule(
    val abbreviation: String,
    val fullWord: String
)

@Serializable
data class BalanceAnomaly(
    val dateIso: String,
    val description: String,
    val expectedBalance: Double,
    val actualBalance: Double,
    val difference: Double
)

@Serializable
data class FinancialHygieneReport(
    val rawDebitRows: Int = 0,
    val uniqueDebitRows: Int = 0,
    val duplicatesRemoved: Int = 0,
    val autoCategorizedCount: Int = 0,
    val balanceAnomalies: List<BalanceAnomaly> = emptyList(),
    val ssfRefErrorFixedLocally: Boolean = false,
    val lastSyncedLabel: String = "Not synced yet"
)

@Serializable
enum class StepSource {
    HEALTH_CONNECT,
    HARDWARE_SENSOR,
    MANUAL
}

@Serializable
data class DailyStepRecord(
    val dateIso: String,
    val steps: Long,
    val source: StepSource,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

data class StepDerivedMetrics(
    val steps: Long,
    val distanceKm: Double,
    val caloriesKcal: Int,
    val activeMinutes: Int
) {
    companion object {
        fun fromSteps(steps: Long): StepDerivedMetrics {
            val safe = steps.coerceAtLeast(0L)
            val km = ((safe * 0.000762) * 100.0).roundToInt() / 100.0
            val kcal = (safe * 0.04).roundToInt()
            val mins = if (safe == 0L) 0 else maxOf(1, (safe / 95.0).roundToInt())
            return StepDerivedMetrics(
                steps = safe,
                distanceKm = km,
                caloriesKcal = kcal,
                activeMinutes = mins
            )
        }
    }
}

@Serializable
data class HealthVitalsSummary(
    val lastNightSleepMinutes: Int = 0,
    val avgSleepMinutes7d: Int = 0,
    val latestHeartRateBpm: Int? = null,
    val restingHeartRateBpm: Int? = null,
    val latestSpo2Percent: Double? = null,
    val workoutsThisWeek: Int = 0,
    val workoutMinutesThisWeek: Int = 0,
    val lastWorkoutTitle: String = "",
    val syncedAtMillis: Long = 0L
) {
    val hasAnyWatchData: Boolean
        get() = lastNightSleepMinutes > 0 ||
            avgSleepMinutes7d > 0 ||
            latestHeartRateBpm != null ||
            restingHeartRateBpm != null ||
            latestSpo2Percent != null ||
            workoutsThisWeek > 0
}

@Serializable
data class CalendarEventItem(
    val id: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    val location: String = "",
    val calendarName: String = ""
)

@Serializable
enum class TaskPriority(val label: String) {
    URGENT("Urgent"),
    HIGH("High"),
    NORMAL("Normal")
}

@Serializable
enum class TaskCategory(val label: String) {
    WORK("Work"),
    FINANCE("Finance"),
    HEALTH("Health"),
    PERSONAL("Personal")
}

@Serializable
enum class TaskRecurrence(val label: String) {
    NONE("One-time"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly")
}

@Serializable
data class SecretaryTask(
    val id: String,
    val title: String,
    val notes: String = "",
    val dueAtMillis: Long? = null,
    val priority: TaskPriority = TaskPriority.HIGH,
    val category: TaskCategory = TaskCategory.WORK,
    val recurrence: TaskRecurrence = TaskRecurrence.NONE,
    val isCompleted: Boolean = false,
    val reminderEnabled: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Serializable
enum class SecretaryVideoSlot(
    val slotKey: String,
    val title: String,
    val subtitle: String
) {
    IDLE("idle", "At Desk (Ambient)", "Default executive presence on Briefing & Consultation screens"),
    THINKING("thinking", "Reviewing Dossier", "Plays while Gemma 4 E4B analyzes your finances and activity"),
    SPEAKING("speaking", "Delivering Briefing", "Plays while streaming consultation advice or voice briefing"),
    ALERT("alert", "Executive Alert", "Optional state when a budget or balance alert is active")
}

@Serializable
enum class ChatRole {
    USER,
    ASSISTANT
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val engineTag: String = "Deterministic Consultant",
    val tokensPerSec: Float? = null
)

@Serializable
data class SecretaryProfile(
    val userName: String = "Owner",
    val secretaryName: String = "Clara",
    val currencySymbol: String = "Rs.",
    val monthlySpendBudget: Double = 60000.0,
    val dailyStepGoal: Int = 8000,
    val financialGoals: String = "Control discretionary spending, maintain healthy cash runway, and track long-term savings vs. loans.",
    val healthGoals: String = "Hit my daily step goal consistently and maintain active hourly movement.",
    val customDossierNotes: String = "",
    val spreadsheetId: String = "",
    val appsScriptUrl: String = "",
    val modelPath: String = "",
    val preferGpuBackend: Boolean = true,
    val autoLoadModelOnStart: Boolean = true,
    val voiceBriefingEnabled: Boolean = false,
    val showVideoPresence: Boolean = true,
    val videoMuted: Boolean = true,
    val githubRepo: String = "Siroj-ai/personal-secretary",
    val githubToken: String = "",
    val autoCheckUpdates: Boolean = true
)

data class MonthlyCashFlow(
    val monthKey: String,
    val income: Double,
    val expense: Double
) {
    val net: Double get() = income - expense
}

data class CategorySpend(
    val category: String,
    val amount: Double,
    val count: Int,
    val sharePercent: Double
)

enum class InsightSeverity {
    POSITIVE,
    INFO,
    CAUTION
}

enum class InsightDomain {
    FINANCIAL,
    PHYSICAL,
    SCHEDULE,
    CROSS_DOMAIN
}

data class ExecutiveInsight(
    val id: String,
    val domain: InsightDomain,
    val severity: InsightSeverity,
    val title: String,
    val detail: String,
    val recommendation: String
)

data class ExecutiveSnapshot(
    val debitBalance: Double = 0.0,
    val creditCardBalance: Double = 0.0,
    val ssfSummary: SsfSummary = SsfSummary(),
    val earningsTotal: Double = 0.0,
    val earningsMonthLabel: String = "",
    val netWorthEstimate: Double = 0.0,
    val currentMonthKey: String = "",
    val currentMonthIncome: Double = 0.0,
    val currentMonthExpense: Double = 0.0,
    val last30DaysIncome: Double = 0.0,
    val last30DaysExpense: Double = 0.0,
    val dailyBurnRate30d: Double = 0.0,
    val budgetUsedPercent: Double = 0.0,
    val tobaccoAlcohol30d: Double = 0.0,
    val topCategories30d: List<CategorySpend> = emptyList(),
    val monthlyCashFlows: List<MonthlyCashFlow> = emptyList(),
    val hygieneReport: FinancialHygieneReport = FinancialHygieneReport(),
    val todaySteps: Long = 0L,
    val todayStepSource: StepSource = StepSource.HARDWARE_SENSOR,
    val todayMetrics: StepDerivedMetrics = StepDerivedMetrics.fromSteps(0L),
    val stepsVsYesterdayPercent: Int? = null,
    val avgSteps7d: Long = 0L,
    val weeklyStepsTotal: Long = 0L,
    val stepStreakDays: Int = 0,
    val recentSteps7d: List<DailyStepRecord> = emptyList(),
    val hourlyStepsByDate: Map<String, List<Long>> = emptyMap(),
    val peakHourToday: Int? = null,
    val healthVitals: HealthVitalsSummary = HealthVitalsSummary(),
    val todayEvents: List<CalendarEventItem> = emptyList(),
    val upcomingEvents7d: List<CalendarEventItem> = emptyList(),
    val pendingTasks: List<SecretaryTask> = emptyList(),
    val overdueTasksCount: Int = 0,
    val dueTodayTasksCount: Int = 0,
    val insights: List<ExecutiveInsight> = emptyList()
)
