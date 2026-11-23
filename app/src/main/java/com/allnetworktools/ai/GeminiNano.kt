package com.allnetworktools.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where Gemini Nano stands on this phone. */
sealed interface AiState {
    /** Not asked yet: nothing about the AI shows until the answer is known. */
    data object Checking : AiState

    /** No AICore or no model for this phone: the AI shows nowhere. */
    data object Unavailable : AiState

    /** The phone can run it once the model is downloaded (done the first time it is asked for). */
    data object Downloadable : AiState
    data class Downloading(val bytes: Long) : AiState
    data object Ready : AiState
}

/**
 * Gemini Nano, run on the phone by AICore through ML Kit's Prompt API. Nothing leaves the phone. Only asked whether it
 * exists at start; the model is downloaded and run only when the user taps a Gemini button.
 */
interface AiEngine {
    val state: StateFlow<AiState>
    fun check()

    /** The answer to [prompt], piece by piece; downloads the model first when it is not on the phone yet. */
    fun answer(prompt: String): Flow<String>
}

class GeminiNano(private val scope: CoroutineScope) : AiEngine {
    private val _state = MutableStateFlow<AiState>(AiState.Checking)
    override val state: StateFlow<AiState> = _state.asStateFlow()
    private var model: GenerativeModel? = null
    private val lock = Mutex()

    private fun client(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    override fun check() {
        scope.launch(Dispatchers.Default) {
            _state.value = runCatching { toState(client().checkStatus()) }.getOrElse {
                com.allnetworktools.util.DebugLog.i("AI", "Gemini Nano unavailable: ${it.message}")
                AiState.Unavailable
            }
            com.allnetworktools.util.DebugLog.i("AI", "Gemini Nano: ${_state.value}")
        }
    }

    private fun toState(status: Int): AiState = when (status) {
        FeatureStatus.AVAILABLE -> AiState.Ready
        FeatureStatus.DOWNLOADABLE -> AiState.Downloadable
        FeatureStatus.DOWNLOADING -> AiState.Downloading(0)
        else -> AiState.Unavailable
    }

    private suspend fun ensureModel() {
        val m = client()
        if (toState(m.checkStatus()) == AiState.Ready) { _state.value = AiState.Ready; return }
        var failure: Throwable? = null
        m.download().collect { s ->
            when (s) {
                is DownloadStatus.DownloadStarted -> _state.value = AiState.Downloading(0)
                is DownloadStatus.DownloadProgress -> _state.value = AiState.Downloading(s.totalBytesDownloaded)
                DownloadStatus.DownloadCompleted -> _state.value = AiState.Ready
                is DownloadStatus.DownloadFailed -> failure = s.e
            }
        }
        failure?.let { _state.value = AiState.Downloadable; throw it }
        _state.value = AiState.Ready
    }

    override fun answer(prompt: String): Flow<String> = flow {
        // One answer at a time: AICore allows little in parallel and the phone has one model in memory.
        lock.withLock {
            ensureModel()
            val request = generateContentRequest(TextPart(prompt)) {
                temperature = 0.2f
                topK = 16
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }
            client().generateContentStream(request).collect { chunk ->
                chunk.candidates.firstOrNull()?.text?.let { if (it.isNotEmpty()) emit(it) }
            }
        }
    }

    companion object {
        const val MAX_OUTPUT_TOKENS = 400
    }
}
