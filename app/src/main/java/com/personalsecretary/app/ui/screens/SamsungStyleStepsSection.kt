package com.personalsecretary.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personalsecretary.app.data.DailyStepRecord
import com.personalsecretary.app.data.ExecutiveAnalyticsEngine
import com.personalsecretary.app.data.ExecutiveSnapshot
import com.personalsecretary.app.data.SecretaryProfile
import com.personalsecretary.app.data.StepDerivedMetrics
import com.personalsecretary.app.health.StepPermissionStatus
import com.personalsecretary.app.ui.components.ExecutiveCard
import com.personalsecretary.app.ui.components.SectionHeader
import com.personalsecretary.app.ui.components.StepsWeekChart
import com.personalsecretary.app.ui.theme.AccentGold
import com.personalsecretary.app.ui.theme.CardBorder
import com.personalsecretary.app.ui.theme.CardSurface
import com.personalsecretary.app.ui.theme.CardSurfaceElevated
import com.personalsecretary.app.ui.theme.CoralAccent
import com.personalsecretary.app.ui.theme.EmeraldAccent
import com.personalsecretary.app.ui.theme.ObsidianBg
import com.personalsecretary.app.ui.theme.SkyAccent
import com.personalsecretary.app.ui.theme.TextMuted
import com.personalsecretary.app.ui.theme.TextPrimary
import com.personalsecretary.app.ui.theme.TextSecondary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val SamsungGreen = Color(0xFF1EE38A)
private val SamsungMint = Color(0xFF34D399)
private val SamsungCyan = Color(0xFF38BDF8)

