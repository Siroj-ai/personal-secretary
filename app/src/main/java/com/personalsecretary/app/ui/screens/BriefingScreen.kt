package com.personalsecretary.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalsecretary.app.data.ExecutiveAnalyticsEngine
import com.personalsecretary.app.data.ExecutiveSnapshot
import com.personalsecretary.app.data.SecretaryProfile
import com.personalsecretary.app.data.SecretaryVideoSlot
import com.personalsecretary.app.media.SecretaryPresenceBanner
import com.personalsecretary.app.ui.components.ExecutiveCard
import com.personalsecretary.app.ui.components.InsightCard
import com.personalsecretary.app.ui.components.ProgressRing
import com.personalsecretary.app.ui.components.SectionHeader
import com.personalsecretary.app.ui.components.formatExecutiveMarkdown
import com.personalsecretary.app.ui.theme.AccentGold
import com.personalsecretary.app.ui.theme.CardBorder
import com.personalsecretary.app.ui.theme.CardSurfaceElevated
import com.personalsecretary.app.ui.theme.CoralAccent
import com.personalsecretary.app.ui.theme.EmeraldAccent
import com.personalsecretary.app.ui.theme.ObsidianBg
import com.personalsecretary.app.ui.theme.SkyAccent
import com.personalsecretary.app.ui.theme.TextMuted
import com.personalsecretary.app.ui.theme.TextPrimary
import com.personalsecretary.app.ui.theme.TextSecondary
import com.personalsecretary.app.ui.theme.VioletAccent
import java.time.LocalTime
import java.util.Locale

