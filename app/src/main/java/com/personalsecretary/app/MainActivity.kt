package com.personalsecretary.app

import android.Manifest
import android.content.ComponentCallbacks2
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.health.connect.client.PermissionController
import com.personalsecretary.app.data.SecretaryVideoSlot
import com.personalsecretary.app.mail.EmailNotificationAccessHelper
import com.personalsecretary.app.ui.screens.AdvisorScreen
import com.personalsecretary.app.ui.screens.BriefingScreen
import com.personalsecretary.app.ui.screens.FinancesScreen
import com.personalsecretary.app.ui.screens.HealthAndSetupScreen
import com.personalsecretary.app.ui.screens.HubSubTab
import com.personalsecretary.app.ui.screens.ScheduleScreen
import com.personalsecretary.app.ui.theme.AccentGold
import com.personalsecretary.app.ui.theme.AccentGoldSoft
import com.personalsecretary.app.ui.theme.CardSurface
import com.personalsecretary.app.ui.theme.ObsidianBg
import com.personalsecretary.app.ui.theme.PersonalSecretaryTheme
import com.personalsecretary.app.ui.theme.TextMuted
import com.personalsecretary.app.viewmodel.SecretaryViewModel

private enum class TopDestination(val label: String, val icon: ImageVector) {
    BRIEFING("Briefing", Icons.Default.Dashboard),
    ADVISOR("Advisor", Icons.Default.SupportAgent),
    SCHEDULE("Schedule", Icons.Default.EventNote),
    FINANCES("Finances", Icons.Default.AccountBalanceWallet),
    HEALTH_SETUP("Health", Icons.AutoMirrored.Filled.DirectionsWalk)
}

class MainActivity : ComponentActivity() {