@Composable
fun SamsungStyleStepsTabContent(
    snapshot: ExecutiveSnapshot,
    profile: SecretaryProfile,
    stepPermissions: StepPermissionStatus,
    onRequestHealthConnectPermissions: () -> Unit,
    onRequestActivityRecognition: () -> Unit,
    onOpenColorOsBatterySettings: () -> Unit,
    onOpenHealthConnectSettings: () -> Unit,
    onSyncSteps: () -> Unit,
    onLogManualSteps: (Long) -> Unit
) {
    val weekRecords = snapshot.recentSteps7d
    val defaultIndex = (weekRecords.size - 1).coerceAtLeast(0)
    var selectedDayIndex by remember(weekRecords.size) { mutableIntStateOf(defaultIndex) }
    val selectedRecord = weekRecords.getOrNull(selectedDayIndex)
    val isSelectedToday = selectedDayIndex == defaultIndex

    val displayedSteps = selectedRecord?.steps ?: snapshot.todaySteps
    val displayedDateIso = selectedRecord?.dateIso ?: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val metrics = remember(displayedSteps) { StepDerivedMetrics.fromSteps(displayedSteps) }
    val hourlyForSelectedDay = remember(snapshot.hourlyStepsByDate, displayedDateIso) {
        val raw = snapshot.hourlyStepsByDate[displayedDateIso]
        if (raw != null && raw.size == 24) raw else List(24) { 0L }
    }

    val defaultSelectedHour = remember(hourlyForSelectedDay, displayedDateIso) {
        hourlyForSelectedDay.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0L }?.index ?: -1
    }
    var inspectedHour by remember(displayedDateIso, defaultSelectedHour) { mutableIntStateOf(defaultSelectedHour) }

    val allCorePermissionsGranted = stepPermissions.healthConnectStepsGranted &&
        stepPermissions.activityRecognitionGranted
    var expandSetupCard by remember(allCorePermissionsGranted) { mutableStateOf(!allCorePermissionsGranted) }
    var manualStepsInput by remember { mutableStateOf("") }

    val goal = profile.dailyStepGoal.coerceAtLeast(1000)
    val progress = (displayedSteps.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
    val pctText = when {
        displayedSteps > 0L && (progress * 100).toInt() == 0 -> "<1%"
        else -> "${(progress * 100).toInt()}%"
    }
    val remaining = (goal - displayedSteps).coerceAtLeast(0L)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Samsung Health-Style 7-Day Calendar Ring Strip
        if (weekRecords.isNotEmpty()) {
            item {
                SamsungWeekDateStrip(
                    records = weekRecords,
                    selectedIndex = selectedDayIndex,
                    goal = goal,
                    onSelectDay = { selectedDayIndex = it }
                )
            }
        }

        // 2. Hero Steps Card (Samsung Health Dark Mode Layout)
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = CardSurface,
                border = BorderStroke(1.dp, SamsungGreen.copy(alpha = 0.28f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Header row: Day label + live badge + refresh
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = formatReadableDay(displayedDateIso, isSelectedToday),
                                style = MaterialTheme.typography.titleSmall,
                                color = TextSecondary
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (allCorePermissionsGranted) SamsungGreen else AccentGold)
                                )
                                Text(
                                    text = if (isSelectedToday) {
                                        "OnePlus 13 · Live & Health Connect"
                                    } else {
                                        "Recorded via ${selectedRecord?.source?.name?.replace('_', ' ') ?: "Health Connect"}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                    maxLines = 1
                                )
                            }
                        }
                        IconButton(
                            onClick = onSyncSteps,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(CardSurfaceElevated)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync steps",
                                tint = SamsungGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Giant bold step count + target + comparison pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = String.format(Locale.US, "%,d", displayedSteps),
                                fontSize = 44.sp,
                                lineHeight = 48.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "/ ${String.format(Locale.US, "%,d", goal)}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextMuted,
                                modifier = Modifier.padding(bottom = 7.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (displayedSteps >= goal) SamsungGreen.copy(alpha = 0.18f) else CardSurfaceElevated,
                            border = BorderStroke(1.dp, if (displayedSteps >= goal) SamsungGreen.copy(alpha = 0.5f) else CardBorder)
                        ) {
                            Text(
                                text = when {
                                    displayedSteps >= goal -> "Goal Met"
                                    isSelectedToday && snapshot.stepsVsYesterdayPercent != null -> {
                                        val d = snapshot.stepsVsYesterdayPercent
                                        if (d >= 0) "+$d% vs yest." else "$d% vs yest."
                                    }
                                    else -> pctText
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (displayedSteps >= goal) SamsungGreen else TextSecondary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Sweeping Samsung Health gradient step gauge bar
                    SamsungGradientStepBar(
                        progress = if (displayedSteps > 0L) progress.coerceAtLeast(0.03f) else 0f,
                        goalMet = displayedSteps >= goal
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (remaining == 0L) {
                                "Daily target achieved — great momentum"
                            } else {
                                "${String.format(Locale.US, "%,d", remaining)} steps remaining ($pctText)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (remaining == 0L) SamsungGreen else TextMuted
                        )
                        Icon(
                            imageVector = Icons.Default.Flag,
                            contentDescription = null,
                            tint = if (remaining == 0L) SamsungGreen else AccentGold.copy(alpha = 0.7f),
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = CardBorder)
                    Spacer(Modifier.height(14.dp))

                    // Samsung Health Companion Metrics Triad: Distance | Calories | Active Time
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        SamsungMetricBlock(
                            icon = Icons.Default.Route,
                            iconTint = SamsungCyan,
                            value = String.format(Locale.US, "%.2f km", metrics.distanceKm),
                            label = "Distance",
                            modifier = Modifier.weight(1f)
                        )
                        SamsungMetricBlock(
                            icon = Icons.Default.LocalFireDepartment,
                            iconTint = CoralAccent,
                            value = "${metrics.caloriesKcal} kcal",
                            label = "Calories",
                            modifier = Modifier.weight(1f)
                        )
                        SamsungMetricBlock(
                            icon = Icons.Default.Timer,
                            iconTint = SamsungGreen,
                            value = "${metrics.activeMinutes} min",
                            label = "Active time",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 3. Samsung Health-Style 24-Hour Steps Distribution Chart (Interactive Tap-to-Inspect)
        item {
            ExecutiveCard {
                val hourlySum = hourlyForSelectedDay.sum()
                val activeHourText = if (inspectedHour in 0..23) {
                    val hrSteps = hourlyForSelectedDay[inspectedHour]
                    val startLbl = ExecutiveAnalyticsEngine.formatHourLabel(inspectedHour)
                    val endLbl = ExecutiveAnalyticsEngine.formatHourLabel((inspectedHour + 1) % 24)
                    val hrKm = String.format(Locale.US, "%.2f", StepDerivedMetrics.fromSteps(hrSteps).distanceKm)
                    "$startLbl – $endLbl · ${String.format(Locale.US, "%,d", hrSteps)} steps ($hrKm km)"
                } else if (displayedSteps > hourlySum + 50L) {
                    "Tap any bar · Bars show Health Connect & sensor hourly buckets"
                } else {
                    "Tap any hour bar to inspect hourly step activity"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hourly Activity",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = activeHourText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (inspectedHour in 0..23) SamsungGreen else TextMuted
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                SamsungHourlyBarChart(
                    hourlySteps = hourlyForSelectedDay,
                    selectedHour = inspectedHour,
                    onSelectHour = { inspectedHour = it }
                )
            }
        }

        // 4. Weekly Summary Card (7-Day Chart + 3 Key Stats)
        item {
            val hasPriorHistory = weekRecords.dropLast(1).any { it.steps > 0L }
            ExecutiveCard {
                SectionHeader(
                    title = "This Week",
                    subtitle = if (hasPriorHistory) {
                        "Gold line marks your ${String.format(Locale.US, "%,d", goal)}-step target"
                    } else {
                        "ColorOS 17 tracks continuously via Health Connect from today onward"
                    }
                )
                Spacer(Modifier.height(12.dp))
                StepsWeekChart(records = weekRecords, goal = goal)

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = CardBorder)
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    WeeklySummaryTile(
                        label = "Daily Avg",
                        value = String.format(Locale.US, "%,d", snapshot.avgSteps7d),
                        accent = SamsungCyan,
                        modifier = Modifier.weight(1f)
                    )
                    WeeklySummaryTile(
                        label = "7-Day Total",
                        value = String.format(Locale.US, "%,d", snapshot.weeklyStepsTotal),
                        accent = SamsungGreen,
                        modifier = Modifier.weight(1f)
                    )
                    WeeklySummaryTile(
                        label = "Streak",
                        value = "${snapshot.stepStreakDays}d",
                        accent = AccentGold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 4b. Watch-Ready Health Connect Vitals Card (Sleep, HR, SpO2, Workouts)
        item {
            val vitals = snapshot.healthVitals
            ExecutiveCard(borderColor = if (vitals.hasAnyWatchData) SamsungCyan.copy(alpha = 0.45f) else CardBorder) {
                SectionHeader(
                    title = "Watch Vitals (Health Connect)",
                    subtitle = when {
                        vitals.hasAnyWatchData -> "Synced automatically from your paired smartwatch / tracker"
                        stepPermissions.watchVitalsGranted -> "Ready for OnePlus Watch (OHealth), Fitbit, Amazfit, Xiaomi, or Garmin"
                        else -> "Grant Sleep, Heart Rate, SpO2 & Workout permissions to unlock watch insights"
                    }
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WatchVitalTile(
                        label = "Sleep",
                        value = if (vitals.lastNightSleepMinutes > 0) {
                            ExecutiveAnalyticsEngine.formatDurationHoursMins(vitals.lastNightSleepMinutes)
                        } else "—",
                        sub = if (vitals.avgSleepMinutes7d > 0) {
                            "7d avg ${ExecutiveAnalyticsEngine.formatDurationHoursMins(vitals.avgSleepMinutes7d)}"
                        } else "Last night",
                        accent = SamsungCyan,
                        modifier = Modifier.weight(1f)
                    )
                    WatchVitalTile(
                        label = "Heart Rate",
                        value = vitals.restingHeartRateBpm?.let { "$it bpm" }
                            ?: vitals.latestHeartRateBpm?.let { "$it bpm" }
                            ?: "—",
                        sub = when {
                            vitals.restingHeartRateBpm != null && vitals.latestHeartRateBpm != null ->
                                "Resting · Latest ${vitals.latestHeartRateBpm}"
                            vitals.restingHeartRateBpm != null -> "Resting HR"
                            vitals.latestHeartRateBpm != null -> "Latest sample"
                            else -> "Resting / Live"
                        },
                        accent = CoralAccent,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WatchVitalTile(
                        label = "SpO2",
                        value = vitals.latestSpo2Percent?.let {
                            String.format(Locale.US, "%.1f%%", it)
                        } ?: "—",
                        sub = "Blood oxygen",
                        accent = SamsungGreen,
                        modifier = Modifier.weight(1f)
                    )
                    WatchVitalTile(
                        label = "Workouts (7d)",
                        value = if (vitals.workoutsThisWeek > 0) "${vitals.workoutsThisWeek} · ${vitals.workoutMinutesThisWeek}m" else "—",
                        sub = vitals.lastWorkoutTitle.ifBlank { "Exercise sessions" },
                        accent = AccentGold,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (!stepPermissions.watchVitalsGranted) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onRequestHealthConnectPermissions,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Watch Vitals Permissions", maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        // 5. Collapsible ColorOS 17 Permissions & Manual Step Adjustment Card
        item {
            ExecutiveCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandSetupCard = !expandSetupCard },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "OnePlus 13 Sensors & Manual Entry",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = if (allCorePermissionsGranted) {
                                "Health Connect & OP 13 pedometer active · Tap for settings"
                            } else {
                                stepPermissions.statusDetail
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (allCorePermissionsGranted) SamsungGreen else AccentGold
                        )
                    }
                    Icon(
                        imageVector = if (expandSetupCard) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle details",
                        tint = TextMuted
                    )
                }

                AnimatedVisibility(visible = expandSetupCard) {
                    Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HorizontalDivider(color = CardBorder)
                        CompactPermRow("Health Connect Read Steps", stepPermissions.healthConnectStepsGranted)
                        CompactPermRow("Health Connect Watch Vitals", stepPermissions.watchVitalsGranted)
                        CompactPermRow("Health Connect Background Read", stepPermissions.healthConnectBackgroundGranted)
                        CompactPermRow("OP 13 Physical Activity Sensor", stepPermissions.activityRecognitionGranted)
                        CompactPermRow("ColorOS 17 Battery Unrestricted", stepPermissions.batteryOptimizationIgnored)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onRequestHealthConnectPermissions,
                                colors = ButtonDefaults.buttonColors(containerColor = SamsungGreen, contentColor = ObsidianBg),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Health Connect", maxLines = 1, softWrap = false)
                            }
                            OutlinedButton(
                                onClick = onRequestActivityRecognition,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("OP 13 Sensor", maxLines = 1, softWrap = false)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onOpenColorOsBatterySettings,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Battery Settings", maxLines = 1, softWrap = false)
                            }
                            OutlinedButton(
                                onClick = onOpenHealthConnectSettings,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("HC Data Sources", maxLines = 1, softWrap = false)
                            }
                        }

                        HorizontalDivider(color = CardBorder)
                        Text("Manual Step Override (Today)", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = manualStepsInput,
                                onValueChange = { manualStepsInput = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Enter today's steps") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val v = manualStepsInput.toLongOrNull()
                                    if (v != null && v >= 0L) {
                                        onLogManualSteps(v)
                                        manualStepsInput = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Save") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SamsungWeekDateStrip(
    records: List<DailyStepRecord>,
    selectedIndex: Int,
    goal: Int,
    onSelectDay: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CardSurface,
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            records.forEachIndexed { idx, rec ->
                val isSelected = idx == selectedIndex
                val date = runCatching { LocalDate.parse(rec.dateIso) }.getOrNull()
                val dayLetter = date?.dayOfWeek?.getDisplayName(TextStyle.NARROW, Locale.US) ?: "-"
                val dayNum = date?.dayOfMonth?.toString() ?: rec.dateIso.takeLast(2)
                val prog = (rec.steps.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
                val goalMet = rec.steps >= goal

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) CardSurfaceElevated else Color.Transparent)
                        .clickable { onSelectDay(idx) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = dayLetter,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) SamsungGreen else TextMuted,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                    Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.size(34.dp)) {
                            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                            drawArc(
                                color = CardBorder,
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = stroke
                            )
                            if (rec.steps > 0L) {
                                drawArc(
                                    color = if (goalMet) SamsungGreen else SamsungCyan,
                                    startAngle = -90f,
                                    sweepAngle = (360f * prog).coerceAtLeast(18f),
                                    useCenter = false,
                                    style = stroke
                                )
                            }
                        }
                        Text(
                            text = dayNum,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) TextPrimary else TextSecondary,
                            fontWeight = if (isSelected || goalMet) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SamsungGradientStepBar(
    progress: Float,
    goalMet: Boolean
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(50))
            .background(CardSurfaceElevated)
    ) {
        if (clamped > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .height(14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            colors = if (goalMet) {
                                listOf(SamsungMint, SamsungGreen)
                            } else {
                                listOf(SamsungCyan, SamsungGreen)
                            }
                        )
                    )
            )
        }
    }
}

@Composable
private fun SamsungHourlyBarChart(
    hourlySteps: List<Long>,
    selectedHour: Int,
    onSelectHour: (Int) -> Unit
) {
    val safeHours = if (hourlySteps.size == 24) hourlySteps else List(24) { 0L }
    val maxHourSteps = maxOf(safeHours.maxOrNull() ?: 0L, 50L).toFloat()

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .pointerInput(safeHours) {
                    detectTapGestures { offset ->
                        val slotW = size.width / 24f
                        val idx = (offset.x / slotW).toInt().coerceIn(0, 23)
                        onSelectHour(idx)
                    }
                }
        ) {
            val h = size.height - 6f
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

            // Subtle Samsung Health style horizontal guide lines (top, mid, baseline)
            listOf(0.15f, 0.55f, 1.0f).forEach { frac ->
                val y = h * frac
                drawLine(
                    color = CardBorder.copy(alpha = 0.65f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.2f,
                    pathEffect = if (frac < 1f) dashEffect else null
                )
            }

            val slotW = size.width / 24f
            val barW = (slotW * 0.54f).coerceIn(5f, 18f)

            safeHours.forEachIndexed { hr, steps ->
                val x = hr * slotW + (slotW - barW) / 2f
                val isSelected = hr == selectedHour
                val barH = if (steps > 0L) {
                    ((steps / maxHourSteps) * (h * 0.88f)).coerceAtLeast(10f)
                } else {
                    3f
                }
                val color = when {
                    isSelected && steps > 0L -> AccentGold
                    isSelected -> SamsungCyan.copy(alpha = 0.6f)
                    steps > 0L -> SamsungGreen
                    else -> CardBorder.copy(alpha = 0.7f)
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, h - barH),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("12 AM", "6 AM", "12 PM", "6 PM", "11 PM").forEach { lbl ->
                Text(text = lbl, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
    }
}

@Composable
private fun SamsungMetricBlock(
    icon: ImageVector,
    iconTint: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WeeklySummaryTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun WatchVitalTile(
    label: String,
    value: String,
    sub: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = CardSurfaceElevated,
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Text(value, style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.Bold)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun CompactPermRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(
            text = if (granted) "Granted" else "Pending",
            style = MaterialTheme.typography.labelSmall,
            color = if (granted) EmeraldAccent else CoralAccent,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatReadableDay(dateIso: String, isToday: Boolean): String {
    val parsed = runCatching { LocalDate.parse(dateIso) }.getOrNull() ?: return dateIso
    val nice = parsed.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
    return if (isToday) "Today · $nice" else nice
}
