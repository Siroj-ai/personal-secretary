package com.personalsecretary.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.personalsecretary.app.BuildConfig
import com.personalsecretary.app.data.ExecutiveSnapshot
import com.personalsecretary.app.data.SecretaryProfile
import com.personalsecretary.app.data.SecretaryVideoSlot
import com.personalsecretary.app.health.StepPermissionStatus
import com.personalsecretary.app.ui.components.ExecutiveCard
import com.personalsecretary.app.ui.components.SectionHeader
import com.personalsecretary.app.ui.theme.AccentGold
import com.personalsecretary.app.ui.theme.CardBorder
import com.personalsecretary.app.ui.theme.CardSurface
import com.personalsecretary.app.ui.theme.CardSurfaceElevated
import com.personalsecretary.app.ui.theme.CoralAccent
import com.personalsecretary.app.ui.theme.EmeraldAccent
import com.personalsecretary.app.ui.theme.ObsidianBg
import com.personalsecretary.app.ui.theme.TextMuted
import com.personalsecretary.app.ui.theme.TextPrimary
import com.personalsecretary.app.ui.theme.TextSecondary
import com.personalsecretary.app.update.OtaUpdateState
import com.personalsecretary.app.voice.ClaraVoiceEngine
import com.personalsecretary.app.voice.VoiceEngineState
import java.io.File
import java.util.Locale

enum class HubSubTab(val label: String) {
    STEPS("Health & Watch"),
    SETUP("Secretary Setup")
}

