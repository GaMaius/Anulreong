package io.infotive.anulleong.sdk

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.infotive.anulleong.care.Feeling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

enum class ListenStatus { UNAVAILABLE, IDLE, LISTENING, ERROR }

data class ListenState(val status: ListenStatus = ListenStatus.UNAVAILABLE, val partial: String? = null, val lastError: String? = null)

/**
 * "말로 알리기": the child or a parent says how they feel, the Pleos SpeechToText service turns it
 * into text and a short keyword match turns that into a feeling. Recognition was slow and
 * inaccurate on the emulator, so the buttons always stay available and a timeout ends listening.
 */
class VoiceInput(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var stt: SpeechToText? = null
    private var onResult: ((Feeling?, String) -> Unit)? = null
    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state

    private val timeout = Runnable { finish(null) }

    private val listener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            _state.update { it.copy(partial = text) }
            if (isFinal) main.post { finish(text) }
        }
        override fun onUpdatedEpdData(start: Long, end: Long) {}
        override fun onStartedRecognition() {}
        override fun onEndedRecognition() {}
        override fun onError(errorType: ErrorType) {
            _state.update { it.copy(status = ListenStatus.ERROR, lastError = errorType.name) }
            main.post { finish(null) }
        }
        override fun onReady() {}
        override fun onUpdatedRms(rms: Double) {}
    }

    fun start() {
        try {
            stt = SpeechToText(app, Mode.ON_DEVICE).also { sdk ->
                sdk.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        runCatching { sdk.addListener(listener); sdk.setLocale(Locale.KOREA) }
                        _state.update { it.copy(status = ListenStatus.IDLE) }
                    }
                    override fun onFailure(errorType: ErrorType) {
                        _state.update { it.copy(status = ListenStatus.UNAVAILABLE, lastError = errorType.name) }
                    }
                })
            }
        } catch (e: Throwable) {
            Log.w("AnulListen", "stt unavailable", e)
            _state.update { it.copy(status = ListenStatus.UNAVAILABLE, lastError = e.message ?: e.javaClass.simpleName) }
        }
    }

    val ready get() = _state.value.status == ListenStatus.IDLE || _state.value.status == ListenStatus.ERROR

    /** Starts listening for up to 8 s; [cb] gets the matched feeling (or null) and the heard text. */
    fun listen(cb: (Feeling?, String) -> Unit): Boolean {
        val sdk = stt ?: return false
        if (!ready) return false
        onResult = cb
        _state.update { it.copy(status = ListenStatus.LISTENING, partial = null, lastError = null) }
        return runCatching { sdk.request() }.onFailure { finish(null) }.isSuccess.also {
            main.removeCallbacks(timeout); main.postDelayed(timeout, 8_000)
        }
    }

    fun cancel() = finish(null)

    private fun finish(text: String?) {
        main.removeCallbacks(timeout)
        val cb = onResult ?: return
        onResult = null
        runCatching { stt?.stop() }
        val heard = text ?: _state.value.partial.orEmpty()
        _state.update { if (it.status == ListenStatus.LISTENING) it.copy(status = ListenStatus.IDLE) else it }
        cb(match(heard), heard)
    }

    fun stop() {
        main.removeCallbacks(timeout)
        runCatching { stt?.removeListener(listener); stt?.release() }
        stt = null
    }

    companion object {
        /** Keyword match, most specific first. Exposed for tests. */
        fun match(text: String): Feeling? {
            val t = text.replace(" ", "")
            return when {
                listOf("멀미안나", "멀미없", "안울렁", "괜찮아요멀미").any { it in t } -> Feeling.OK
                "안괜찮" in t -> Feeling.UNEASY
                listOf("멀미", "토할", "토나", "속이안좋", "속안좋", "울렁", "어지러", "메스꺼", "속이이상").any { it in t } -> Feeling.SICK
                listOf("불편", "답답", "더워", "조금이상", "별로").any { it in t } -> Feeling.UNEASY
                listOf("괜찮", "좋아", "안아파", "멀쩡").any { it in t } -> Feeling.OK
                else -> null
            }
        }
    }
}
