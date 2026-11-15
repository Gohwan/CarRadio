package com.example.carradio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import java.util.Locale

/** One foreground utterance. The SDK owns the microphone; this app stores no raw audio. */
internal class PleosRecognitionController(
    context: Context,
    private val onState: (RecognitionUiState) -> Unit,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var sdk: SpeechToText? = null
    private var listener: ResultListener? = null
    private var draft = RecognitionDraft()
    private var state = RecognitionUiState()
    private var generation = 0
    private var signal = RecognitionSignal()
    private var lastSignalUpdate = 0L

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, PERMISSION) ==
        PackageManager.PERMISSION_GRANTED

    fun permissionPending() {
        releaseSession()
        draft = RecognitionDraft()
        publish(RecognitionUiState(RecognitionPhase.PERMISSION, status = "음성 입력 권한 확인 중"))
    }

    fun permissionDenied() {
        fail("음성 입력 서비스 권한이 필요합니다.")
    }

    fun begin() {
        releaseSession()
        draft = RecognitionDraft()
        signal = RecognitionSignal()
        lastSignalUpdate = 0L
        if (!hasPermission()) {
            permissionDenied()
            return
        }
        val token = generation
        publish(RecognitionUiState(RecognitionPhase.CONNECTING, status = "마이크 준비 중"))
        try {
            val service = SpeechToText(context, Mode.ON_DEVICE)
            sdk = service
            val results = object : ResultListener {
                override fun onReady() {
                    post(token) {
                        Log.i(TAG, "SDK ready")
                        if (state.phase == RecognitionPhase.STARTING) {
                            publish(state.copy(status = "SDK 준비 완료 · 인식 시작 응답 대기"))
                        }
                    }
                }
                override fun onStartedRecognition() {
                    post(token) {
                        Log.i(TAG, "SDK recognition started")
                        if (state.canFinish) publish(state.copy(
                            phase = RecognitionPhase.LISTENING,
                            sdkStarted = true,
                            status = "듣는 중 · 최대 30초",
                        ))
                    }
                }
                override fun onUpdated(stt: String, completed: Boolean) {
                    post(token) {
                        if (!state.canFinish && state.phase != RecognitionPhase.FINISHING) return@post
                        Log.i(TAG, "SDK text callback: completed=$completed, length=${stt.length}")
                        draft.accept(stt, completed)
                        publish(state.copy(
                            phase = if (state.phase == RecognitionPhase.STARTING)
                                RecognitionPhase.LISTENING else state.phase,
                            text = draft.text,
                            textCallbacks = state.textCallbacks + 1,
                        ))
                        if (draft.completed) review()
                    }
                }
                override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                override fun onUpdatedRms(rms: Double) {
                    post(token) { updateSignal(rms) }
                }
                override fun onEndedRecognition() {
                    post(token) {
                        Log.i(TAG, "SDK recognition ended")
                        waitForFinalResult()
                    }
                }
                override fun onError(errorType: ErrorType) {
                    post(token) { fail(errorType) }
                }
            }
            listener = results
            service.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    post(token) {
                        try {
                            service.addListener(results)
                            service.setLocale(Locale.KOREA)
                            publish(state.copy(phase = RecognitionPhase.STARTING, status = "인식 시작 응답 대기"))
                            Log.i(TAG, "SDK microphone request")
                            service.request()
                            main.postDelayed({
                                if (token == generation && state.canFinish) finish()
                            }, 30000)
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
                if (token == generation && state.phase == RecognitionPhase.CONNECTING) {
                    fail("음성 입력 서비스 연결 시간 초과")
                }
            }, 15000)
        } catch (exception: Exception) {
            failException(exception)
        }
    }

    fun finish() {
        if (!state.canFinish) return
        waitForFinalResult()
        try {
            sdk?.stop()
        } catch (exception: Exception) {
            failException(exception)
        }
    }

    private fun waitForFinalResult() {
        if (!state.active || state.phase == RecognitionPhase.FINISHING) return
        val token = generation
        publish(state.copy(phase = RecognitionPhase.FINISHING, status = "인식 결과 정리 중"))
        main.postDelayed({ if (token == generation) review() }, 1500)
    }

    private fun review() {
        releaseSession()
        val status = when {
            draft.text.isBlank() && !state.sdkStarted && state.textCallbacks == 0 ->
                "인식 시작 또는 결과 응답을 받지 못했습니다. 다시 시도하거나 직접 입력해 주세요."
            draft.text.isBlank() -> "인식된 문장이 없습니다. 다시 말하거나 직접 입력해 주세요."
            draft.completed -> "인식 완료 · 내용을 확인해 주세요."
            else -> "중간 인식 결과 · 내용을 확인하고 수정해 주세요."
        }
        publish(state.copy(phase = RecognitionPhase.REVIEW, text = draft.text, status = status, signal = signal))
    }

    fun editText(text: String) {
        if (!state.visible || state.active) return
        draft.edit(text)
        publish(state.copy(text = draft.text))
    }

    fun confirmText(): String? {
        if (!state.canSave) return null
        val text = draft.forSave() ?: return null
        cancel()
        return text
    }

    fun cancel() {
        releaseSession()
        draft = RecognitionDraft()
        publish(RecognitionUiState())
    }

    private fun fail(error: ErrorType) {
        Log.w(TAG, "Recognition error: ${error.name}")
        fail(when (error) {
            ErrorType.PERMISSION_DENIED -> "음성 입력 서비스 권한 확인 필요"
            ErrorType.MIC_ERROR -> "마이크 입력 오류 · 에뮬레이터 마이크 설정 확인"
            ErrorType.ON_DEVICE_ENGINE_ERROR -> "기기 내 음성 인식 엔진 오류"
            ErrorType.NOT_SUPPORTED_API -> "이 환경에서 음성 입력을 지원하지 않습니다."
            ErrorType.UNAVAILABLE_LANGUAGE -> "한국어 음성 인식을 사용할 수 없습니다."
            ErrorType.TIMEOUT -> "음성 인식 응답 시간 초과"
            else -> "음성 인식 오류 · 다시 시도해 주세요."
        }, error.name)
    }

    private fun fail(message: String, errorCode: String? = null) {
        releaseSession()
        publish(state.copy(
            phase = RecognitionPhase.ERROR,
            text = draft.text,
            status = message + if (draft.text.isBlank()) "" else " · 중간 결과를 확인해 주세요.",
            signal = signal,
            errorCode = errorCode,
        ))
    }

    private fun failException(exception: Exception) {
        Log.w(TAG, "Recognition request failed: ${exception.javaClass.simpleName}")
        fail(if (exception is SecurityException) "음성 입력 서비스 권한 확인 필요"
            else "음성 입력 서비스 연결 실패")
    }

    private fun updateSignal(rms: Double) {
        if (!state.canFinish && state.phase != RecognitionPhase.FINISHING) return
        signal = signal.record(rms)
        val now = SystemClock.elapsedRealtime()
        if (signal.callbacks == 1 || now - lastSignalUpdate >= 750) {
            lastSignalUpdate = now
            Log.i(TAG, "SDK input RMS callback: count=${signal.callbacks}, latest=${signal.latest}")
            publish(state.copy(signal = signal))
        }
    }

    private fun post(token: Int, action: () -> Unit) {
        main.post { if (token == generation) action() }
    }

    private fun publish(value: RecognitionUiState) {
        state = value
        onState(value)
    }

    private fun releaseSession() {
        generation += 1
        val service = sdk
        val results = listener
        sdk = null
        listener = null
        if (service != null) {
            runCatching { service.stop() }
            if (results != null) runCatching { service.removeListener(results) }
            runCatching { service.release() }
                .onFailure { Log.w(TAG, "Recognition cleanup failed: ${it.javaClass.simpleName}") }
        }
    }

    companion object {
        const val PERMISSION = "pleos.car.permission.STT_SERVICE"
        private const val TAG = "CarRadioRecognition"
    }
}
