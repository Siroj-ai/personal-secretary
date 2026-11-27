package com.personalsecretary.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.personalsecretary.app.data.SecretaryProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

data class KokoroVoicePreset(
    val speakerId: Int,
    val code: String,
    val label: String,
    val accent: String,
    val description: String
)

data class GoogleVoiceOption(
    val name: String,
    val label: String,
    val localeTag: String,
    val isHighQuality: Boolean,
    val requiresNetwork: Boolean
)

data class VoiceEngineState(
    val isSpeaking: Boolean = false,
    val isKokoroInstalled: Boolean = false,
    val isKokoroLoaded: Boolean = false,
    val isKokoroLoading: Boolean = false,
    val kokoroModelDir: String = "",
    val isDownloadingModel: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadStatus: String? = null,
    val isGoogleTtsReady: Boolean = false,
    val activeVoiceLabel: String = "Initializing voice...",
    val availableGoogleVoices: List<GoogleVoiceOption> = emptyList(),
    val lastError: String? = null
)

/**
 * Hybrid executive voice engine for Clara:
 * 1) Primary: Offline Kokoro-82M neural TTS (via sherpa-onnx, 24 kHz studio voice, 100% private)
 * 2) Fallback: Google Speech Services (com.google.android.tts) HD neural voices
 */
class ClaraVoiceEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(VoiceEngineState())
    val state: StateFlow<VoiceEngineState> = _state.asStateFlow()

    private val kokoroMutex = Mutex()
    private var kokoroTts: OfflineTts? = null
    private var loadedKokoroDir: String = ""
    private var loadedKokoroLexicon: String = ""

    private var googleTts: TextToSpeech? = null
    private var googleTtsReady = false

    private val stopRequested = AtomicBoolean(false)
    @Volatile
    private var activeTrack: AudioTrack? = null
    private var activeSpeakJob: Job? = null
    private var downloadJob: Job? = null
    private var activeFocusRequest: AudioFocusRequest? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    init {
        initGoogleTts()
        refreshKokoroModelPresence()
    }

    private fun initGoogleTts() {
        // Explicitly prefer Google Speech Services for neural voices; fall back to system default if unavailable
        try {
            googleTts = TextToSpeech(appContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    onGoogleTtsInitialized()
                } else {
                    googleTts = TextToSpeech(appContext) { fallbackStatus ->
                        if (fallbackStatus == TextToSpeech.SUCCESS) {
                            onGoogleTtsInitialized()
                        }
                    }
                }
            }, GOOGLE_TTS_ENGINE)
        } catch (_: Throwable) {
            googleTts = TextToSpeech(appContext) { fallbackStatus ->
                if (fallbackStatus == TextToSpeech.SUCCESS) {
                    onGoogleTtsInitialized()
                }
            }
        }
    }

    private fun onGoogleTtsInitialized() {
        val engine = googleTts ?: return
        engine.language = Locale.US
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _state.update { it.copy(isSpeaking = true) }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == UTTERANCE_LAST_ID) {
                    releaseAudioFocus()
                    _state.update { it.copy(isSpeaking = false) }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                releaseAudioFocus()
                _state.update { it.copy(isSpeaking = false) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                releaseAudioFocus()
                _state.update { it.copy(isSpeaking = false) }
            }
        })

        googleTtsReady = true
        val rankedVoices = discoverGoogleEnglishVoices(engine)
        val defaultBest = rankedVoices.firstOrNull()
        if (defaultBest != null) {
            runCatching {
                val match = engine.voices?.firstOrNull { it.name == defaultBest.name }
                if (match != null) engine.voice = match
            }
        }
        _state.update {
            it.copy(
                isGoogleTtsReady = true,
                availableGoogleVoices = rankedVoices,
                activeVoiceLabel = computeVoiceLabel(
                    isKokoroInstalled = it.isKokoroInstalled,
                    isKokoroLoaded = it.isKokoroLoaded,
                    googleBest = defaultBest?.label
                )
            )
        }
    }

    fun refreshKokoroModelPresence(): File? {
        val found = locateKokoroModelDir()
        _state.update { cur ->
            cur.copy(
                isKokoroInstalled = found != null,
                kokoroModelDir = found?.absolutePath.orEmpty(),
                activeVoiceLabel = computeVoiceLabel(
                    isKokoroInstalled = found != null,
                    isKokoroLoaded = cur.isKokoroLoaded,
                    googleBest = cur.availableGoogleVoices.firstOrNull()?.label
                )
            )
        }
        return found
    }

    fun preloadKokoroIfInstalled(profile: SecretaryProfile) {
        if (profile.voiceEngineMode == MODE_GOOGLE_HD) return
        val dir = refreshKokoroModelPresence() ?: return
        scope.launch(Dispatchers.IO) {
            ensureKokoroLoaded(dir, profile.kokoroVoiceId)
        }
    }

    fun speak(rawText: String, profile: SecretaryProfile) {
        val sentences = SpeechTextFormatter.prepareSpokenSentences(rawText)
        if (sentences.isEmpty()) return

        stop()
        stopRequested.set(false)

        val useKokoro = when (profile.voiceEngineMode) {
            MODE_GOOGLE_HD -> false
            MODE_KOKORO -> locateKokoroModelDir() != null
            else -> locateKokoroModelDir() != null
        }

        if (useKokoro) {
            activeSpeakJob = scope.launch(Dispatchers.IO) {
                val dir = locateKokoroModelDir()
                if (dir == null) {
                    speakWithGoogleHd(sentences, profile)
                    return@launch
                }
                val loaded = ensureKokoroLoaded(dir, profile.kokoroVoiceId)
                if (!loaded || stopRequested.get()) {
                    if (!stopRequested.get()) {
                        speakWithGoogleHd(sentences, profile)
                    }
                    return@launch
                }
                speakWithKokoroStreaming(sentences, profile)
            }
        } else {
            speakWithGoogleHd(sentences, profile)
        }
    }

    fun speakSample(profile: SecretaryProfile) {
        val sample = "Good day ${profile.userName}. I am ${profile.secretaryName}, your private executive secretary. Everything is synced, your figures are verified, and I am ready whenever you need me."
        speak(sample, profile)
    }

    fun stop() {
        stopRequested.set(true)
        activeSpeakJob?.cancel()
        activeSpeakJob = null
        try {
            activeTrack?.let { tr ->
                if (tr.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    tr.pause()
                }
                tr.flush()
                tr.stop()
                tr.release()
            }
        } catch (_: Throwable) {
        } finally {
            activeTrack = null
        }
        runCatching { googleTts?.stop() }
        releaseAudioFocus()
        _state.update { it.copy(isSpeaking = false) }
    }

    fun downloadAndInstallKokoroModel(onCompleted: (Result<String>) -> Unit = {}) {
        if (_state.value.isDownloadingModel) return
        downloadJob?.cancel()
        downloadJob = scope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    isDownloadingModel = true,
                    downloadProgress = 0.02f,
                    downloadStatus = "Connecting to Kokoro-82M neural voice package (126 MB)...",
                    lastError = null
                )
            }
            val tempArchive = File(appContext.cacheDir, "kokoro_download.tar.bz2")
            val stagingDir = File(appContext.filesDir, "${KOKORO_DIR_NAME}_staging")
            val targetDir = File(appContext.filesDir, KOKORO_DIR_NAME)

            val result = runCatching {
                val req = Request.Builder()
                    .url(KOKORO_MODEL_URL)
                    .header("User-Agent", "PersonalSecretary-Android")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Download failed (HTTP ${resp.code})")
                    }
                    val body = resp.body ?: throw IllegalStateException("Empty download stream")
                    val totalBytes = body.contentLength().coerceAtLeast(1L)
                    FileOutputStream(tempArchive).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastUiPct = -1
                        val input = body.byteStream()
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            copied += read
                            val pct = ((copied * 75L) / totalBytes).toInt().coerceIn(1, 75)
                            if (pct != lastUiPct) {
                                lastUiPct = pct
                                val mbDone = copied / (1024L * 1024L)
                                val mbTotal = (totalBytes / (1024L * 1024L)).coerceAtLeast(1L)
                                _state.update {
                                    it.copy(
                                        downloadProgress = pct / 100f,
                                        downloadStatus = "Downloading Kokoro-82M... $mbDone / $mbTotal MB"
                                    )
                                }
                            }
                        }
                    }
                }

                _state.update {
                    it.copy(
                        downloadProgress = 0.78f,
                        downloadStatus = "Extracting neural voice weights & English lexicon..."
                    )
                }

                stagingDir.deleteRecursively()
                stagingDir.mkdirs()

                val neededFiles = setOf(
                    "model.int8.onnx",
                    "model.onnx",
                    "voices.bin",
                    "tokens.txt",
                    "lexicon-us-en.txt",
                    "lexicon-gb-en.txt"
                )
                var extractedCount = 0
                FileInputStream(tempArchive).use { fis ->
                    BufferedInputStream(fis, 64 * 1024).use { bis ->
                        BZip2CompressorInputStream(bis).use { bzis ->
                            TarArchiveInputStream(bzis).use { tais ->
                                var entry = tais.nextEntry
                                val buf = ByteArray(64 * 1024)
                                while (entry != null) {
                                    if (!entry.isDirectory) {
                                        val baseName = File(entry.name).name
                                        if (baseName in neededFiles) {
                                            val dest = File(stagingDir, baseName)
                                            FileOutputStream(dest).use { out ->
                                                while (true) {
                                                    val n = tais.read(buf)
                                                    if (n <= 0) break
                                                    out.write(buf, 0, n)
                                                }
                                            }
                                            extractedCount++
                                            val progress = (0.78f + (extractedCount * 0.04f)).coerceAtMost(0.96f)
                                            _state.update {
                                                it.copy(
                                                    downloadProgress = progress,
                                                    downloadStatus = "Extracted $baseName..."
                                                )
                                            }
                                            if (extractedCount >= 5) {
                                                // All 5 core files extracted; stop early to skip 500+ unused files!
                                                break
                                            }
                                        }
                                    }
                                    entry = tais.nextEntry
                                }
                            }
                        }
                    }
                }

                if (!isValidKokoroDir(stagingDir)) {
                    throw IllegalStateException("Extracted archive was missing required Kokoro voice files.")
                }

                targetDir.deleteRecursively()
                if (!stagingDir.renameTo(targetDir)) {
                    stagingDir.copyRecursively(targetDir, overwrite = true)
                    stagingDir.deleteRecursively()
                }
                tempArchive.delete()
                "Kokoro-82M neural voice installed and ready!"
            }

            tempArchive.delete()
            stagingDir.deleteRecursively()

            result.onSuccess { msg ->
                val installedDir = refreshKokoroModelPresence()
                if (installedDir != null) {
                    ensureKokoroLoaded(installedDir, DEFAULT_KOKORO_SPEAKER_ID)
                }
                _state.update {
                    it.copy(
                        isDownloadingModel = false,
                        downloadProgress = 1f,
                        downloadStatus = msg,
                        lastError = null
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                Log.e(TAG, "Kokoro download failed", err)
                _state.update {
                    it.copy(
                        isDownloadingModel = false,
                        downloadProgress = 0f,
                        downloadStatus = null,
                        lastError = "Kokoro download failed: ${err.localizedMessage}"
                    )
                }
            }
            withContext(Dispatchers.Main) {
                onCompleted(result)
            }
        }
    }

    private suspend fun ensureKokoroLoaded(modelDir: File, speakerId: Int): Boolean = kokoroMutex.withLock {
        val preferredLexicon = resolveLexiconPaths(modelDir, speakerId)
        if (kokoroTts != null && loadedKokoroDir == modelDir.absolutePath && loadedKokoroLexicon == preferredLexicon) {
            return@withLock true
        }

        _state.update { it.copy(isKokoroLoading = true, lastError = null) }
        return@withLock try {
            kokoroTts?.release()
            kokoroTts = null

            val modelFile = File(modelDir, "model.int8.onnx").takeIf { it.exists() }
                ?: File(modelDir, "model.onnx")
            val voicesFile = File(modelDir, "voices.bin")
            val tokensFile = File(modelDir, "tokens.txt")

            val kokoroCfg = OfflineTtsKokoroModelConfig(
                model = modelFile.absolutePath,
                voices = voicesFile.absolutePath,
                tokens = tokensFile.absolutePath,
                dataDir = "",
                lexicon = preferredLexicon,
                lang = "",
                dictDir = "",
                lengthScale = 1.0f
            )
            val modelCfg = OfflineTtsModelConfig(
                kokoro = kokoroCfg,
                numThreads = 4,
                debug = false,
                provider = "cpu"
            )
            val ttsCfg = OfflineTtsConfig(
                model = modelCfg,
                maxNumSentences = 1,
                silenceScale = 0.22f
            )
            val engine = OfflineTts(assetManager = null, config = ttsCfg)
            kokoroTts = engine
            loadedKokoroDir = modelDir.absolutePath
            loadedKokoroLexicon = preferredLexicon

            val preset = presetForSpeakerId(speakerId)
            _state.update {
                it.copy(
                    isKokoroInstalled = true,
                    isKokoroLoaded = true,
                    isKokoroLoading = false,
                    kokoroModelDir = modelDir.absolutePath,
                    activeVoiceLabel = "Kokoro-82M · ${preset.label}",
                    lastError = null
                )
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize Kokoro-82M", t)
            _state.update {
                it.copy(
                    isKokoroLoaded = false,
                    isKokoroLoading = false,
                    lastError = "Kokoro load error: ${t.localizedMessage}"
                )
            }
            false
        }
    }

    private suspend fun speakWithKokoroStreaming(sentences: List<String>, profile: SecretaryProfile) {
        kokoroMutex.withLock {
            val engine = kokoroTts ?: return@withLock
            val sampleRate = engine.sampleRate().takeIf { it > 0 } ?: 24000
            val sid = profile.kokoroVoiceId.coerceIn(0, (engine.numSpeakers() - 1).coerceAtLeast(0))
            val speed = profile.voiceSpeed.coerceIn(0.75f, 1.35f)
            val preset = presetForSpeakerId(sid)

            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(sampleRate * 4 / 5)

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2)
                .build()

            activeTrack = track
            requestAudioFocus()
            _state.update {
                it.copy(
                    isSpeaking = true,
                    activeVoiceLabel = "Kokoro-82M · ${preset.label}"
                )
            }

            try {
                track.play()
                val pauseSamples = FloatArray((sampleRate * 0.16f).toInt()) // 160ms natural breath pause

                for ((index, sentence) in sentences.withIndex()) {
                    if (stopRequested.get()) break
                    val audio = engine.generate(
                        text = sentence,
                        sid = sid,
                        speed = speed
                    )
                    if (stopRequested.get()) break
                    if (audio.samples.isNotEmpty()) {
                        val smoothed = applyEdgeFadeAndSoftLimit(audio.samples)
                        writeFloatAudio(track, smoothed)
                    }
                    if (!stopRequested.get() && index < sentences.lastIndex) {
                        writeFloatAudio(track, pauseSamples)
                    }
                }
                if (!stopRequested.get()) {
                    // Drain final tail smoothly before releasing track
                    writeFloatAudio(track, FloatArray((sampleRate * 0.12f).toInt()))
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    Log.e(TAG, "Kokoro playback error", t)
                }
            } finally {
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop()
                    }
                    track.release()
                } catch (_: Throwable) {
                }
                if (activeTrack === track) activeTrack = null
                releaseAudioFocus()
                _state.update { it.copy(isSpeaking = false) }
            }
        }
    }

    private fun writeFloatAudio(track: AudioTrack, samples: FloatArray) {
        var offset = 0
        while (offset < samples.size && !stopRequested.get()) {
            val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
    }

    private fun applyEdgeFadeAndSoftLimit(samples: FloatArray): FloatArray {
        val out = samples.copyOf()
        val fadeLen = min(200, out.size / 4)
        for (i in out.indices) {
            var v = out[i].coerceIn(-0.98f, 0.98f)
            if (i < fadeLen) {
                v *= i.toFloat() / fadeLen.toFloat()
            } else if (i >= out.size - fadeLen) {
                v *= (out.size - 1 - i).toFloat() / fadeLen.toFloat()
            }
            out[i] = v
        }
        return out
    }

    private fun speakWithGoogleHd(sentences: List<String>, profile: SecretaryProfile) {
        val engine = googleTts
        if (!googleTtsReady || engine == null) return

        applyGoogleVoiceSettings(engine, profile)
        requestAudioFocus()
        _state.update { it.copy(isSpeaking = true) }

        sentences.forEachIndexed { idx, sentence ->
            val mode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val uttId = if (idx == sentences.lastIndex) UTTERANCE_LAST_ID else "sec_chunk_$idx"
            engine.speak(sentence, mode, null, uttId)
            if (idx < sentences.lastIndex) {
                engine.playSilentUtterance(140L, TextToSpeech.QUEUE_ADD, "sec_pause_$idx")
            }
        }
    }

    private fun applyGoogleVoiceSettings(engine: TextToSpeech, profile: SecretaryProfile) {
        engine.setSpeechRate(profile.voiceSpeed.coerceIn(0.75f, 1.35f))
        engine.setPitch(profile.voicePitch.coerceIn(0.85f, 1.15f))

        val voices = engine.voices.orEmpty()
        val selectedVoice: Voice? = if (profile.googleVoiceName.isNotBlank()) {
            voices.firstOrNull { it.name == profile.googleVoiceName }
        } else {
            val topOption = _state.value.availableGoogleVoices.firstOrNull()
            voices.firstOrNull { it.name == topOption?.name }
        }

        if (selectedVoice != null) {
            runCatching { engine.voice = selectedVoice }
            val label = formatGoogleVoiceLabel(selectedVoice)
            _state.update { it.copy(activeVoiceLabel = "Google HD · $label") }
        }
    }

    private fun discoverGoogleEnglishVoices(engine: TextToSpeech): List<GoogleVoiceOption> {
        val voices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptyList())
        val preferredCodes = listOf(
            "en-us-x-tpf", "en-us-x-iob", "en-us-x-sfg", "en-us-x-tpc",
            "en-gb-x-gba", "en-gb-x-gbc", "en-gb-x-rjs",
            "en-in-x-ena", "en-in-x-enc", "en-au-x-afa"
        )
        return voices
            .filter { v ->
                v.locale?.language.equals("en", ignoreCase = true) &&
                    (v.features == null || !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
            }
            .sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenByDescending { v ->
                        val lower = v.name.lowercase(Locale.US)
                        preferredCodes.any { lower.contains(it) }
                    }
                    .thenByDescending { it.locale.country.equals("US", ignoreCase = true) }
                    .thenBy { it.isNetworkConnectionRequired }
            )
            .distinctBy { it.name }
            .take(12)
            .map { v ->
                GoogleVoiceOption(
                    name = v.name,
                    label = formatGoogleVoiceLabel(v),
                    localeTag = v.locale.toLanguageTag(),
                    isHighQuality = v.quality >= Voice.QUALITY_HIGH,
                    requiresNetwork = v.isNetworkConnectionRequired
                )
            }
    }

    private fun formatGoogleVoiceLabel(voice: Voice): String {
        val region = when (voice.locale.country.uppercase(Locale.US)) {
            "US" -> "US"
            "GB" -> "UK"
            "IN" -> "IN"
            "AU" -> "AU"
            else -> voice.locale.country.ifBlank { "EN" }
        }
        val shortCode = voice.name.substringAfter("-x-", voice.name.takeLast(8))
            .replace("-local", "")
            .replace("-network", " HD")
            .uppercase(Locale.US)
        val tier = if (voice.quality >= Voice.QUALITY_VERY_HIGH) "Neural" else "Std"
        return "$region · $shortCode ($tier)"
    }

    private fun locateKokoroModelDir(): File? {
        val candidates = buildList {
            add(File(appContext.filesDir, KOKORO_DIR_NAME))
            add(File(appContext.filesDir, KOKORO_EN_DIR_NAME))
            appContext.getExternalFilesDir(null)?.let { ext ->
                add(File(ext, KOKORO_DIR_NAME))
                add(File(ext, KOKORO_EN_DIR_NAME))
            }
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dl != null) {
                add(File(dl, KOKORO_DIR_NAME))
                add(File(dl, KOKORO_EN_DIR_NAME))
            }
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (docs != null) {
                add(File(docs, KOKORO_DIR_NAME))
                add(File(docs, KOKORO_EN_DIR_NAME))
            }
        }
        return candidates.firstOrNull { isValidKokoroDir(it) }
    }

    private fun isValidKokoroDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val hasModel = File(dir, "model.int8.onnx").exists() || File(dir, "model.onnx").exists()
        val hasVoices = File(dir, "voices.bin").exists()
        val hasTokens = File(dir, "tokens.txt").exists()
        val hasLexicon = File(dir, "lexicon-us-en.txt").exists() ||
            File(dir, "lexicon-gb-en.txt").exists() ||
            File(dir, "lexicon.txt").exists()
        return hasModel && hasVoices && hasTokens && hasLexicon
    }

    private fun resolveLexiconPaths(modelDir: File, speakerId: Int): String {
        val usLex = File(modelDir, "lexicon-us-en.txt").takeIf { it.exists() }
        val gbLex = File(modelDir, "lexicon-gb-en.txt").takeIf { it.exists() }
        val genericLex = File(modelDir, "lexicon.txt").takeIf { it.exists() }
        val isBritish = speakerId in 20..27
        val ordered = if (isBritish) {
            listOfNotNull(gbLex, usLex, genericLex)
        } else {
            listOfNotNull(usLex, gbLex, genericLex)
        }
        return ordered.joinToString(",") { it.absolutePath }
    }

    private fun computeVoiceLabel(
        isKokoroInstalled: Boolean,
        isKokoroLoaded: Boolean,
        googleBest: String?
    ): String = when {
        isKokoroLoaded -> "Kokoro-82M Neural (Ready)"
        isKokoroInstalled -> "Kokoro-82M Installed (Offline Neural)"
        !googleBest.isNullOrBlank() -> "Google HD · $googleBest"
        else -> "Google Speech Services"
    }

    private fun requestAudioFocus() {
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .build()
            activeFocusRequest = req
            audioManager.requestAudioFocus(req)
        }
    }

    private fun releaseAudioFocus() {
        runCatching {
            activeFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            activeFocusRequest = null
        }
    }

    fun release() {
        stop()
        downloadJob?.cancel()
        runCatching { kokoroTts?.release() }
        kokoroTts = null
        runCatching { googleTts?.shutdown() }
        googleTts = null
    }

    companion object {
        private const val TAG = "ClaraVoiceEngine"
        private const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
        private const val UTTERANCE_LAST_ID = "clara_utt_last"

        const val MODE_AUTO = "AUTO"
        const val MODE_KOKORO = "KOKORO"
        const val MODE_GOOGLE_HD = "GOOGLE_HD"

        const val DEFAULT_KOKORO_SPEAKER_ID = 3
        const val KOKORO_DIR_NAME = "kokoro-int8-multi-lang-v1_0"
        const val KOKORO_EN_DIR_NAME = "kokoro-int8-en-v0_19"
        const val KOKORO_MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"

        val KOKORO_VOICES = listOf(
            KokoroVoicePreset(3, "af_heart", "Heart (US)", "US Female", "Warm, flagship human executive voice"),
            KokoroVoicePreset(2, "af_bella", "Bella (US)", "US Female", "Clear, articulate & warm"),
            KokoroVoicePreset(9, "af_sarah", "Sarah (US)", "US Female", "Calm, composed briefing delivery"),
            KokoroVoicePreset(7, "af_nova", "Nova (US)", "US Female", "Modern, bright & crisp"),
            KokoroVoicePreset(6, "af_nicole", "Nicole (US)", "US Female", "Soft, relaxed conversational tone"),
            KokoroVoicePreset(10, "af_sky", "Sky (US)", "US Female", "Light, approachable assistant voice"),
            KokoroVoicePreset(21, "bf_emma", "Emma (UK)", "UK Female", "Polished British executive presence"),
            KokoroVoicePreset(22, "bf_isabella", "Isabella (UK)", "UK Female", "Refined, warm British tone"),
            KokoroVoicePreset(23, "bf_lily", "Lily (UK)", "UK Female", "Friendly British studio voice"),
            KokoroVoicePreset(31, "hf_alpha", "Alpha (South Asian)", "South Asian", "Natural South Asian English cadence")
        )

        fun presetForSpeakerId(sid: Int): KokoroVoicePreset =
            KOKORO_VOICES.firstOrNull { it.speakerId == sid } ?: KOKORO_VOICES.first()
    }
}
