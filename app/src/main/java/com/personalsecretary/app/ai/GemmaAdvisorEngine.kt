package com.personalsecretary.app.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.personalsecretary.app.data.SecretaryProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class GemmaEngineState(
    val isLoading: Boolean = false,
    val isReady: Boolean = false,
    val activeBackendLabel: String = "Deterministic Consultant",
    val loadedModelName: String? = null,
    val lastError: String? = null
)

data class GenerationOutcome(
    val text: String,
    val tokensPerSec: Float?,
    val engineTag: String
)

class GemmaAdvisorEngine(private val context: Context) {

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedPath: String? = null

    private val _state = MutableStateFlow(GemmaEngineState())
    val state: StateFlow<GemmaEngineState> = _state.asStateFlow()

    suspend fun loadModel(modelPath: String, preferGpu: Boolean): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val file = File(modelPath)
                require(file.exists() && file.canRead() && file.length() > 1024L * 1024L) {
                    "Model file not found or unreadable at: $modelPath"
                }

                // Close any previously loaded engine cleanly
                closeEngineInternal()

                _state.value = GemmaEngineState(
                    isLoading = true,
                    isReady = false,
                    activeBackendLabel = "Loading ${file.name}...",
                    loadedModelName = file.name
                )

                val cacheDir = File(context.cacheDir, "litertlm_cache").apply { mkdirs() }
                var activeBackend = if (preferGpu) "GPU" else "CPU"
                var initializedEngine: Engine? = null

                if (preferGpu) {
                    initializedEngine = runCatching {
                        createAndInitEngine(file.absolutePath, Backend.GPU(), cacheDir.absolutePath)
                    }.getOrNull()
                }

                if (initializedEngine == null) {
                    activeBackend = "CPU"
                    initializedEngine = createAndInitEngine(file.absolutePath, Backend.CPU(), cacheDir.absolutePath)
                }

                engine = initializedEngine
                loadedPath = file.absolutePath

                val label = "Gemma 4 E4B ($activeBackend)"
                _state.value = GemmaEngineState(
                    isLoading = false,
                    isReady = true,
                    activeBackendLabel = label,
                    loadedModelName = file.name,
                    lastError = null
                )
                label
            }.onFailure { err ->
                closeEngineInternal()
                _state.value = GemmaEngineState(
                    isLoading = false,
                    isReady = false,
                    activeBackendLabel = "Deterministic Consultant",
                    loadedModelName = null,
                    lastError = err.localizedMessage ?: "Failed to initialize LiteRT-LM engine"
                )
            }
        }
    }

    private fun createAndInitEngine(
        modelAbsolutePath: String,
        backend: Backend,
        cacheDirPath: String
    ): Engine {
        val config = EngineConfig(
            modelPath = modelAbsolutePath,
            backend = backend,
            maxNumTokens = 4096,
            cacheDir = cacheDirPath
        )
        val newEngine = Engine(config)
        try {
            newEngine.initialize()
            return newEngine
        } catch (t: Throwable) {
            runCatching { newEngine.close() }
            throw t
        }
    }

    suspend fun generateConsultation(
        profile: SecretaryProfile,
        dossierContext: String,
        recentHistorySummary: String,
        userPrompt: String,
        onTokenChunk: (accumulatedText: String) -> Unit
    ): Result<GenerationOutcome> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val activeEngine = engine
                check(activeEngine != null && activeEngine.isInitialized()) {
                    "Gemma 4 E4B engine is not loaded."
                }

                val systemPrompt = buildExecutiveSystemPrompt(profile)
                val fullTurnPrompt = buildString {
                    appendLine(dossierContext)
                    if (recentHistorySummary.isNotBlank()) {
                        appendLine()
                        appendLine("=== RECENT CONSULTATION CONTEXT ===")
                        appendLine(recentHistorySummary)
                    }
                    appendLine()
                    appendLine("=== CLIENT REQUEST ===")
                    appendLine(userPrompt)
                }

                val convConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = SamplerConfig(
                        topK = 40,
                        topP = 0.92,
                        temperature = 0.55
                    )
                )

                val sb = StringBuilder()
                val startNanos = System.nanoTime()
                var chunkCount = 0

                activeEngine.createConversation(convConfig).use { conversation ->
                    conversation.sendMessageAsync(fullTurnPrompt).collect { message ->
                        val textPart = message.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        if (textPart.isNotEmpty()) {
                            sb.append(textPart)
                            chunkCount++
                            onTokenChunk(sb.toString())
                        }
                    }
                }

                val elapsedSec = ((System.nanoTime() - startNanos) / 1_000_000_000.0).coerceAtLeast(0.05)
                val approxWords = sb.toString().trim().split(Regex("\\s+")).count { it.isNotEmpty() }
                val estTokens = maxOf(chunkCount, (approxWords * 1.3).toInt())
                val tokPerSec = (estTokens / elapsedSec).toFloat()

                GenerationOutcome(
                    text = sb.toString().trim().ifEmpty { "No response generated. Please try rephrasing." },
                    tokensPerSec = tokPerSec,
                    engineTag = _state.value.activeBackendLabel
                )
            }
        }
    }

    suspend fun unload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeEngineInternal()
            _state.value = GemmaEngineState(
                isLoading = false,
                isReady = false,
                activeBackendLabel = "Deterministic Consultant",
                loadedModelName = null,
                lastError = null
            )
        }
    }

    private fun closeEngineInternal() {
        val current = engine
        engine = null
        loadedPath = null
        if (current != null) {
            runCatching { current.close() }
        }
    }

    private fun buildExecutiveSystemPrompt(profile: SecretaryProfile): String {
        return """
            You are ${profile.secretaryName}, ${profile.userName}'s private executive secretary and chief-of-staff covering financial status, physical & watch health, and work schedules/reminders.

            Role & Tone:
            - Speak like a composed, sharp, trustworthy executive secretary and consultant — concise, structured, practical, and completely free of guilt or preachiness.
            - Never act like a virtual pet or needy companion. Focus strictly on ${profile.userName}'s financial clarity, daily physical & watch health (steps, sleep, HR, workouts), and work schedule/task execution.
            - Always cite exact numbers, meetings, and task titles strictly from the provided EXECUTIVE CLIENT DOSSIER. Never invent transactions, balances, vitals, calendar events, or deadlines.
            - Connect insights across all three pillars when helpful (e.g., fitting a brisk walk between meetings, flagging overdue bills/tasks alongside budget burn, or pacing work around short sleep).
            - Keep answers tight and scannable: lead with the bottom line, use 3–5 concise bullet points, and close with 1–2 concrete next actions.
            - Safety boundary: You provide executive organization and coaching, not medical diagnosis or licensed legal/financial advice.
        """.trimIndent()
    }
}
