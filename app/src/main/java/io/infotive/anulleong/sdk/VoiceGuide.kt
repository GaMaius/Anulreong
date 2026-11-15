package io.infotive.anulleong.sdk

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

enum class VoiceStatus { STARTING, READY, SPEAKING, ERROR, UNAVAILABLE }

data class VoiceState(val status: VoiceStatus = VoiceStatus.STARTING, val lastError: String? = null, val lastDone: Boolean = false)

/**
 * Pleos on-device TextToSpeech. Every spoken cue is also shown on screen, so if the
 * engine fails (seen on the emulator) the family still gets the same guidance visually.
 */
class VoiceGuide(context: Context) {
    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state

    private val events = object : EventListener {
        override fun onReady() {}
        override fun onStart() { _state.update { it.copy(status = VoiceStatus.SPEAKING, lastDone = false) } }
        override fun onDone() { _state.update { it.copy(status = VoiceStatus.READY, lastDone = true) } }
        override fun onStop() { _state.update { it.copy(status = VoiceStatus.READY) } }
        override fun onError(errorType: ErrorType) { _state.update { it.copy(status = VoiceStatus.ERROR, lastError = errorType.name) } }
        override fun onUpdatedRms(rms: Double) {}
    }

    fun start() {
        try {
            tts = TextToSpeech(app, Mode.ON_DEVICE).also { sdk ->
                sdk.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        runCatching { sdk.addEventListener(events); sdk.setLocale(Locale.KOREA) }
                        _state.update { it.copy(status = VoiceStatus.READY, lastError = null) }
                    }
                    override fun onFailure(errorType: ErrorType) {
                        _state.update { it.copy(status = VoiceStatus.ERROR, lastError = errorType.name) }
                    }
                })
            }
        } catch (e: Throwable) {
            Log.w("AnulVoice", "tts unavailable", e)
            _state.update { it.copy(status = VoiceStatus.UNAVAILABLE, lastError = e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Returns false if the cue could not be handed to the engine (screen-only fallback). */
    fun speak(text: String): Boolean {
        val sdk = tts ?: return false
        if (_state.value.status == VoiceStatus.UNAVAILABLE || _state.value.status == VoiceStatus.STARTING) return false
        return runCatching { sdk.speak(text) }.isSuccess
    }

    fun stop() {
        runCatching { tts?.stop(); tts?.removeEventListener(events); tts?.release() }
        tts = null
    }
}
