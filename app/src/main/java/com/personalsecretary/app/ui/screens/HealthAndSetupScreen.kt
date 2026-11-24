package com.personalsecretary.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.personalsecretary.app.BuildConfig
import com.personalsecretary.app.update.OtaUpdateState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personalsecretary.app.data.ExecutiveSnapshot
import com.personalsecretary.app.data.MentalCheckIn
import com.personalsecretary.app.data.SecretaryProfile
import com.personalsecretary.app.data.SecretaryVideoSlot
import com.personalsecretary.app.health.StepPermissionStatus
import com.personalsecretary.app.ui.components.ExecutiveCard
import com.personalsecretary.app.ui.components.ProgressRing
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
import com.personalsecretary.app.ui.theme.VioletAccent
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

enum class HubSubTab(val label: String) {
    PHYSICAL("Physical"),
    MENTAL("Mental"),
    SETUP("Secretary Setup")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HealthAndSetupScreen(
    selectedSubTab: HubSubTab,
    onSelectSubTab: (HubSubTab) -> Unit,
    snapshot: ExecutiveSnapshot,
    profile: SecretaryProfile,
    stepPermissions: StepPermissionStatus,
    checkIns: List<MentalCheckIn>,
    videoSlots: Map<String, String>,
    detectedModelFiles: List<File>,
    engineStatusLabel: String,
    isModelLoading: Boolean,
    isGemmaLoaded: Boolean,
    modelImportProgress: String?,
    otaState: OtaUpdateState,
    statusToast: String?,
    onRequestHealthConnectPermissions: () -> Unit,
    onRequestActivityRecognition: () -> Unit,
    onOpenColorOsBatterySettings: () -> Unit,
    onOpenHealthConnectSettings: () -> Unit,
    onSyncSteps: () -> Unit,
    onLogManualSteps: (Long) -> Unit,
    onSaveCheckIn: (mood: Int, energy: Int, stress: Int, sleepHours: Float, tags: List<String>, note: String) -> Unit,
    onDeleteCheckIn: (String) -> Unit,
    onSaveProfile: (SecretaryProfile) -> Unit,
    onSyncDayBook: () -> Unit,
    onImportCsvPicker: () -> Unit,
    onPickLiteRtModel: () -> Unit,
    onScanModels: () -> Unit,
    onSelectModelPath: (String) -> Unit,
    onLoadGemma: () -> Unit,
    onUnloadGemma: () -> Unit,
    onPickFlowVideo: (SecretaryVideoSlot) -> Unit,
    onRemoveFlowVideo: (SecretaryVideoSlot) -> Unit,
    onCheckForUpdate: () -> Unit,
    onDownloadAndInstallUpdate: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = selectedSubTab.ordinal,
            containerColor = CardSurface,
            contentColor = AccentGold
        ) {
            HubSubTab.entries.forEach { tab ->
                Tab(
                    selected = selectedSubTab == tab,
                    onClick = { onSelectSubTab(tab) },
                    text = { Text(tab.label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        if (!statusToast.isNullOrBlank()) {
            Surface(
                color = CardSurfaceElevated,
                border = BorderStroke(1.dp, AccentGold.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = statusToast,
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentGold,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        when (selectedSubTab) {
            HubSubTab.PHYSICAL -> PhysicalTabContent(
                snapshot = snapshot,
                profile = profile,
                stepPermissions = stepPermissions,
                onRequestHealthConnectPermissions = onRequestHealthConnectPermissions,
                onRequestActivityRecognition = onRequestActivityRecognition,
                onOpenColorOsBatterySettings = onOpenColorOsBatterySettings,
                onOpenHealthConnectSettings = onOpenHealthConnectSettings,
                onSyncSteps = onSyncSteps,
                onLogManualSteps = onLogManualSteps
            )

            HubSubTab.MENTAL -> MentalTabContent(
                snapshot = snapshot,
                checkIns = checkIns,
                onSaveCheckIn = onSaveCheckIn,
                onDeleteCheckIn = onDeleteCheckIn
            )

            HubSubTab.SETUP -> SecretarySetupTabContent(
                profile = profile,
                videoSlots = videoSlots,
                detectedModelFiles = detectedModelFiles,
                engineStatusLabel = engineStatusLabel,
                isModelLoading = isModelLoading,
                isGemmaLoaded = isGemmaLoaded,
                modelImportProgress = modelImportProgress,
                otaState = otaState,
                onSaveProfile = onSaveProfile,
                onSyncDayBook = onSyncDayBook,
                onImportCsvPicker = onImportCsvPicker,
                onPickLiteRtModel = onPickLiteRtModel,
                onScanModels = onScanModels,
                onSelectModelPath = onSelectModelPath,
                onLoadGemma = onLoadGemma,
                onUnloadGemma = onUnloadGemma,
                onPickFlowVideo = onPickFlowVideo,
                onRemoveFlowVideo = onRemoveFlowVideo,
                onCheckForUpdate = onCheckForUpdate,
                onDownloadAndInstallUpdate = onDownloadAndInstallUpdate
            )
        }
    }
}

@Composable
private fun PhysicalTabContent(
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
    var manualStepsInput by remember { mutableStateOf("") }
    val pct = if (profile.dailyStepGoal > 0) {
        (snapshot.todaySteps.toFloat() / profile.dailyStepGoal.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val pctLabel = when {
        snapshot.todaySteps > 0L && (pct * 100).toInt() == 0 -> "<1%"
        else -> "${(pct * 100).toInt()}%"
    }
    val hasPriorHistory = snapshot.recentSteps7d.dropLast(1).any { it.steps > 0L }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ExecutiveCard(borderColor = SkyAccent.copy(alpha = 0.45f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("OnePlus 13 Steps Today", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                        Text(
                            text = String.format(Locale.US, "%,d", snapshot.todaySteps),
                            style = MaterialTheme.typography.headlineMedium,
                            color = SkyAccent,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Goal: ${String.format(Locale.US, "%,d", profile.dailyStepGoal)} · 7d Avg: ${String.format(Locale.US, "%,d", snapshot.avgSteps7d)} · Streak: ${snapshot.stepStreakDays}d",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Text(
                            text = "Source: ${snapshot.todayStepSource.name.replace('_', ' ')}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                    ProgressRing(
                        progress = if (snapshot.todaySteps > 0L) pct.coerceAtLeast(0.02f) else 0f,
                        ringColor = if (pct >= 1f) EmeraldAccent else SkyAccent,
                        size = 66.dp,
                        strokeWidth = 6.dp,
                        centerText = pctLabel
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onSyncSteps,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh Steps from Health Connect")
                }
            }
        }

        item {
            ExecutiveCard {
                SectionHeader(
                    title = "7-Day Movement Trend",
                    subtitle = if (hasPriorHistory) {
                        "Gold line marks your ${profile.dailyStepGoal}-step daily target"
                    } else {
                        "Health Connect tracks continuously from the day access was granted"
                    }
                )
                Spacer(Modifier.height(12.dp))
                StepsWeekChart(records = snapshot.recentSteps7d, goal = profile.dailyStepGoal)
                if (!hasPriorHistory) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Tip: On ColorOS 17, past days stay 0 until a full day elapses or OHealth / Google Fit is linked in HC Data Sources.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }
        }

        item {
            ExecutiveCard {
                SectionHeader(
                    title = "OnePlus 13 & ColorOS 17 Step Permissions",
                    subtitle = stepPermissions.statusDetail
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PermissionStatusLine("Health Connect Read Steps", stepPermissions.healthConnectStepsGranted)
                    PermissionStatusLine("Health Connect Background Read", stepPermissions.healthConnectBackgroundGranted)
                    PermissionStatusLine("OP 13 Physical Activity Sensor", stepPermissions.activityRecognitionGranted)
                    PermissionStatusLine("ColorOS 17 Battery Unrestricted", stepPermissions.batteryOptimizationIgnored)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRequestHealthConnectPermissions,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Authorize Health Connect", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onRequestActivityRecognition,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("OP 13 Sensor", maxLines = 1)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onOpenColorOsBatterySettings,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("ColorOS 17 Battery", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onOpenHealthConnectSettings,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("HC Data Sources", maxLines = 1)
                    }
                }
            }
        }

        item {
            ExecutiveCard {
                SectionHeader(
                    title = "Manual Step Entry",
                    subtitle = "Optional override if you tracked a walk on another device"
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualStepsInput,
                        onValueChange = { manualStepsInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Today's steps") },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MentalTabContent(
    snapshot: ExecutiveSnapshot,
    checkIns: List<MentalCheckIn>,
    onSaveCheckIn: (mood: Int, energy: Int, stress: Int, sleepHours: Float, tags: List<String>, note: String) -> Unit,
    onDeleteCheckIn: (String) -> Unit
) {
    var mood by remember { mutableIntStateOf(3) }
    var energy by remember { mutableIntStateOf(3) }
    var stress by remember { mutableIntStateOf(3) }
    var sleepHours by remember { mutableFloatStateOf(7.0f) }
    var selectedTags by remember { mutableStateOf(setOf<String>()) }
    var note by remember { mutableStateOf("") }

    val availableTags = listOf("Work Load", "Finances", "Good Sleep", "Poor Sleep", "Walked", "Social", "Cravings", "Focused")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ExecutiveCard(borderColor = VioletAccent.copy(alpha = 0.45f)) {
                SectionHeader(
                    title = "15-Second Executive Check-In",
                    subtitle = "Private on-device log used by your secretary to spot stress & spending triggers"
                )
                Spacer(Modifier.height(12.dp))

                RatingSliderRow("Mood", mood, 1..5, EmeraldAccent) { mood = it }
                RatingSliderRow("Energy", energy, 1..5, SkyAccent) { energy = it }
                RatingSliderRow("Stress", stress, 1..5, CoralAccent) { stress = it }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sleep Last Night", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("${String.format(Locale.US, "%.1f", sleepHours)} hrs", style = MaterialTheme.typography.titleSmall, color = AccentGold)
                }
                Slider(
                    value = sleepHours,
                    onValueChange = { sleepHours = (it * 2f).roundToInt() / 2f },
                    valueRange = 3f..11f,
                    steps = 15
                )

                Text("Context Tags", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableTags.forEach { tag ->
                        val active = tag in selectedTags
                        FilterChip(
                            selected = active,
                            onClick = {
                                selectedTags = if (active) selectedTags - tag else selectedTags + tag
                            },
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Brief reflection or what's on your mind") },
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        onSaveCheckIn(mood, energy, stress, sleepHours, selectedTags.toList(), note.trim())
                        note = ""
                        selectedTags = emptySet()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Record Private Check-In")
                }
            }
        }

        if (snapshot.checkInCount14d > 0) {
            item {
                ExecutiveCard {
                    SectionHeader(title = "7-Day Mental Baseline", subtitle = "${snapshot.checkInCount14d} check-ins in last 14 days")
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MiniMetric("Mood", "${String.format(Locale.US, "%.1f", snapshot.avgMood7d)}/5", EmeraldAccent)
                        MiniMetric("Energy", "${String.format(Locale.US, "%.1f", snapshot.avgEnergy7d)}/5", SkyAccent)
                        MiniMetric("Stress", "${String.format(Locale.US, "%.1f", snapshot.avgStress7d)}/5", CoralAccent)
                        MiniMetric("Sleep", "${String.format(Locale.US, "%.1f", snapshot.avgSleep7d)}h", AccentGold)
                    }
                }
            }
        }

        item {
            SectionHeader(title = "Recent Check-Ins", subtitle = "Stored strictly on-device, excluded from backup")
        }

        if (checkIns.isEmpty()) {
            item {
                ExecutiveCard {
                    Text("No check-ins recorded yet.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        } else {
            items(checkIns.take(20), key = { it.id }) { item ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CardSurfaceElevated,
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "${item.dateIso} · Mood ${item.mood}/5 · Energy ${item.energy}/5 · Stress ${item.stress}/5 · ${item.sleepHours}h",
                                style = MaterialTheme.typography.labelMedium,
                                color = AccentGold
                            )
                            if (item.tags.isNotEmpty()) {
                                Text(item.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = SkyAccent)
                            }
                            if (item.note.isNotBlank()) {
                                Text(item.note, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                            }
                        }
                        IconButton(onClick = { onDeleteCheckIn(item.id) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete check-in", tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecretarySetupTabContent(
    profile: SecretaryProfile,
    videoSlots: Map<String, String>,
    detectedModelFiles: List<File>,
    engineStatusLabel: String,
    isModelLoading: Boolean,
    isGemmaLoaded: Boolean,
    modelImportProgress: String?,
    otaState: OtaUpdateState,
    onSaveProfile: (SecretaryProfile) -> Unit,
    onSyncDayBook: () -> Unit,
    onImportCsvPicker: () -> Unit,
    onPickLiteRtModel: () -> Unit,
    onScanModels: () -> Unit,
    onSelectModelPath: (String) -> Unit,
    onLoadGemma: () -> Unit,
    onUnloadGemma: () -> Unit,
    onPickFlowVideo: (SecretaryVideoSlot) -> Unit,
    onRemoveFlowVideo: (SecretaryVideoSlot) -> Unit,
    onCheckForUpdate: () -> Unit,
    onDownloadAndInstallUpdate: () -> Unit
) {
    var userName by remember(profile.userName) { mutableStateOf(profile.userName) }
    var secretaryName by remember(profile.secretaryName) { mutableStateOf(profile.secretaryName) }
    var appsScriptUrl by remember(profile.appsScriptUrl) { mutableStateOf(profile.appsScriptUrl) }
    var spreadsheetId by remember(profile.spreadsheetId) { mutableStateOf(profile.spreadsheetId) }
    var monthlyBudget by remember(profile.monthlySpendBudget) { mutableStateOf(profile.monthlySpendBudget.toInt().toString()) }
    var dailyStepGoal by remember(profile.dailyStepGoal) { mutableStateOf(profile.dailyStepGoal.toString()) }
    var financialGoals by remember(profile.financialGoals) { mutableStateOf(profile.financialGoals) }
    var healthGoals by remember(profile.healthGoals) { mutableStateOf(profile.healthGoals) }
    var customNotes by remember(profile.customDossierNotes) { mutableStateOf(profile.customDossierNotes) }
    var githubRepo by remember(profile.githubRepo) { mutableStateOf(profile.githubRepo) }
    var githubToken by remember(profile.githubToken) { mutableStateOf(profile.githubToken) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. On-Device Intelligence: Gemma 4 E4B (LiteRT-LM)
        item {
            ExecutiveCard(borderColor = AccentGold.copy(alpha = 0.55f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Memory, contentDescription = null, tint = AccentGold)
                    SectionHeader(
                        title = "On-Device Intelligence (Gemma 4 E4B)",
                        subtitle = engineStatusLabel
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (profile.modelPath.isNotBlank()) "Model: ${profile.modelPath}" else "No .litertlm model selected yet. Pick gemma-4-E4B-it.litertlm from your OnePlus 13.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (!modelImportProgress.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(modelImportProgress, style = MaterialTheme.typography.labelMedium, color = AccentGold)
                }

                if (detectedModelFiles.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Detected .litertlm files:", style = MaterialTheme.typography.labelSmall, color = EmeraldAccent)
                    Spacer(Modifier.height(4.dp))
                    detectedModelFiles.forEach { file ->
                        val sizeMb = file.length() / (1024L * 1024L)
                        OutlinedButton(
                            onClick = { onSelectModelPath(file.absolutePath) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Text("${file.name} (${sizeMb} MB)", maxLines = 1)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Use GPU Acceleration (Adreno 830)", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text("Falls back to CPU automatically if GPU init fails", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    Switch(
                        checked = profile.preferGpuBackend,
                        onCheckedChange = { onSaveProfile(profile.copy(preferGpuBackend = it)) }
                    )
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-load Gemma 4 on startup", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text("Keeps your advisor warm across app switches", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    Switch(
                        checked = profile.autoLoadModelOnStart,
                        onCheckedChange = { onSaveProfile(profile.copy(autoLoadModelOnStart = it)) }
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onPickLiteRtModel,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pick Model", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onScanModels,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Scan Storage", maxLines = 1)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onLoadGemma,
                        enabled = !isModelLoading && profile.modelPath.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isModelLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = ObsidianBg)
                            Spacer(Modifier.width(6.dp))
                            Text("Loading...")
                        } else {
                            Text(if (isGemmaLoaded) "Reload Engine" else "Load Gemma 4 E4B")
                        }
                    }
                    if (isGemmaLoaded) {
                        OutlinedButton(
                            onClick = onUnloadGemma,
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("Unload") }
                    }
                }
            }
        }

        // 2. Google Flow Realistic Secretary Videos
        item {
            ExecutiveCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Videocam, contentDescription = null, tint = AccentGold)
                    SectionHeader(
                        title = "Google Flow Secretary Videos",
                        subtitle = "Attach realistic executive MP4 clips for ambient presence"
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Show video presence header", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Switch(
                        checked = profile.showVideoPresence,
                        onCheckedChange = { onSaveProfile(profile.copy(showVideoPresence = it)) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecretaryVideoSlot.entries.forEach { slot ->
                        val assigned = videoSlots[slot.slotKey]
                        val exists = assigned != null && File(assigned).exists()
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CardSurfaceElevated,
                            border = BorderStroke(1.dp, if (exists) EmeraldAccent.copy(alpha = 0.45f) else CardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(slot.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                                    Text(
                                        text = if (assigned != null && exists) "Assigned (${File(assigned).length() / 1024} KB)" else slot.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (exists) EmeraldAccent else TextMuted
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(
                                        onClick = { onPickFlowVideo(slot) },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(if (exists) "Replace" else "Import MP4")
                                    }
                                    if (exists) {
                                        IconButton(onClick = { onRemoveFlowVideo(slot) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = TextMuted, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Day Book Google Sheets Connection
        item {
            ExecutiveCard {
                SectionHeader(
                    title = "Day Book Google Sheets Sync",
                    subtitle = "Compatible with your DayBookTracker Apps Script URL or direct CSV/TSV import"
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = appsScriptUrl,
                    onValueChange = { appsScriptUrl = it },
                    label = { Text("Apps Script Web App URL (https://script.google.com/macros/s/.../exec)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = spreadsheetId,
                    onValueChange = { spreadsheetId = it },
                    label = { Text("Day Book Spreadsheet ID / URL (optional CSV fallback)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSaveProfile(
                                profile.copy(
                                    appsScriptUrl = appsScriptUrl.trim(),
                                    spreadsheetId = spreadsheetId.trim()
                                )
                            )
                            onSyncDayBook()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save & Sync")
                    }
                    OutlinedButton(
                        onClick = onImportCsvPicker,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import CSV/TSV")
                    }
                }
            }
        }

        // 4. Client Dossier & Advisory Goals
        item {
            ExecutiveCard {
                SectionHeader(
                    title = "Client Dossier & Consultant Targets",
                    subtitle = "Feed your goals and personal context directly to your secretary"
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = userName,
                        onValueChange = { userName = it },
                        label = { Text("Your Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = secretaryName,
                        onValueChange = { secretaryName = it },
                        label = { Text("Secretary Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = monthlyBudget,
                        onValueChange = { monthlyBudget = it.filter { c -> c.isDigit() } },
                        label = { Text("Monthly Budget (Rs.)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = dailyStepGoal,
                        onValueChange = { dailyStepGoal = it.filter { c -> c.isDigit() } },
                        label = { Text("Daily Step Goal") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = financialGoals,
                    onValueChange = { financialGoals = it },
                    label = { Text("Financial Priorities") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = healthGoals,
                    onValueChange = { healthGoals = it },
                    label = { Text("Physical & Mental Priorities") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customNotes,
                    onValueChange = { customNotes = it },
                    label = { Text("Additional Personal Context (income sources, habits, schedule)") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        onSaveProfile(
                            profile.copy(
                                userName = userName.trim().ifBlank { "Owner" },
                                secretaryName = secretaryName.trim().ifBlank { "Clara" },
                                appsScriptUrl = appsScriptUrl.trim(),
                                spreadsheetId = spreadsheetId.trim(),
                                monthlySpendBudget = monthlyBudget.toDoubleOrNull()?.coerceAtLeast(1000.0) ?: profile.monthlySpendBudget,
                                dailyStepGoal = dailyStepGoal.toIntOrNull()?.coerceIn(1000, 50000) ?: profile.dailyStepGoal,
                                financialGoals = financialGoals.trim(),
                                healthGoals = healthGoals.trim(),
                                customDossierNotes = customNotes.trim()
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Executive Profile")
                }
            }
        }

        // 5. GitHub OTA Self-Updates
        item {
            ExecutiveCard(
                borderColor = if (otaState.availableRelease != null) AccentGold else CardBorder
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = AccentGold)
                    SectionHeader(
                        title = "App Updates (GitHub OTA)",
                        subtitle = "Installed: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = githubRepo,
                    onValueChange = { githubRepo = it },
                    label = { Text("GitHub Repository (owner/repo)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = githubToken,
                    onValueChange = { githubToken = it },
                    label = { Text("Read-only GitHub PAT (leave blank if repo is public)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Auto-check on app launch", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Switch(
                        checked = profile.autoCheckUpdates,
                        onCheckedChange = { onSaveProfile(profile.copy(autoCheckUpdates = it)) }
                    )
                }

                if (!otaState.statusMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = otaState.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (otaState.availableRelease != null) EmeraldAccent else TextSecondary
                    )
                }

                val release = otaState.availableRelease
                if (release != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CardSurfaceElevated,
                        border = BorderStroke(1.dp, AccentGold.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "${release.title} (v${release.versionName})",
                                style = MaterialTheme.typography.titleSmall,
                                color = AccentGold
                            )
                            Text(
                                text = release.releaseNotes.take(400),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            if (release.expectedSha256 != null) {
                                Text(
                                    text = "SHA-256 verified release",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldAccent
                                )
                            }
                        }
                    }
                }

                if (otaState.isDownloading) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { otaState.downloadProgress },
                        modifier = Modifier.fillMaxWidth().height(7.dp),
                        color = AccentGold,
                        trackColor = CardBorder
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Downloading ${(otaState.downloadProgress * 100).toInt()}%...",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentGold
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onSaveProfile(
                                profile.copy(
                                    githubRepo = githubRepo.trim(),
                                    githubToken = githubToken.trim()
                                )
                            )
                            onCheckForUpdate()
                        },
                        enabled = !otaState.isChecking && !otaState.isDownloading,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (otaState.isChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentGold)
                            Spacer(Modifier.width(6.dp))
                            Text("Checking...")
                        } else {
                            Text("Check for Updates")
                        }
                    }
                    if (release != null) {
                        Button(
                            onClick = onDownloadAndInstallUpdate,
                            enabled = !otaState.isDownloading,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (otaState.downloadedApkPath != null) "Install Update" else "Download & Install")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusLine(label: String, granted: Boolean) {
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

@Composable
private fun RatingSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    accent: androidx.compose.ui.graphics.Color,
    onChange: (Int) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text("$value / ${range.last}", style = MaterialTheme.typography.titleSmall, color = accent, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) - 1
        )
    }
}

@Composable
private fun MiniMetric(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}
