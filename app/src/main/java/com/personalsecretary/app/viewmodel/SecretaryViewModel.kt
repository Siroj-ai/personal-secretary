package com.personalsecretary.app.viewmodel

import android.app.Application
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personalsecretary.app.BuildConfig
import com.personalsecretary.app.ai.GemmaAdvisorEngine
import com.personalsecretary.app.ai.GemmaEngineState
import com.personalsecretary.app.data.AccountType
import com.personalsecretary.app.data.ChatMessage
import com.personalsecretary.app.data.ChatRole
import com.personalsecretary.app.data.DailyStepRecord
import com.personalsecretary.app.data.DayBookParserAndHygiene
import com.personalsecretary.app.data.DayBookSyncClient
import com.personalsecretary.app.data.EarningEntry
import com.personalsecretary.app.data.ExecutiveAnalyticsEngine
import com.personalsecretary.app.data.ExecutiveSnapshot
import com.personalsecretary.app.data.FinancialHygieneReport
import com.personalsecretary.app.data.InsightSeverity
import com.personalsecretary.app.data.MentalCheckIn
import com.personalsecretary.app.data.SecretaryDatabase
import com.personalsecretary.app.data.SecretaryProfile
import com.personalsecretary.app.data.SecretaryVideoSlot
import com.personalsecretary.app.data.SsfSummary
import com.personalsecretary.app.data.StepSource
import com.personalsecretary.app.data.TransactionRecord
import com.personalsecretary.app.health.OnePlusStepTracker
import com.personalsecretary.app.health.StepPermissionStatus
import com.personalsecretary.app.media.SecretaryMediaStore
import com.personalsecretary.app.update.OtaUpdateManager
import com.personalsecretary.app.update.OtaUpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SecretaryUiState(
    val profile: SecretaryProfile = SecretaryProfile(),
    val snapshot: ExecutiveSnapshot = ExecutiveSnapshot(),
    val transactions: List<TransactionRecord> = emptyList(),
    val checkIns: List<MentalCheckIn> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val streamingReply: String = "",
    val isGeneratingChat: Boolean = false,
    val briefingText: String = "",
    val isGeneratingBriefing: Boolean = false,
    val isSyncingFinances: Boolean = false,
    val syncStatusMessage: String? = null,
    val stepPermissions: StepPermissionStatus = StepPermissionStatus(),
    val engineState: GemmaEngineState = GemmaEngineState(),
    val videoSlots: Map<String, String> = emptyMap(),
    val detectedModels: List<File> = emptyList(),
    val modelImportProgress: String? = null,
    val otaState: OtaUpdateState = OtaUpdateState(),
    val statusToast: String? = null
) {
    val activeVideoSlot: SecretaryVideoSlot
        get() = when {
            (isGeneratingChat && streamingReply.isEmpty()) || isGeneratingBriefing -> SecretaryVideoSlot.THINKING
            isGeneratingChat && streamingReply.isNotEmpty() -> SecretaryVideoSlot.SPEAKING
            snapshot.insights.any { it.severity == InsightSeverity.CAUTION } &&
                videoSlots.containsKey(SecretaryVideoSlot.ALERT.slotKey) -> SecretaryVideoSlot.ALERT
            else -> SecretaryVideoSlot.IDLE
        }
}

class SecretaryViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val database = SecretaryDatabase(application)
    private val syncClient = DayBookSyncClient()
    private val mediaStore = SecretaryMediaStore(application)
    val stepTracker = OnePlusStepTracker(application, database, viewModelScope)
    val otaManager = OtaUpdateManager(application)
    private val gemmaEngine = GemmaAdvisorEngine(application)

    private val _uiState = MutableStateFlow(SecretaryUiState())
    val uiState: StateFlow<SecretaryUiState> = _uiState.asStateFlow()

    private var ssfCache = SsfSummary()
    private var earningsCache = emptyList<EarningEntry>()
    private var hygieneCache = FinancialHygieneReport()
    private var stepsCache = emptyList<DailyStepRecord>()

    private var generationJob: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(application, this)
        observeEngineAndSensors()
        loadInitialState()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
        }
    }

    private fun observeEngineAndSensors() {
        viewModelScope.launch {
            gemmaEngine.state.collect { st ->
                _uiState.update { it.copy(engineState = st) }
            }
        }
        viewModelScope.launch {
            stepTracker.permissionStatus.collect { perms ->
                _uiState.update { it.copy(stepPermissions = perms) }
            }
        }
        viewModelScope.launch {
            stepTracker.liveHardwareStepsToday.collect { live ->
                if (live != null) {
                    stepsCache = database.loadStepRecords(60)
                    recomputeSnapshot()
                }
            }
        }
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            val profile = database.loadProfile()
            val txns = database.loadTransactions()
            ssfCache = database.loadSsfSummary()
            earningsCache = database.loadEarnings()
            hygieneCache = database.loadHygieneReport()
            stepsCache = database.loadStepRecords(60)
            val checkIns = database.loadCheckIns(60)
            val chat = database.loadChatMessages(80)
            val videos = database.loadVideoSlots()
            val briefing = database.loadLatestBriefing()
            val detected = withContext(Dispatchers.IO) { mediaStore.scanKnownModelPaths() }

            // Auto-fill model path if not set yet and gemma-4-E4B-it.litertlm is already on device
            val resolvedProfile = if (profile.modelPath.isBlank() && detected.isNotEmpty()) {
                val preferred = detected.firstOrNull { it.name.contains("gemma-4", ignoreCase = true) } ?: detected.first()
                profile.copy(modelPath = preferred.absolutePath).also { database.saveProfile(it) }
            } else {
                profile
            }

            _uiState.update {
                it.copy(
                    profile = resolvedProfile,
                    transactions = txns,
                    checkIns = checkIns,
                    chatMessages = chat,
                    videoSlots = videos,
                    briefingText = briefing,
                    detectedModels = detected
                )
            }
            recomputeSnapshot()
            refreshPermissionsAndSteps()
            if (resolvedProfile.autoLoadModelOnStart && resolvedProfile.modelPath.isNotBlank() && File(resolvedProfile.modelPath).exists()) {
                loadGemmaModel()
            }
            if (resolvedProfile.autoCheckUpdates && resolvedProfile.githubRepo.contains("/")) {
                checkForOtaUpdate(silent = true)
            }
        }
    }

    private fun recomputeSnapshot() {
        val current = _uiState.value
        val snap = ExecutiveAnalyticsEngine.computeSnapshot(
            transactions = current.transactions,
            ssf = ssfCache,
            earnings = earningsCache,
            hygiene = hygieneCache,
            steps = stepsCache,
            checkIns = current.checkIns,
            profile = current.profile
        )
        _uiState.update { it.copy(snapshot = snap) }
    }

    fun onForegroundResume() {
        stepTracker.startHardwareSensorIfPermitted()
        refreshPermissionsAndSteps()
    }

    fun onBackgroundPause() {
        stepTracker.stopHardwareSensor()
    }

    fun onMemoryPressure() {
        viewModelScope.launch {
            if (_uiState.value.engineState.isReady && !_uiState.value.isGeneratingChat) {
                gemmaEngine.unload()
            }
        }
    }

    fun refreshPermissionsAndSteps() {
        viewModelScope.launch {
            val status = stepTracker.refreshPermissionStatus()
            if (status.activityRecognitionGranted) {
                stepTracker.startHardwareSensorIfPermitted()
            }
            if (status.healthConnectStepsGranted) {
                stepTracker.syncStepsFromHealthConnect(days = 14)
            }
            stepsCache = database.loadStepRecords(60)
            recomputeSnapshot()
        }
    }

    fun syncAllSources() {
        refreshPermissionsAndSteps()
        syncDayBook()
    }

    fun syncDayBook() {
        val profile = _uiState.value.profile
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingFinances = true, syncStatusMessage = "Syncing Day Book...") }
            val result = when {
                profile.appsScriptUrl.startsWith("https://") ->
                    syncClient.syncFromAppsScript(profile.appsScriptUrl)
                profile.spreadsheetId.isNotBlank() ->
                    syncClient.syncFromPublicSheetCsv(profile.spreadsheetId)
                else -> Result.failure(IllegalStateException("Configure your Apps Script URL or import a CSV/TSV in Setup."))
            }

            result.onSuccess { bundle ->
                database.saveFinancialBundle(
                    transactions = bundle.transactions,
                    ssf = bundle.ssfSummary,
                    earnings = bundle.earnings,
                    abbreviations = bundle.abbreviations,
                    hygiene = bundle.hygieneReport
                )
                ssfCache = bundle.ssfSummary
                earningsCache = bundle.earnings
                hygieneCache = bundle.hygieneReport
                _uiState.update {
                    it.copy(
                        transactions = bundle.transactions,
                        isSyncingFinances = false,
                        syncStatusMessage = "Synced ${bundle.hygieneReport.uniqueDebitRows} unique rows (${bundle.hygieneReport.duplicatesRemoved} duplicates filtered).",
                        statusToast = "Day Book synced cleanly."
                    )
                }
                recomputeSnapshot()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSyncingFinances = false,
                        syncStatusMessage = err.localizedMessage ?: "Day Book sync failed."
                    )
                }
            }
        }
    }

    fun importCsvOrTsvFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isSyncingFinances = true, statusToast = "Importing spreadsheet file...") }
            runCatching {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("Unable to read selected file.")
                val ts = SimpleDateFormat("MMM d, hh:mm a", Locale.getDefault()).format(Date())
                val bundle = if (text.trimStart().startsWith("{")) {
                    DayBookParserAndHygiene.parseAppsScriptBootstrapJson(text, "Imported JSON ($ts)")
                } else {
                    DayBookParserAndHygiene.parseCsvOrTsvDebitSheet(text, "Imported CSV/TSV ($ts)")
                }
                database.saveFinancialBundle(
                    transactions = bundle.transactions,
                    ssf = bundle.ssfSummary,
                    earnings = bundle.earnings,
                    abbreviations = bundle.abbreviations,
                    hygiene = bundle.hygieneReport
                )
                ssfCache = bundle.ssfSummary
                earningsCache = bundle.earnings
                hygieneCache = bundle.hygieneReport
                _uiState.update {
                    it.copy(
                        transactions = bundle.transactions,
                        isSyncingFinances = false,
                        syncStatusMessage = "Imported ${bundle.hygieneReport.uniqueDebitRows} unique rows (${bundle.hygieneReport.duplicatesRemoved} duplicates removed).",
                        statusToast = "Spreadsheet imported successfully."
                    )
                }
                recomputeSnapshot()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSyncingFinances = false,
                        statusToast = "Import failed: ${err.localizedMessage}"
                    )
                }
            }
        }
    }

    fun addManualTransaction(
        description: String,
        amount: Double,
        isDebit: Boolean,
        category: String,
        pushRemote: Boolean
    ) {
        viewModelScope.launch {
            val today = LocalDate.now()
            val iso = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val sheetDate = "${today.monthValue}/${today.dayOfMonth}/${today.year}"
            val currentBal = _uiState.value.snapshot.debitBalance
            val nextBal = DayBookParserAndHygiene.round2(if (isDebit) currentBal - amount else currentBal + amount)
            val txId = "local_${UUID.randomUUID().toString().take(8)}"

            val abbrev = database.loadAbbreviations().ifEmpty { DayBookParserAndHygiene.defaultAbbreviations() }
            val normalized = DayBookParserAndHygiene.normalizeDescription(description, abbrev)
            val resolvedCat = category.ifBlank {
                DayBookParserAndHygiene.inferCategory(normalized, if (isDebit) amount else 0.0, if (!isDebit) amount else 0.0)
            }

            val record = TransactionRecord(
                id = txId,
                accountType = AccountType.DEBIT,
                dateIso = iso,
                rawDate = sheetDate,
                description = description,
                normalizedDescription = normalized,
                debit = if (isDebit) amount else 0.0,
                credit = if (!isDebit) amount else 0.0,
                balance = nextBal,
                category = resolvedCat
            )
            database.appendSingleTransaction(record)
            val updated = database.loadTransactions()
            hygieneCache = hygieneCache.copy(uniqueDebitRows = updated.count { it.accountType == AccountType.DEBIT })
            _uiState.update { it.copy(transactions = updated, statusToast = "Transaction saved locally.") }
            recomputeSnapshot()

            val scriptUrl = _uiState.value.profile.appsScriptUrl
            if (pushRemote && scriptUrl.startsWith("https://")) {
                val res = syncClient.postTransactionToAppsScript(
                    rawUrl = scriptUrl,
                    sheetName = "Debit Card",
                    dateDisplay = sheetDate,
                    description = description,
                    amount = amount,
                    isDebit = isDebit,
                    newBalance = nextBal,
                    category = resolvedCat,
                    requestId = txId
                )
                res.onSuccess {
                    _uiState.update { it.copy(statusToast = "Saved locally and pushed to Day Book.") }
                }.onFailure { err ->
                    _uiState.update { it.copy(statusToast = err.localizedMessage) }
                }
            }
        }
    }

    fun logManualSteps(stepsToday: Long) {
        viewModelScope.launch {
            val iso = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            database.upsertStepRecords(
                listOf(DailyStepRecord(iso, stepsToday, StepSource.MANUAL, System.currentTimeMillis()))
            )
            stepsCache = database.loadStepRecords(60)
            recomputeSnapshot()
            _uiState.update { it.copy(statusToast = "Updated today's steps to $stepsToday.") }
        }
    }

    fun saveMentalCheckIn(
        mood: Int,
        energy: Int,
        stress: Int,
        sleepHours: Float,
        tags: List<String>,
        note: String
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val iso = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val item = MentalCheckIn(
                id = "ci_${UUID.randomUUID().toString().take(8)}",
                timestampMillis = now,
                dateIso = iso,
                mood = mood,
                energy = energy,
                stress = stress,
                sleepHours = sleepHours,
                tags = tags,
                note = note
            )
            database.saveCheckIn(item)
            val all = database.loadCheckIns(60)
            _uiState.update { it.copy(checkIns = all, statusToast = "Private check-in recorded.") }
            recomputeSnapshot()
        }
    }

    fun deleteMentalCheckIn(id: String) {
        viewModelScope.launch {
            database.deleteCheckIn(id)
            val all = database.loadCheckIns(60)
            _uiState.update { it.copy(checkIns = all) }
            recomputeSnapshot()
        }
    }

    fun updateProfile(updated: SecretaryProfile) {
        viewModelScope.launch {
            database.saveProfile(updated)
            _uiState.update { it.copy(profile = updated, statusToast = "Executive profile saved.") }
            recomputeSnapshot()
        }
    }

    fun toggleVideoMute() {
        val current = _uiState.value.profile
        updateProfile(current.copy(videoMuted = !current.videoMuted))
    }

    fun toggleVoiceBriefing() {
        val current = _uiState.value.profile
        val next = !current.voiceBriefingEnabled
        if (!next) tts?.stop()
        updateProfile(current.copy(voiceBriefingEnabled = next))
    }

    fun scanDeviceForModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val found = mediaStore.scanKnownModelPaths()
            _uiState.update {
                it.copy(
                    detectedModels = found,
                    statusToast = if (found.isEmpty()) {
                        "No .litertlm files auto-detected. Use 'Pick .litertlm' or place gemma-4-E4B-it.litertlm in ${mediaStore.getAppModelsDirectory().absolutePath}"
                    } else {
                        "Found ${found.size} .litertlm model file(s)."
                    }
                )
            }
        }
    }

    fun selectModelPath(path: String) {
        val updated = _uiState.value.profile.copy(modelPath = path)
        viewModelScope.launch {
            database.saveProfile(updated)
            _uiState.update { it.copy(profile = updated, statusToast = "Selected model: ${File(path).name}") }
        }
    }

    fun importLiteRtModelUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(modelImportProgress = "Importing .litertlm model to private app storage...") }
            val result = mediaStore.importLiteRtModel(uri) { copiedMb, totalMb ->
                val msg = if (totalMb != null && totalMb > 0) {
                    "Copying model: $copiedMb MB / $totalMb MB..."
                } else {
                    "Copying model: $copiedMb MB..."
                }
                _uiState.update { it.copy(modelImportProgress = msg) }
            }
            result.onSuccess { absPath ->
                val updated = _uiState.value.profile.copy(modelPath = absPath)
                database.saveProfile(updated)
                val found = withContext(Dispatchers.IO) { mediaStore.scanKnownModelPaths() }
                _uiState.update {
                    it.copy(
                        profile = updated,
                        detectedModels = found,
                        modelImportProgress = null,
                        statusToast = "Model ready at $absPath. Tap 'Load Gemma 4 E4B'."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        modelImportProgress = null,
                        statusToast = "Model import failed: ${err.localizedMessage}"
                    )
                }
            }
        }
    }

    fun loadGemmaModel() {
        val profile = _uiState.value.profile
        if (profile.modelPath.isBlank()) {
            _uiState.update { it.copy(statusToast = "Select gemma-4-E4B-it.litertlm first.") }
            return
        }
        viewModelScope.launch {
            val res = gemmaEngine.loadModel(profile.modelPath, profile.preferGpuBackend)
            res.onSuccess { label ->
                _uiState.update { it.copy(statusToast = "$label loaded and ready.") }
            }.onFailure { err ->
                _uiState.update { it.copy(statusToast = "Load failed: ${err.localizedMessage}") }
            }
        }
    }

    fun unloadGemmaModel() {
        viewModelScope.launch {
            gemmaEngine.unload()
            _uiState.update { it.copy(statusToast = "Gemma 4 E4B unloaded from memory.") }
        }
    }

    fun importGoogleFlowVideo(slot: SecretaryVideoSlot, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(statusToast = "Importing Google Flow video for ${slot.title}...") }
            val result = mediaStore.importGoogleFlowVideo(slot, uri)
            result.onSuccess { path ->
                database.saveVideoSlotPath(slot, path)
                val slots = database.loadVideoSlots()
                _uiState.update {
                    it.copy(
                        videoSlots = slots,
                        statusToast = "Google Flow video assigned to ${slot.title}."
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(statusToast = "Video import failed: ${err.localizedMessage}") }
            }
        }
    }

    fun removeGoogleFlowVideo(slot: SecretaryVideoSlot) {
        viewModelScope.launch {
            val existing = _uiState.value.videoSlots[slot.slotKey]
            mediaStore.deleteSlotVideo(existing)
            database.saveVideoSlotPath(slot, null)
            val slots = database.loadVideoSlots()
            _uiState.update { it.copy(videoSlots = slots, statusToast = "Removed video from ${slot.title}.") }
        }
    }

    fun generateExecutiveBriefing() {
        val st = _uiState.value
        if (st.isGeneratingBriefing) return

        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingBriefing = true) }
            if (st.engineState.isReady) {
                val dossier = ExecutiveAnalyticsEngine.buildGemmaDossier(
                    snapshot = st.snapshot,
                    profile = st.profile,
                    recentTransactions = st.transactions,
                    recentCheckIns = st.checkIns
                )
                val prompt = "Deliver my concise Executive Briefing covering: 1) Financial Health & Cash Flow, 2) OnePlus 13 Steps & Physical Pace, 3) Mental Bandwidth & Stress, and 4) Top 2 concrete actions for today."
                val res = gemmaEngine.generateConsultation(
                    profile = st.profile,
                    dossierContext = dossier,
                    recentHistorySummary = "",
                    userPrompt = prompt,
                    onTokenChunk = { partial ->
                        _uiState.update { it.copy(briefingText = partial) }
                    }
                )
                val finalBrief = res.getOrNull()?.text
                    ?: ExecutiveAnalyticsEngine.buildDeterministicBriefing(st.snapshot, st.profile)
                database.saveLatestBriefing(finalBrief)
                _uiState.update { it.copy(briefingText = finalBrief, isGeneratingBriefing = false) }
                if (st.profile.voiceBriefingEnabled) speakText(finalBrief)
            } else {
                val deterministic = ExecutiveAnalyticsEngine.buildDeterministicBriefing(st.snapshot, st.profile)
                database.saveLatestBriefing(deterministic)
                _uiState.update { it.copy(briefingText = deterministic, isGeneratingBriefing = false) }
                if (st.profile.voiceBriefingEnabled) speakText(deterministic)
            }
        }
    }

    fun sendConsultationMessage(userText: String) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty() || _uiState.value.isGeneratingChat) return

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            val userMsg = ChatMessage(
                id = "u_${UUID.randomUUID().toString().take(8)}",
                role = ChatRole.USER,
                content = trimmed
            )
            database.saveChatMessage(userMsg)
            val updatedHistory = _uiState.value.chatMessages + userMsg
            _uiState.update {
                it.copy(
                    chatMessages = updatedHistory,
                    isGeneratingChat = true,
                    streamingReply = ""
                )
            }

            val st = _uiState.value
            if (st.engineState.isReady) {
                val dossier = ExecutiveAnalyticsEngine.buildGemmaDossier(
                    snapshot = st.snapshot,
                    profile = st.profile,
                    recentTransactions = st.transactions,
                    recentCheckIns = st.checkIns
                )
                val recentTurns = updatedHistory.dropLast(1).takeLast(4).joinToString("\n") { m ->
                    "${if (m.role == ChatRole.USER) "Client" else st.profile.secretaryName}: ${m.content.take(260)}"
                }
                val result = gemmaEngine.generateConsultation(
                    profile = st.profile,
                    dossierContext = dossier,
                    recentHistorySummary = recentTurns,
                    userPrompt = trimmed,
                    onTokenChunk = { partial ->
                        _uiState.update { it.copy(streamingReply = partial) }
                    }
                )

                val assistantMsg = result.fold(
                    onSuccess = { out ->
                        ChatMessage(
                            id = "a_${UUID.randomUUID().toString().take(8)}",
                            role = ChatRole.ASSISTANT,
                            content = out.text,
                            engineTag = out.engineTag,
                            tokensPerSec = out.tokensPerSec
                        )
                    },
                    onFailure = { err ->
                        val fallback = ExecutiveAnalyticsEngine.buildDeterministicReply(trimmed, st.snapshot, st.profile)
                        ChatMessage(
                            id = "a_${UUID.randomUUID().toString().take(8)}",
                            role = ChatRole.ASSISTANT,
                            content = "$fallback\n\n(Gemma runtime note: ${err.localizedMessage})",
                            engineTag = "Deterministic Fallback"
                        )
                    }
                )

                database.saveChatMessage(assistantMsg)
                _uiState.update {
                    it.copy(
                        chatMessages = it.chatMessages + assistantMsg,
                        isGeneratingChat = false,
                        streamingReply = ""
                    )
                }
                if (_uiState.value.profile.voiceBriefingEnabled) speakText(assistantMsg.content)
            } else {
                val replyText = ExecutiveAnalyticsEngine.buildDeterministicReply(trimmed, st.snapshot, st.profile)
                val assistantMsg = ChatMessage(
                    id = "a_${UUID.randomUUID().toString().take(8)}",
                    role = ChatRole.ASSISTANT,
                    content = replyText,
                    engineTag = "Deterministic Consultant"
                )
                database.saveChatMessage(assistantMsg)
                _uiState.update {
                    it.copy(
                        chatMessages = it.chatMessages + assistantMsg,
                        isGeneratingChat = false,
                        streamingReply = ""
                    )
                }
                if (_uiState.value.profile.voiceBriefingEnabled) speakText(replyText)
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        tts?.stop()
        val partial = _uiState.value.streamingReply.trim()
        if (partial.isNotEmpty()) {
            viewModelScope.launch {
                val partialMsg = ChatMessage(
                    id = "a_${UUID.randomUUID().toString().take(8)}",
                    role = ChatRole.ASSISTANT,
                    content = "$partial [stopped]",
                    engineTag = _uiState.value.engineState.activeBackendLabel
                )
                database.saveChatMessage(partialMsg)
                _uiState.update {
                    it.copy(
                        chatMessages = it.chatMessages + partialMsg,
                        isGeneratingChat = false,
                        streamingReply = ""
                    )
                }
            }
        } else {
            _uiState.update { it.copy(isGeneratingChat = false, streamingReply = "") }
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            database.clearChatHistory()
            _uiState.update { it.copy(chatMessages = emptyList(), streamingReply = "") }
        }
    }

    fun checkForOtaUpdate(silent: Boolean = false) {
        val profile = _uiState.value.profile
        if (_uiState.value.otaState.isChecking) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    otaState = it.otaState.copy(
                        isChecking = true,
                        statusMessage = if (silent) it.otaState.statusMessage else "Checking GitHub Releases..."
                    )
                )
            }
            val result = otaManager.checkForUpdate(
                repoSlug = profile.githubRepo,
                currentVersionName = BuildConfig.VERSION_NAME,
                githubToken = profile.githubToken
            )
            result.onSuccess { release ->
                _uiState.update {
                    it.copy(
                        otaState = it.otaState.copy(
                            isChecking = false,
                            availableRelease = release,
                            needsInstallPermission = !otaManager.canRequestPackageInstalls(),
                            statusMessage = when {
                                release != null -> "Update v${release.versionName} is ready (${release.apkSizeBytes / (1024L * 1024L)} MB)."
                                !silent -> "You are on the latest version (v${BuildConfig.VERSION_NAME})."
                                else -> null
                            }
                        )
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        otaState = it.otaState.copy(
                            isChecking = false,
                            statusMessage = if (silent) null else (err.localizedMessage ?: "Update check failed.")
                        )
                    )
                }
            }
        }
    }

    fun downloadAndInstallOtaUpdate(
        onLaunchInstaller: (File) -> Unit,
        onNeedInstallPermission: () -> Unit
    ) {
        val st = _uiState.value
        val release = st.otaState.availableRelease ?: return
        if (st.otaState.isDownloading) return

        // If already downloaded this session, launch installer directly
        val existingPath = st.otaState.downloadedApkPath
        if (existingPath != null && File(existingPath).exists()) {
            if (!otaManager.canRequestPackageInstalls()) {
                _uiState.update {
                    it.copy(
                        otaState = it.otaState.copy(
                            needsInstallPermission = true,
                            statusMessage = "Enable 'Allow from this source' for Personal Secretary, then tap Install again."
                        )
                    )
                }
                onNeedInstallPermission()
            } else {
                onLaunchInstaller(File(existingPath))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    otaState = it.otaState.copy(
                        isDownloading = true,
                        downloadProgress = 0f,
                        statusMessage = "Downloading v${release.versionName}..."
                    )
                )
            }
            val res = otaManager.downloadReleaseApk(
                release = release,
                githubToken = st.profile.githubToken,
                onProgress = { pct ->
                    _uiState.update { cur ->
                        cur.copy(otaState = cur.otaState.copy(downloadProgress = pct))
                    }
                }
            )
            res.onSuccess { apkFile ->
                val canInstall = otaManager.canRequestPackageInstalls()
                _uiState.update {
                    it.copy(
                        otaState = it.otaState.copy(
                            isDownloading = false,
                            downloadProgress = 1f,
                            downloadedApkPath = apkFile.absolutePath,
                            needsInstallPermission = !canInstall,
                            statusMessage = if (canInstall) {
                                "Verified APK ready. Opening Android installer..."
                            } else {
                                "Verified APK downloaded. Allow 'Install unknown apps' for Personal Secretary, then tap Install."
                            }
                        )
                    )
                }
                if (canInstall) onLaunchInstaller(apkFile) else onNeedInstallPermission()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        otaState = it.otaState.copy(
                            isDownloading = false,
                            statusMessage = "Download failed: ${err.localizedMessage}"
                        )
                    )
                }
            }
        }
    }

    private fun speakText(text: String) {
        if (!ttsReady) return
        val clean = text.replace("•", ". ").replace("#", "").take(900)
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "sec_brief")
    }

    override fun onCleared() {
        super.onCleared()
        stepTracker.stopHardwareSensor()
        tts?.stop()
        tts?.shutdown()
        viewModelScope.launch { gemmaEngine.unload() }
    }
}