@Composable
fun BriefingScreen(
    snapshot: ExecutiveSnapshot,
    profile: SecretaryProfile,
    activeVideoSlot: SecretaryVideoSlot,
    videoSlots: Map<String, String>,
    engineStatusLabel: String,
    briefingText: String,
    isGeneratingBriefing: Boolean,
    isSpeakingVoice: Boolean,
    isSyncingAll: Boolean,
    availableOtaVersion: String?,
    onOpenOtaSetup: () -> Unit,
    onGenerateBriefing: () -> Unit,
    onSpeakBriefing: (String) -> Unit,
    onStopSpeaking: () -> Unit,
    onRefreshAll: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenAdvisor: (String?) -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenFinances: () -> Unit,
    onOpenPhysical: () -> Unit
) {
    val cur = profile.currencySymbol
    val hour = LocalTime.now().hour
    val greeting = when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }

    val resolvedVideoPath = videoSlots[activeVideoSlot.slotKey] ?: videoSlots[SecretaryVideoSlot.IDLE.slotKey]
    val stepProgress = if (profile.dailyStepGoal > 0) {
        (snapshot.todaySteps.toFloat() / profile.dailyStepGoal.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val budgetProgress = (snapshot.budgetUsedPercent / 100.0).toFloat().coerceIn(0f, 1f)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "$greeting, ${profile.userName}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Executive Finances · Schedule · Health",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
                OutlinedButton(
                    onClick = onRefreshAll,
                    enabled = !isSyncingAll,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (isSyncingAll) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = AccentGold)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Sync")
                }
            }
        }

        // OTA Update Available Banner
        if (!availableOtaVersion.isNullOrBlank()) {
            item {
                Surface(
                    onClick = onOpenOtaSetup,
                    shape = RoundedCornerShape(14.dp),
                    color = CardSurfaceElevated,
                    border = BorderStroke(1.dp, AccentGold),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = AccentGold)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Update v$availableOtaVersion Available",
                                style = MaterialTheme.typography.titleSmall,
                                color = AccentGold
                            )
                            Text(
                                text = "Tap to review release notes and install OTA via GitHub",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }

        // Google Flow Secretary Presence Banner (optional via Setup toggle)
        if (profile.showVideoPresence) {
            item {
                SecretaryPresenceBanner(
                    secretaryName = profile.secretaryName,
                    activeSlot = activeVideoSlot,
                    videoPath = resolvedVideoPath,
                    statusBadge = if (isSpeakingVoice) "Speaking Live" else engineStatusLabel,
                    isMuted = profile.videoMuted,
                    onToggleMute = onToggleMute,
                    height = 196.dp
                )
            }
        }

        // Three-Pillar Pulse Cards
        item {
            SectionHeader(
                title = "Today's Executive Pulse",
                subtitle = "Tap any pillar to inspect or update"
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillarPulseRow(
                    icon = Icons.Default.Wallet,
                    iconTint = EmeraldAccent,
                    title = "Financial Health",
                    headline = ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.debitBalance),
                    subtitle = if (snapshot.hygieneReport.uniqueDebitRows > 0) {
                        "${snapshot.currentMonthKey}: ${ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.currentMonthExpense)} spent (${snapshot.budgetUsedPercent.toInt()}% of budget)"
                    } else {
                        "Connect Day Book in Setup to sync balances & spending"
                    },
                    ringProgress = budgetProgress,
                    ringColor = if (snapshot.budgetUsedPercent >= 85.0) CoralAccent else EmeraldAccent,
                    ringLabel = "${snapshot.budgetUsedPercent.toInt()}%",
                    onClick = onOpenFinances
                )

                val emailPill = if (snapshot.unreadEmailsCount > 0) {
                    " · ${snapshot.unreadEmailsCount} email${if (snapshot.unreadEmailsCount == 1) "" else "s"}"
                } else ""
                PillarPulseRow(
                    icon = Icons.AutoMirrored.Filled.EventNote,
                    iconTint = AccentGold,
                    title = "Schedule, Work & Inbox",
                    headline = "${snapshot.todayEvents.size} event${if (snapshot.todayEvents.size == 1) "" else "s"} · ${snapshot.pendingTasks.size} open$emailPill",
                    subtitle = when {
                        snapshot.urgentEmailsCount > 0 -> "${snapshot.urgentEmailsCount} urgent email alert${if (snapshot.urgentEmailsCount == 1) "" else "s"} in Inbox"
                        snapshot.overdueTasksCount > 0 -> "${snapshot.overdueTasksCount} overdue item${if (snapshot.overdueTasksCount == 1) "" else "s"} awaiting review"
                        snapshot.todayEvents.isNotEmpty() -> "Next: ${snapshot.todayEvents.first().title} (${ExecutiveAnalyticsEngine.formatEventTimeRange(snapshot.todayEvents.first())})"
                        snapshot.pendingTasks.isNotEmpty() -> "Top task: ${snapshot.pendingTasks.first().title}"
                        else -> "Connect calendar, email alerts, or add work reminders"
                    },
                    ringProgress = when {
                        snapshot.overdueTasksCount > 0 || snapshot.urgentEmailsCount > 0 -> 1f
                        snapshot.pendingTasks.isEmpty() && snapshot.todayEvents.isEmpty() && snapshot.unreadEmailsCount == 0 -> 0f
                        else -> 0.65f
                    },
                    ringColor = if (snapshot.overdueTasksCount > 0 || snapshot.urgentEmailsCount > 0) CoralAccent else AccentGold,
                    ringLabel = if (snapshot.overdueTasksCount + snapshot.urgentEmailsCount > 0) {
                        "${snapshot.overdueTasksCount + snapshot.urgentEmailsCount}!"
                    } else {
                        "${snapshot.todayEvents.size + snapshot.dueTodayTasksCount + snapshot.unreadEmailsCount}"
                    },
                    onClick = onOpenSchedule
                )

                val stepRingLabel = when {
                    snapshot.todaySteps > 0L && (stepProgress * 100).toInt() == 0 -> "<1%"
                    else -> "${(stepProgress * 100).toInt()}%"
                }
                val watchExtra = if (snapshot.healthVitals.lastNightSleepMinutes > 0) {
                    " · Sleep ${ExecutiveAnalyticsEngine.formatDurationHoursMins(snapshot.healthVitals.lastNightSleepMinutes)}"
                } else ""
                PillarPulseRow(
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    iconTint = EmeraldAccent,
                    title = "Health & Watch (OP 13)",
                    headline = "${String.format(Locale.US, "%,d", snapshot.todaySteps)} steps",
                    subtitle = "${String.format(Locale.US, "%.2f", snapshot.todayMetrics.distanceKm)} km · ${snapshot.todayMetrics.caloriesKcal} kcal · Goal ${String.format(Locale.US, "%,d", profile.dailyStepGoal)}$watchExtra",
                    ringProgress = if (snapshot.todaySteps > 0L) stepProgress.coerceAtLeast(0.02f) else 0f,
                    ringColor = if (stepProgress >= 1f) EmeraldAccent else SkyAccent,
                    ringLabel = stepRingLabel,
                    onClick = onOpenPhysical
                )
            }
        }

        // Daily Expense Coach Snapshot
        if (snapshot.dailyExpenses14d.isNotEmpty()) {
            item {
                val dailyOver = snapshot.dailyBudgetAllowance > 0.0 &&
                    (snapshot.todaySpend > snapshot.dailyBudgetAllowance || snapshot.avgDailySpend7d > snapshot.dailyBudgetAllowance * 1.15)
                ExecutiveCard(
                    borderColor = if (dailyOver) CoralAccent.copy(alpha = 0.45f) else EmeraldAccent.copy(alpha = 0.4f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Daily Expenses & Coach",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Cap ${cur} ${String.format(Locale.US, "%,.0f", snapshot.dailyBudgetAllowance)}/d · 7d avg ${cur} ${String.format(Locale.US, "%,.0f", snapshot.avgDailySpend7d)}/d",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenFinances,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("Details", maxLines = 1, softWrap = false)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = CardSurfaceElevated,
                            border = BorderStroke(1.dp, CardBorder),
                            modifier = Modifier.weight(1f).clickable(onClick = onOpenFinances)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Today", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text(
                                    text = ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.todaySpend),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (snapshot.dailyBudgetAllowance > 0.0 && snapshot.todaySpend > snapshot.dailyBudgetAllowance) CoralAccent else EmeraldAccent,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = CardSurfaceElevated,
                            border = BorderStroke(1.dp, CardBorder),
                            modifier = Modifier.weight(1f).clickable(onClick = onOpenFinances)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Yesterday", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text(
                                    text = ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.yesterdaySpend),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (snapshot.dailyBudgetAllowance > 0.0 && snapshot.yesterdaySpend > snapshot.dailyBudgetAllowance) CoralAccent else TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (snapshot.dailyCoachingHeadline.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = snapshot.dailyCoachingHeadline,
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentGold,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    snapshot.dailyCoachingSuggestions.firstOrNull()?.let { topTip ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "• $topTip",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            onOpenAdvisor("Analyze my daily expenses over the last 7 days against my daily cap and suggest concrete ways to improve today's spending.")
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp), tint = AccentGold)
                        Spacer(Modifier.width(6.dp))
                        Text("Ask ${profile.secretaryName} for Daily Advice", maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        // Executive Briefing Card
        item {
            ExecutiveCard(borderColor = AccentGold.copy(alpha = 0.45f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentGold, modifier = Modifier.size(18.dp))
                        Text(
                            text = "${profile.secretaryName}'s Briefing",
                            style = MaterialTheme.typography.titleMedium,
                            color = AccentGold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val resolvedBriefing = briefingText.ifBlank {
                        ExecutiveAnalyticsEngine.buildDeterministicBriefing(snapshot, profile)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = {
                                if (isSpeakingVoice) onStopSpeaking() else onSpeakBriefing(resolvedBriefing)
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = if (isSpeakingVoice) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (isSpeakingVoice) "Stop speaking" else "Read briefing aloud",
                                tint = if (isSpeakingVoice) CoralAccent else AccentGold,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Button(
                            onClick = onGenerateBriefing,
                            enabled = !isGeneratingBriefing,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (isGeneratingBriefing) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = ObsidianBg)
                                Spacer(Modifier.width(6.dp))
                                Text("Briefing...", maxLines = 1, softWrap = false)
                            } else {
                                Text("Generate", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val resolvedBriefingText = briefingText.ifBlank {
                    ExecutiveAnalyticsEngine.buildDeterministicBriefing(snapshot, profile)
                }
                val formattedBriefing = remember(resolvedBriefingText) {
                    formatExecutiveMarkdown(
                        raw = resolvedBriefingText,
                        boldColor = TextPrimary,
                        headingColor = AccentGold
                    )
                }
                Text(
                    text = formattedBriefing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onOpenAdvisor("Give me a concise action plan across my schedule, work tasks, finances, and health for the next 48 hours.") },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("48h Plan", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { onOpenAdvisor("Brief my schedule today, pending work, spending status, and steps.") },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Consult ${profile.secretaryName}", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // Priority Alerts & Insights
        if (snapshot.insights.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Priority Alerts & Insights",
                    subtitle = "Computed locally across finances, schedule, inbox & health"
                )
            }
            items(snapshot.insights, key = { it.id }) { insight ->
                InsightCard(insight = insight)
            }
        }
    }
}

@Composable
private fun PillarPulseRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    headline: String,
    subtitle: String,
    ringProgress: Float,
    ringColor: Color,
    ringLabel: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = CardSurfaceElevated,
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = iconTint.copy(alpha = 0.14f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.padding(9.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    Text(headline, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 2)
                }
            }
            Spacer(Modifier.width(10.dp))
            ProgressRing(
                progress = ringProgress,
                ringColor = ringColor,
                centerText = ringLabel
            )
        }
    }
}
