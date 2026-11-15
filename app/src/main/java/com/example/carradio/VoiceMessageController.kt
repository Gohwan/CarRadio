package com.example.carradio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

internal data class VoiceDraft(
    val file: File, val durationMs: Int, val requestId: String,
    val channel: String, val rangeKm: Int,
)
internal data class VoiceUiState(
    val visible: Boolean = false, val recording: Boolean = false,
    val elapsedSeconds: Int = 0, val draft: VoiceDraft? = null,
    val playingId: String? = null, val status: String = "최대 15초 · 눌러서 녹음",
) {
    val usingAudio: Boolean get() = recording || playingId != null
}

/** Debug local-test voice notes. No microphone is opened until the user presses record. */
internal class VoiceMessageController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onState: (VoiceUiState) -> Unit,
) {
    private var state = VoiceUiState()
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var startedAt = 0L
    private var timer: Job? = null
    private var player: MediaPlayer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { change ->
            if (change < 0) {
                if (state.recording) cancel() else stopPlayback()
            }
        }.build()
    private var selectedChannel = "일반 대화"
    private var selectedRange = 1

    init {
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("outgoing-voice-") && it.extension == "m4a"
        }?.forEach { it.delete() }
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun open(channel: String, rangeKm: Int) {
        cancel()
        selectedChannel = channel
        selectedRange = rangeKm
        publish(VoiceUiState(visible = true))
    }

    fun permissionDenied() {
        publish(state.copy(status = "마이크 권한이 없어 녹음하지 않았습니다."))
    }

    @Suppress("DEPRECATION")
    fun begin() {
        if (!BuildConfig.LOCAL_DEMO || !state.visible || state.recording || state.draft != null) return
        if (!hasPermission()) { permissionDenied(); return }
        stopPlayback()
        val file = File.createTempFile("outgoing-voice-", ".m4a", context.cacheDir)
        try {
            publish(state.copy(recording = true, elapsedSeconds = 0, status = "녹음 중 · 15초에 자동 종료"))
            val next = MediaRecorder()
            recorder = next
            recordingFile = file
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioChannels(1)
            next.setAudioSamplingRate(VoiceMessagePolicy.RECORD_SAMPLE_RATE_HZ)
            next.setAudioEncodingBitRate(VoiceMessagePolicy.RECORD_BIT_RATE)
            next.setMaxDuration(VoiceMessagePolicy.MAX_DURATION_MS)
            next.setMaxFileSize(VoiceMessagePolicy.MAX_BYTES.toLong())
            next.setOutputFile(file.absolutePath)
            next.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) finish()
            }
            next.setOnErrorListener { _, _, _ ->
                cancel()
                publish(state.copy(visible = true, status = "녹음 오류 · 마이크 지원 여부를 확인해 주세요."))
            }
            next.prepare()
            next.start()
            startedAt = SystemClock.elapsedRealtime()
            timer = scope.launch {
                while (state.recording) {
                    delay(200)
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    publish(state.copy(elapsedSeconds = (elapsed / 1000).toInt().coerceAtMost(15)))
                    if (elapsed >= VoiceMessagePolicy.MAX_DURATION_MS) finish()
                }
            }
        } catch (_: Exception) {
            releaseRecorder()
            file.delete()
            publish(state.copy(recording = false, status = "녹음 시작 실패 · 이 기기의 마이크 지원은 확인이 필요합니다."))
        }
    }

    fun finish() {
        if (!state.recording) return
        timer?.cancel()
        val file = recordingFile
        val duration = (SystemClock.elapsedRealtime() - startedAt).toInt().coerceAtMost(15000)
        var stopped = false
        try { recorder?.stop(); stopped = true } catch (_: Exception) { /* Too-short recordings are discarded. */ }
        releaseRecorder()
        val valid = stopped && file != null && VoiceMessagePolicy.valid(duration, file.length().toInt())
        if (valid) {
            logRecordingFormat(file!!)
            publish(state.copy(recording = false, draft = VoiceDraft(file!!, duration,
                UUID.randomUUID().toString(), selectedChannel, selectedRange), status = "녹음 완료 · 전송 전 들어볼 수 있습니다."))
        } else {
            file?.delete()
            publish(state.copy(recording = false, status = "녹음이 너무 짧거나 파일이 올바르지 않습니다. 다시 녹음해 주세요."))
        }
    }

    fun play(id: String, file: File?) {
        if (state.recording) return
        stopPlayback()
        if (file == null || !file.isFile) {
            publish(state.copy(status = "음성 파일을 받는 중이거나 이미 만료되었습니다."))
            return
        }
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            publish(state.copy(status = "다른 음성 기능 사용 중 · 잠시 후 다시 재생해 주세요."))
            return
        }
        try {
            publish(state.copy(playingId = id, status = "음성 준비 중"))
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            next.setDataSource(file.absolutePath)
            next.setOnPreparedListener {
                if (player === it) { it.start(); publish(state.copy(status = "음성 재생 중")) }
            }
            next.setOnCompletionListener { stopPlayback() }
            next.setOnErrorListener { _, _, _ ->
                stopPlayback()
                publish(state.copy(status = "이 기기에서 음성을 재생할 수 없습니다."))
                true
            }
            next.prepareAsync()
        } catch (_: Exception) {
            stopPlayback()
            publish(state.copy(status = "음성 파일 재생 실패"))
        }
    }

    fun stopPlayback() {
        player?.release()
        player = null
        audioManager.abandonAudioFocusRequest(focus)
        if (state.playingId != null) publish(state.copy(playingId = null, status = "재생 종료"))
    }

    fun cancel() {
        timer?.cancel()
        releaseRecorder()
        recordingFile?.delete()
        recordingFile = null
        state.draft?.file?.delete()
        stopPlayback()
        publish(VoiceUiState())
    }

    private fun releaseRecorder() {
        timer?.cancel()
        runCatching { recorder?.release() }
        recorder = null
    }

    /** Metadata only: never logs audio, filenames, message contents or vehicle identities. */
    private fun logRecordingFormat(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else -1
                val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else -1
                val bitRate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else -1
                Log.i("CarRadioVoice", "Recorded format: rate=$rate channels=$channels bitRate=$bitRate bytes=${file.length()}")
                break
            }
        } catch (_: Exception) {
            Log.w("CarRadioVoice", "Recording format metadata unavailable")
        } finally { extractor.release() }
    }

    private fun publish(value: VoiceUiState) { state = value; onState(value) }
}