@Composable
fun HealthAndSetupScreen(
    selectedSubTab: HubSubTab,
    onSelectSubTab: (HubSubTab) -> Unit,
    snapshot: ExecutiveSnapshot,
    profile: SecretaryProfile,
    stepPermissions: StepPermissionStatus,
    videoSlots: Map<String, String>,
    detectedModelFiles: List<File>,
    engineStatusLabel: String,
    isModelLoading: Boolean,
    isGemmaLoaded: Boolean,
    modelImportProgress: String?,
    voiceState: VoiceEngineState,
    otaState: OtaUpdateState,
    statusToast: String?,
    onRequestHealthConnectPermissions: () -> Unit,
    onRequestActivityRecognition: () -> Unit,
    onOpenColorOsBatterySettings: () -> Unit,
    onOpenHealthConnectSettings: () -> Unit,
    onSyncSteps: () -> Unit,
    onLogManualSteps: (Long) -> Unit,
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
    onDownloadKokoroVoice: () -> Unit,
    onScanKokoroVoice: () -> Unit,
    onTestClaraVoice: () -> Unit,
    onStopSpeaking: () -> Unit,
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
            HubSubTab.STEPS -> SamsungStyleStepsTabContent(
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

            HubSubTab.SETUP -> SecretarySetupTabContent(
                profile = profile,
                videoSlots = videoSlots,
                detectedModelFiles = detectedModelFiles,
                engineStatusLabel = engineStatusLabel,
                isModelLoading = isModelLoading,
                isGemmaLoaded = isGemmaLoaded,
                modelImportProgress = modelImportProgress,
                voiceState = voiceState,
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
                onDownloadKokoroVoice = onDownloadKokoroVoice,
                onScanKokoroVoice = onScanKokoroVoice,
                onTestClaraVoice = onTestClaraVoice,
                onStopSpeaking = onStopSpeaking,
                onCheckForUpdate = onCheckForUpdate,
                onDownloadAndInstallUpdate = onDownloadAndInstallUpdate
            )
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
    voiceState: VoiceEngineState,
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
    onDownloadKokoroVoice: () -> Unit,
    onScanKokoroVoice: () -> Unit,
    onTestClaraVoice: () -> Unit,
    onStopSpeaking: () -> Unit,
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
                            Text("${file.name} ($sizeMb MB)", maxLines = 1)
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
                        Text("Fastest inference; toggle off for CPU if you prefer stricter free-form numeric accuracy", style = MaterialTheme.typography.bodySmall, color = TextMuted)
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

        // 2. Clara Voice Studio (Offline Kokoro-82M Neural + Google HD Fallback)
        item {
            ExecutiveCard(
                borderColor = if (voiceState.isKokoroInstalled) EmeraldAccent.copy(alpha = 0.55f) else AccentGold.copy(alpha = 0.45f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = AccentGold)
                    SectionHeader(
                        title = "${profile.secretaryName} Voice Studio (Kokoro-82M)",
                        subtitle = voiceState.activeVoiceLabel
                    )
                }
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Speak Advisor replies automatically", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text("Natural dates, rupees & live speaking video sync", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    Switch(
                        checked = profile.voiceBriefingEnabled,
                        onCheckedChange = { onSaveProfile(profile.copy(voiceBriefingEnabled = it)) }
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text("Voice Engine", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        ClaraVoiceEngine.MODE_AUTO to "Auto",
                        ClaraVoiceEngine.MODE_KOKORO to "Kokoro-82M",
                        ClaraVoiceEngine.MODE_GOOGLE_HD to "Google HD"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = profile.voiceEngineMode == mode,
                            onClick = { onSaveProfile(profile.copy(voiceEngineMode = mode)) },
                            label = { Text(label, maxLines = 1, softWrap = false) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentGold.copy(alpha = 0.22f),
                                selectedLabelColor = AccentGold
                            )
                        )
                    }
                }

                if (!voiceState.isKokoroInstalled) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Install the offline Kokoro-82M neural voice pack (~126 MB, 100% private on-device) for natural studio-quality speech instead of robotic TTS.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    if (voiceState.isDownloadingModel) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { voiceState.downloadProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(7.dp),
                            color = AccentGold,
                            trackColor = CardBorder
                        )
                        if (!voiceState.downloadStatus.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(voiceState.downloadStatus, style = MaterialTheme.typography.labelSmall, color = AccentGold)
                        }
                    }
                    if (!voiceState.lastError.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(voiceState.lastError, style = MaterialTheme.typography.labelSmall, color = CoralAccent)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onDownloadKokoroVoice,
                            enabled = !voiceState.isDownloadingModel,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (voiceState.isDownloadingModel) "Installing..." else "Download Kokoro-82M", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onScanKokoroVoice,
                            enabled = !voiceState.isDownloadingModel,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Scan")
                        }
                    }
                } else {
                    Spacer(Modifier.height(10.dp))
                    val selectedPreset = ClaraVoiceEngine.presetForSpeakerId(profile.kokoroVoiceId)
                    Text(
                        text = "Kokoro-82M Voice: ${selectedPreset.label} — ${selectedPreset.description}",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldAccent
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ClaraVoiceEngine.KOKORO_VOICES.forEach { preset ->
                            FilterChip(
                                selected = profile.kokoroVoiceId == preset.speakerId,
                                onClick = {
                                    onSaveProfile(profile.copy(kokoroVoiceId = preset.speakerId))
                                },
                                label = { Text(preset.label, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EmeraldAccent.copy(alpha = 0.22f),
                                    selectedLabelColor = EmeraldAccent
                                )
                            )
                        }
                    }
                }

                if ((profile.voiceEngineMode == ClaraVoiceEngine.MODE_GOOGLE_HD || !voiceState.isKokoroInstalled) &&
                    voiceState.availableGoogleVoices.isNotEmpty()
                ) {
                    Spacer(Modifier.height(10.dp))
                    Text("Google HD Neural Voices", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        voiceState.availableGoogleVoices.forEach { gv ->
                            FilterChip(
                                selected = profile.googleVoiceName == gv.name ||
                                    (profile.googleVoiceName.isBlank() && gv == voiceState.availableGoogleVoices.first()),
                                onClick = { onSaveProfile(profile.copy(googleVoiceName = gv.name)) },
                                label = { Text(gv.label, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentGold.copy(alpha = 0.22f),
                                    selectedLabelColor = AccentGold
                                )
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Speaking Pace", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Text(
                        text = "${String.format(Locale.US, "%.2f", profile.voiceSpeed)}x",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentGold
                    )
                }
                Slider(
                    value = profile.voiceSpeed.coerceIn(0.80f, 1.25f),
                    onValueChange = { next ->
                        val snapped = (kotlin.math.round(next * 20f) / 20f).coerceIn(0.80f, 1.25f)
                        onSaveProfile(profile.copy(voiceSpeed = snapped))
                    },
                    valueRange = 0.80f..1.25f,
                    colors = SliderDefaults.colors(
                        thumbColor = AccentGold,
                        activeTrackColor = AccentGold,
                        inactiveTrackColor = CardBorder
                    )
                )

                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (voiceState.isSpeaking) onStopSpeaking() else onTestClaraVoice() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (voiceState.isSpeaking) CoralAccent else AccentGold,
                            contentColor = ObsidianBg
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (voiceState.isSpeaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (voiceState.isSpeaking) "Stop Voice" else "Test ${profile.secretaryName}'s Voice"
                        )
                    }
                }
            }
        }

        // 3. Google Flow Realistic Secretary Videos
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
                    subtitle = "Compatible with your DayBookTracker Apps Script URL or direct CSV/TSV/JSON import"
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
                        Text("Import File")
                    }
                }
            }
        }

        // 4. Client Dossier & Advisory Goals
        item {
            ExecutiveCard {
                SectionHeader(
                    title = "Client Dossier & Consultant Targets",
                    subtitle = "Feed your financial and activity goals directly to your secretary"
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
                    label = { Text("Physical Activity Priorities") },
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