    private val viewModel: SecretaryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.parseColor("#0B0F17"))
        )

        setContent {
            PersonalSecretaryTheme {
                PersonalSecretaryAppRoot(
                    viewModel = viewModel,
                    onOpenBatterySettings = {
                        runCatching { startActivity(viewModel.stepTracker.createAppSettingsIntent()) }
                    },
                    onOpenHealthConnectSettings = {
                        runCatching { startActivity(viewModel.stepTracker.createHealthConnectSettingsIntent()) }
                    },
                    onOpenNotificationAccessSettings = {
                        runCatching { startActivity(EmailNotificationAccessHelper.createNotificationAccessIntent()) }
                    },
                    onLaunchApkInstaller = { apkFile ->
                        runCatching { startActivity(viewModel.otaManager.createInstallApkIntent(apkFile)) }
                    },
                    onOpenUnknownSourcesSettings = {
                        runCatching { startActivity(viewModel.otaManager.createUnknownSourcesSettingsIntent()) }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onForegroundResume()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onBackgroundPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            viewModel.onMemoryPressure()
        }
    }
}

@Composable
private fun PersonalSecretaryAppRoot(
    viewModel: SecretaryViewModel,
    onOpenBatterySettings: () -> Unit,
    onOpenHealthConnectSettings: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
    onLaunchApkInstaller: (java.io.File) -> Unit,
    onOpenUnknownSourcesSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var currentTab by remember { mutableStateOf(TopDestination.BRIEFING) }
    var healthSubTab by remember { mutableStateOf(HubSubTab.STEPS) }
    var pendingVideoSlot by remember { mutableStateOf<SecretaryVideoSlot?>(null) }

    val healthConnectLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) {
        viewModel.refreshPermissionsAndSteps()
    }

    val activityRecognitionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissionsAndSteps()
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshScheduleAndCalendar()
    }

    val csvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importCsvOrTsvFile(uri)
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importLiteRtModelUri(uri)
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val slot = pendingVideoSlot
        pendingVideoSlot = null
        if (uri != null && slot != null) {
            viewModel.importGoogleFlowVideo(slot, uri)
        }
    }

    Scaffold(
        containerColor = ObsidianBg,
        bottomBar = {
            NavigationBar(containerColor = CardSurface) {
                TopDestination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = currentTab == dest,
                        onClick = { currentTab = dest },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentGold,
                            selectedTextColor = AccentGold,
                            indicatorColor = AccentGoldSoft,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                TopDestination.BRIEFING -> BriefingScreen(
                    snapshot = uiState.snapshot,
                    profile = uiState.profile,
                    activeVideoSlot = uiState.activeVideoSlot,
                    videoSlots = uiState.videoSlots,
                    engineStatusLabel = uiState.engineState.activeBackendLabel,
                    briefingText = uiState.briefingText,
                    isGeneratingBriefing = uiState.isGeneratingBriefing,
                    isSyncingAll = uiState.isSyncingFinances,
                    availableOtaVersion = uiState.otaState.availableRelease?.versionName,
                    onOpenOtaSetup = {
                        healthSubTab = HubSubTab.SETUP
                        currentTab = TopDestination.HEALTH_SETUP
                    },
                    onGenerateBriefing = { viewModel.generateExecutiveBriefing() },
                    onRefreshAll = { viewModel.syncAllSources() },
                    onToggleMute = { viewModel.toggleVideoMute() },
                    onOpenAdvisor = { preset ->
                        currentTab = TopDestination.ADVISOR
                        if (!preset.isNullOrBlank()) viewModel.sendConsultationMessage(preset)
                    },
                    onOpenSchedule = { currentTab = TopDestination.SCHEDULE },
                    onOpenFinances = { currentTab = TopDestination.FINANCES },
                    onOpenPhysical = {
                        healthSubTab = HubSubTab.STEPS
                        currentTab = TopDestination.HEALTH_SETUP
                    }
                )

                TopDestination.SCHEDULE -> ScheduleScreen(
                    snapshot = uiState.snapshot,
                    tasks = uiState.tasks,
                    emailAlerts = uiState.emailAlerts,
                    calendarPermissionGranted = uiState.calendarPermissionGranted,
                    notificationAccessGranted = uiState.notificationAccessGranted,
                    statusToast = uiState.statusToast,
                    onRequestCalendarPermission = {
                        val perms = buildList {
                            add(Manifest.permission.READ_CALENDAR)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        calendarPermissionLauncher.launch(perms)
                    },
                    onOpenNotificationAccessSettings = onOpenNotificationAccessSettings,
                    onOpenAppInfoSettings = onOpenBatterySettings,
                    onRefreshSchedule = {
                        viewModel.refreshScheduleAndCalendar()
                        viewModel.refreshEmailInbox()
                    },
                    onAddTask = { title, notes, dueMillis, priority, category, recurrence, reminder ->
                        viewModel.addSecretaryTask(
                            title = title,
                            notes = notes,
                            dueAtMillis = dueMillis,
                            priority = priority,
                            category = category,
                            recurrence = recurrence,
                            reminderEnabled = reminder
                        )
                    },
                    onToggleTask = { viewModel.toggleTaskCompleted(it) },
                    onDeleteTask = { viewModel.deleteSecretaryTask(it) },
                    onMarkEmailRead = { viewModel.markEmailRead(it) },
                    onMarkAllEmailsRead = { viewModel.markAllEmailsRead() },
                    onConvertEmailToTask = { viewModel.convertEmailToTask(it) },
                    onDeleteEmailAlert = { viewModel.deleteEmailAlert(it) },
                    onClearEmailAlerts = { viewModel.clearEmailAlerts() },
                    onAskClaraAboutSchedule = {
                        currentTab = TopDestination.ADVISOR
                        viewModel.sendConsultationMessage("Prioritize my schedule, meetings, open work tasks, and urgent emails for today alongside my budget and energy.")
                    },
                    onAskClaraAboutInbox = {
                        currentTab = TopDestination.ADVISOR
                        viewModel.sendConsultationMessage("Summarize my recent captured emails, highlight any urgent bills or work deadlines, and list what needs action first.")
                    }
                )

                TopDestination.ADVISOR -> AdvisorScreen(
                    profile = uiState.profile,
                    messages = uiState.chatMessages,
                    streamingReply = uiState.streamingReply,
                    isGenerating = uiState.isGeneratingChat,
                    engineStatusLabel = uiState.engineState.activeBackendLabel,
                    isGemmaReady = uiState.engineState.isReady,
                    activeVideoSlot = uiState.activeVideoSlot,
                    videoSlots = uiState.videoSlots,
                    onSendMessage = { viewModel.sendConsultationMessage(it) },
                    onStopGeneration = { viewModel.stopGeneration() },
                    onClearChat = { viewModel.clearChatHistory() },
                    onToggleVoiceBriefing = { viewModel.toggleVoiceBriefing() },
                    onToggleVideoMute = { viewModel.toggleVideoMute() },
                    onOpenBrainSetup = {
                        healthSubTab = HubSubTab.SETUP
                        currentTab = TopDestination.HEALTH_SETUP
                    }
                )

                TopDestination.FINANCES -> FinancesScreen(
                    snapshot = uiState.snapshot,
                    profile = uiState.profile,
                    transactions = uiState.transactions,
                    isSyncing = uiState.isSyncingFinances,
                    syncMessage = uiState.syncStatusMessage,
                    onSyncNow = { viewModel.syncDayBook() },
                    onAddTransaction = { desc, amt, isDebit, cat, pushRemote ->
                        viewModel.addManualTransaction(desc, amt, isDebit, cat, pushRemote)
                    },
                    onNavigateToSetup = {
                        healthSubTab = HubSubTab.SETUP
                        currentTab = TopDestination.HEALTH_SETUP
                    }
                )

                TopDestination.HEALTH_SETUP -> HealthAndSetupScreen(
                    selectedSubTab = healthSubTab,
                    onSelectSubTab = { healthSubTab = it },
                    snapshot = uiState.snapshot,
                    profile = uiState.profile,
                    stepPermissions = uiState.stepPermissions,
                    videoSlots = uiState.videoSlots,
                    detectedModelFiles = uiState.detectedModels,
                    engineStatusLabel = uiState.engineState.activeBackendLabel,
                    isModelLoading = uiState.engineState.isLoading,
                    isGemmaLoaded = uiState.engineState.isReady,
                    modelImportProgress = uiState.modelImportProgress,
                    otaState = uiState.otaState,
                    statusToast = uiState.statusToast,
                    onRequestHealthConnectPermissions = {
                        if (uiState.stepPermissions.healthConnectAvailable) {
                            runCatching {
                                healthConnectLauncher.launch(viewModel.stepTracker.healthConnectReadPermissions)
                            }.onFailure {
                                runCatching {
                                    healthConnectLauncher.launch(viewModel.stepTracker.coreHealthConnectPermissions)
                                }
                            }
                        } else {
                            onOpenHealthConnectSettings()
                        }
                    },
                    onRequestActivityRecognition = {
                        val perms = buildList {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                add(Manifest.permission.ACTIVITY_RECOGNITION)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        if (perms.isNotEmpty()) activityRecognitionLauncher.launch(perms)
                    },
                    onOpenColorOsBatterySettings = onOpenBatterySettings,
                    onOpenHealthConnectSettings = onOpenHealthConnectSettings,
                    onSyncSteps = { viewModel.refreshPermissionsAndSteps() },
                    onLogManualSteps = { viewModel.logManualSteps(it) },
                    onSaveProfile = { viewModel.updateProfile(it) },
                    onSyncDayBook = { viewModel.syncDayBook() },
                    onImportCsvPicker = {
                        csvPickerLauncher.launch(arrayOf("text/*", "application/json", "*/*"))
                    },
                    onPickLiteRtModel = {
                        modelPickerLauncher.launch(arrayOf("*/*"))
                    },
                    onScanModels = { viewModel.scanDeviceForModels() },
                    onSelectModelPath = { viewModel.selectModelPath(it) },
                    onLoadGemma = { viewModel.loadGemmaModel() },
                    onUnloadGemma = { viewModel.unloadGemmaModel() },
                    onPickFlowVideo = { slot ->
                        pendingVideoSlot = slot
                        videoPickerLauncher.launch(arrayOf("video/mp4", "video/*"))
                    },
                    onRemoveFlowVideo = { viewModel.removeGoogleFlowVideo(it) },
                    onCheckForUpdate = { viewModel.checkForOtaUpdate(silent = false) },
                    onDownloadAndInstallUpdate = {
                        viewModel.downloadAndInstallOtaUpdate(
                            onLaunchInstaller = onLaunchApkInstaller,
                            onNeedInstallPermission = onOpenUnknownSourcesSettings
                        )
                    }
                )
            }
        }
    }
}
