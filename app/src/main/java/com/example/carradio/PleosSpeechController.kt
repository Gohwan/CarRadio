package com.example.carradio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.ArrayDeque
import java.util.Locale

internal data class SpeechUiState(
    val enabled: Boolean = false,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val status: String = "음성 읽기 꺼짐",
)

/** Foreground-only Korean speech, using the official on-device engine. */
internal class PleosSpeechController(
    context: Context,
    private val onState: (SpeechUiState) -> Unit,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val tracker = InboxAnnouncementTracker()
    private val queue = ArrayDeque<String>()
    private var sdk: TextToSpeech? = null
    private var listener: EventListener? = null
    private var state = SpeechUiState()
    private var generation = 0
    private var utterance = 0
    private var speaking = false
    private var wasConnected = false
    private var inputActive = false

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, PERMISSION) ==
        PackageManager.PERMISSION_GRANTED

    fun permissionPending() {
        publish(SpeechUiState(enabled = true, busy = true, status = "음성 서비스 권한 확인 중"))
    }

    fun permissionDenied() {
        releaseSession()
        publish(SpeechUiState(status = "음성 서비스 권한이 필요합니다."))
    }

    fun start() {
        releaseSession()
        if (inputActive) {
            publish(SpeechUiState(enabled = true, status = "음성 입력 중 · 읽기 대기"))
            return
        }
        if (!hasPermission()) {
            permissionDenied()
            return
        }
        val token = generation
        publish(SpeechUiState(enabled = true, busy = true, status = "음성 서비스 연결 중"))
        try {
            val service = TextToSpeech(context, Mode.ON_DEVICE)
            sdk = service
            val events = object : EventListener {
                override fun onReady() = Unit
                override fun onStart() {
                    post(token) { publish(state.copy(busy = true, status = "메시지 읽는 중")) }
                }
                override fun onUpdatedRms(rms: Double) = Unit
                override fun onDone() {
                    post(token) { finished() }
                }
                override fun onStop() {
                    post(token) { finished() }
                }
                override fun onError(errorType: ErrorType) {
                    post(token) { fail(errorType) }
                }
            }
            listener = events
            service.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    post(token) {
                        try {
                            service.addEventListener(events)
                            service.setLocale(Locale.KOREA)
                            publish(SpeechUiState(enabled = true, ready = true, status = "새 메시지 읽기 대기"))
                            playNext()
                        } catch (exception: Exception) {
                            failException(exception)
                        }
                    }
                }
                override fun onFailure(errorType: ErrorType) {
                    post(token) { fail(errorType) }
                }
            })
            main.postDelayed({
                if (token == generation && !state.ready) fail(ErrorType.TIMEOUT)
            }, 15000)
        } catch (exception: Exception) {
            failException(exception)
        }
    }

    fun setInputActive(active: Boolean, resume: Boolean = true) {
        if (inputActive == active) return
        inputActive = active
        if (active) {
            val enabled = state.enabled
            releaseSession()
            publish(SpeechUiState(enabled = enabled,
                status = if (enabled) "음성 입력 중 · 읽기 대기" else "음성 읽기 꺼짐"))
        } else if (state.enabled && resume) {
            start()
        }
    }

    fun disable() {
        releaseSession()
        publish(SpeechUiState())
    }

    fun pause() {
        val enabled = state.enabled
        releaseSession()
        publish(SpeechUiState(enabled = enabled,
            status = if (enabled) "음성 읽기 일시 중지" else "음성 읽기 꺼짐"))
    }

    fun observeInbox(relay: LocalRelayState) {
        val fresh = tracker.updates(relay.connected, relay.messages)
        // A channel, range or receive-setting change must stop the previous reading.
        if (wasConnected && !relay.connected) {
            queue.clear()
            if (state.enabled && (speaking || state.busy)) start()
        }
        wasConnected = relay.connected
        if (!state.enabled) return
        fresh.filter { it.kind != "voice" }.forEach { enqueue("${it.sender}. ${it.text}") }
    }

    fun test() {
        if (state.ready && !state.busy) enqueue("카 라디오 음성 안내 테스트입니다.")
    }

    private fun enqueue(text: String) {
        if (!state.enabled || sdk == null || text.isBlank()) return
        if (queue.size >= 5) queue.removeFirst()
        queue.addLast(text.take(400))
        playNext()
    }

    private fun playNext() {
        if (!state.ready || speaking || queue.isEmpty()) return
        val service = sdk ?: return
        val text = queue.removeFirst()
        speaking = true
        val token = generation
        val request = ++utterance
        publish(state.copy(busy = true, status = "음성 재생 요청 중"))
        try {
            service.speak(text)
            main.postDelayed({
                if (generation == token && utterance == request && speaking) fail(ErrorType.TIMEOUT)
            }, 60000)
        } catch (exception: Exception) {
            failException(exception)
        }
    }

    private fun finished() {
        if (!speaking) return
        speaking = false
        publish(state.copy(busy = false, status = "새 메시지 읽기 대기"))
        playNext()
    }

    private fun fail(error: ErrorType) {
        Log.w(TAG, "Speech service error: ${error.name}")
        releaseSession()
        val description = when (error) {
            ErrorType.PERMISSION_DENIED -> "음성 서비스 권한 확인 필요"
            ErrorType.NOT_SUPPORTED_API -> "이 환경에서 음성 기능을 지원하지 않습니다."
            ErrorType.UNAVAILABLE_LANGUAGE -> "한국어 음성 엔진을 사용할 수 없습니다."
            ErrorType.AUDIO_ERROR -> "음성 출력 오류 · 출력 장치 확인"
            ErrorType.ON_DEVICE_ENGINE_ERROR -> "기기 내 음성 엔진 오류"
            ErrorType.TIMEOUT -> "음성 서비스 응답 시간 초과"
            else -> "음성 서비스 오류 · 다시 연결해 주세요."
        }
        publish(SpeechUiState(enabled = true, status = description))
    }

    private fun failException(exception: Exception) {
        Log.w(TAG, "Speech request failed: ${exception.javaClass.simpleName}")
        fail(if (exception is SecurityException) ErrorType.PERMISSION_DENIED else ErrorType.UNKNOWN)
    }

    private fun post(token: Int, action: () -> Unit) {
        main.post { if (token == generation) action() }
    }

    private fun publish(value: SpeechUiState) {
        state = value
        onState(value)
    }

    private fun releaseSession() {
        generation += 1
        val service = sdk
        val events = listener
        sdk = null
        listener = null
        queue.clear()
        speaking = false
        if (service != null) {
            runCatching { service.stop() }
            if (events != null) runCatching { service.removeEventListener(events) }
            runCatching { service.release() }
                .onFailure { Log.w(TAG, "Speech cleanup failed: ${it.javaClass.simpleName}") }
        }
    }

    companion object {
        const val PERMISSION = "pleos.car.permission.TTS_SERVICE"
        private const val TAG = "CarRadioSpeech"
    }
}
