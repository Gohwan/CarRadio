package com.example.carradio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.sin

internal data class WalkieUiState(
    val available: Boolean = false,
    val supported: Boolean = false,
    val joined: Boolean = false,
    val joining: Boolean = false,
    val requesting: Boolean = false,
    val transmitting: Boolean = false,
    val microphoneReady: Boolean = false,
    val speaker: String? = null,
    val speakerId: String? = null,
    val muteSupported: Boolean = false,
    val mutedCount: Int = 0,
    val moderationBusy: Boolean = false,
    val cooldownSeconds: Int = 0,
    val notice: String? = null,
    val participants: Int = 0,
    val elapsedSeconds: Int = 0,
    val inputLevel: Float = 0f,
    val quietInput: Boolean = false,
    val testTone: Boolean = false,
    val status: String = "참여하면 같은 채널·반경 안의 목소리를 바로 들을 수 있습니다.",
) {
    val usingAudio: Boolean get() = joined || joining
}

/** Foreground-only local-test streaming. Joining is explicit, mic opens only while sending. */
internal class WalkieTalkieController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val request: suspend (String, JSONObject) -> JSONObject,
    private val onState: (WalkieUiState) -> Unit,
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val audioLock = Any()
    private var state = WalkieUiState()
    @Volatile private var generation = 0
    private var memberToken: String? = null
    private var activeTalkId: String? = null
    private var pollJob: Job? = null
    private var captureJob: Job? = null
    @Volatile private var pressed = false
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var playbackId: String? = null
    private var playbackSequence = -1
    private var writtenSamples = 0L
    private var focusGeneration = -1
    private val mutedSpeakers = ConcurrentHashMap.newKeySet<String>()
    private var localCooldownUntil = 0L
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change < 0) scope.launch { leave("다른 오디오가 우선되어 워키토키를 종료했습니다.") }
        }, Handler(Looper.getMainLooper())).build()

    fun hasMicrophonePermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun refreshMicrophonePermission() = publish(state.copy(microphoneReady = hasMicrophonePermission()))

    fun updateRelay(connected: Boolean, supported: Boolean) {
        if (!connected && (state.joined || state.joining)) leave("연결이 종료되어 워키토키를 나갔습니다.")
        publish(state.copy(available = connected && BuildConfig.LOCAL_DEMO, supported = supported))
    }

    fun join() {
        if (!state.available || !state.supported || state.joined || state.joining) return
        val epoch = ++generation
        publish(state.copy(joining = true, status = "워키토키 참여 중"))
        pollJob = scope.launch {
            var joinedToken: String? = null
            try {
                // Even a late HTTP response must be paired with a leave, never a hidden microphone.
                val joined = withContext(NonCancellable) { request("join", JSONObject().put("consent", true)) }
                joinedToken = joined.getString("memberToken")
                currentCoroutineContext().ensureActive()
                check(epoch == generation)
                val format = joined.getJSONObject("format")
                check(format.getInt("sampleRate") == WalkiePcm.SAMPLE_RATE &&
                    format.getInt("channels") == 1 && format.getInt("frameBytes") == WalkiePcm.BYTES)
                memberToken = joinedToken
                mutedSpeakers.clear()
                publish(state.copy(joining = false, joined = true, microphoneReady = hasMicrophonePermission(),
                    muteSupported = joined.optJSONObject("features")?.optBoolean("speakerMute") == true,
                    cooldownSeconds = cooldownSeconds(joined), notice = null,
                    status = "듣기 대기 · 말하기를 누를 때만 마이크를 사용합니다."))
                while (isActive && epoch == generation) {
                    val result = request("poll", JSONObject().put("memberToken", joinedToken).put("waitMs", 400))
                    if (epoch != generation) break
                    val events = result.getJSONArray("events")
                    for (index in 0 until events.length()) {
                        if (epoch != generation) break
                        val event = events.getJSONObject(index)
                        when (event.getString("type")) {
                            "audio" -> if (!pressed && !state.transmitting) {
                                withContext(Dispatchers.IO) { playFrame(event, epoch) }
                            }
                            "end" -> {
                                if (event.getString("talkId") == activeTalkId) stopTalking()
                                withContext(Dispatchers.IO) { finishPlayback(event.getString("talkId"), epoch) }
                            }
                        }
                    }
                    if (epoch != generation) break
                    val speaker = result.optJSONObject("speaker")
                    val remoteSpeaker = speaker?.takeUnless {
                        it.optBoolean("mine") || mutedSpeakers.contains(it.optString("senderId"))
                    }
                    val remote = remoteSpeaker?.optString("label")
                    publish(state.copy(participants = result.getInt("participants"), speaker = remote,
                        speakerId = remoteSpeaker?.optString("senderId")?.takeIf { it.isNotBlank() },
                        mutedCount = maxOf(mutedSpeakers.size, result.optInt("mutedCount")),
                        cooldownSeconds = cooldownSeconds(result),
                        status = when {
                            state.transmitting -> if (state.testTone) "1초 테스트음 전송 중" else "내 목소리 전송 중 · 최대 15초"
                            state.requesting -> "발언권 연결 중"
                            remote != null -> "$remote 말하는 중"
                            else -> "듣기 대기 · 말하기를 누를 때만 마이크를 사용합니다."
                        }))
                }
            } catch (error: Exception) {
                if (epoch == generation && error !is CancellationException) {
                    leave("워키토키 종료 · ${error.message ?: "연결을 확인해 주세요."}")
                }
            } finally {
                joinedToken?.let { old -> withContext(NonCancellable) {
                    runCatching { request("leave", JSONObject().put("memberToken", old)) }
                } }
            }
        }
    }

    fun leave(message: String = "워키토키를 나갔습니다.") {
        generation++
        memberToken = null
        pressed = false
        pollJob?.cancel()
        pollJob = null
        captureJob?.cancel()
        stopRecorder()
        releasePlayback()
        releaseFocus()
        mutedSpeakers.clear()
        localCooldownUntil = 0L
        publish(state.copy(joined = false, joining = false, requesting = false, transmitting = false,
            speaker = null, speakerId = null, muteSupported = false, mutedCount = 0,
            moderationBusy = false, cooldownSeconds = 0, notice = null,
            participants = 0, elapsedSeconds = 0, inputLevel = 0f,
            quietInput = false, testTone = false, status = message))
    }

    fun startTalking() {
        if (!hasMicrophonePermission()) {
            publish(state.copy(microphoneReady = false, status = "마이크 허용 후 말하기를 다시 누르세요."))
            return
        }
        beginTalk(testTone = false)
    }

    fun sendTestTone() { if (BuildConfig.LOCAL_DEMO) beginTalk(testTone = true) }

    fun muteSpeaker() {
        val member = memberToken ?: return
        val id = state.speakerId ?: return
        if (!state.joined || !state.muteSupported || state.moderationBusy || mutedSpeakers.size >= 32) return
        val epoch = generation
        mutedSpeakers.add(id)
        releasePlayback()
        releaseFocus(epoch)
        publish(state.copy(speaker = null, speakerId = null, mutedCount = mutedSpeakers.size,
            moderationBusy = true, notice = "이 발언자의 음성을 껐습니다. 이번 참여 동안 유지됩니다."))
        scope.launch {
            try {
                request("mute", JSONObject().put("memberToken", member).put("senderId", id))
            } catch (error: Exception) {
                if (epoch == generation && error !is CancellationException) publish(state.copy(
                    notice = "이 기기에서 음소거 중 · 서버 반영 실패. 설정에서 해제할 수 있습니다."))
            } finally {
                if (epoch == generation) publish(state.copy(moderationBusy = false))
            }
        }
    }

    fun unmuteAll() {
        val member = memberToken ?: return
        if (!state.joined || !state.muteSupported || state.moderationBusy) return
        val epoch = generation
        publish(state.copy(moderationBusy = true))
        scope.launch {
            try {
                request("unmute-all", JSONObject().put("memberToken", member))
                if (epoch == generation) {
                    mutedSpeakers.clear()
                    publish(state.copy(mutedCount = 0, notice = "음소거를 모두 해제했습니다."))
                }
            } catch (error: Exception) {
                if (epoch == generation && error !is CancellationException) publish(state.copy(
                    notice = "음소거 해제 실패 · 연결을 확인한 뒤 다시 시도하세요."))
            } finally {
                if (epoch == generation) publish(state.copy(moderationBusy = false))
            }
        }
    }

    private fun cooldownSeconds(result: JSONObject): Int {
        val millis = maxOf(result.optLong("nextTalkInMs"), localCooldownUntil - SystemClock.elapsedRealtime(), 0L)
        return ((millis + 999) / 1000).toInt()
    }

    private fun beginTalk(testTone: Boolean) {
        val member = memberToken ?: return
        if (!state.joined || state.requesting || state.transmitting || captureJob != null ||
            state.speaker != null || state.cooldownSeconds > 0) return
        val epoch = generation
        pressed = true
        publish(state.copy(requesting = true, elapsedSeconds = 0, inputLevel = 0f,
            quietInput = false, testTone = testTone, notice = null, status = "발언권 연결 중"))
        captureJob = scope.launch(start = CoroutineStart.LAZY) {
            val thisJob = currentCoroutineContext()[Job]
            var talkId: String? = null
            try {
                acquireFocus(epoch)
                val started = withContext(NonCancellable) { request("start", JSONObject().put("memberToken", member)) }
                talkId = started.getString("talkId")
                currentCoroutineContext().ensureActive()
                if (!pressed || epoch != generation) return@launch
                activeTalkId = talkId
                releasePlayback()
                publish(state.copy(requesting = false, transmitting = true,
                    status = if (testTone) "1초 테스트음 전송 중" else "내 목소리 전송 중 · 최대 15초"))
                withContext(Dispatchers.IO) { capture(member, talkId, epoch, testTone) }
            } catch (error: Exception) {
                if (epoch == generation && error !is CancellationException) {
                    publish(state.copy(notice = "말하기 중단 · ${error.message ?: "마이크·연결을 확인해 주세요."}"))
                }
            } finally {
                stopRecorder()
                talkId?.let { old -> withContext(NonCancellable) {
                    runCatching { request("finish", JSONObject().put("memberToken", member).put("talkId", old)) }
                } }
                if (epoch == generation) {
                    if (state.transmitting) localCooldownUntil = SystemClock.elapsedRealtime() + 2000
                    pressed = false
                    activeTalkId = null
                    releaseFocus(epoch)
                    publish(state.copy(requesting = false, transmitting = false, elapsedSeconds = 0,
                        inputLevel = 0f, quietInput = false, testTone = false,
                        cooldownSeconds = cooldownSeconds(JSONObject())))
                }
                if (captureJob === thisJob) captureJob = null
            }
        }
        captureJob?.start()
    }

    fun stopTalking() {
        if (state.transmitting) localCooldownUntil = SystemClock.elapsedRealtime() + 2000
        pressed = false
        stopRecorder()
        captureJob?.cancel()
        publish(state.copy(requesting = false, transmitting = false, elapsedSeconds = 0,
            inputLevel = 0f, quietInput = false, testTone = false,
            cooldownSeconds = cooldownSeconds(JSONObject())))
    }

    private suspend fun capture(member: String, talkId: String, epoch: Int, testTone: Boolean) {
        var local: AudioRecord? = null
        try {
            if (!testTone) {
                val minimum = AudioRecord.getMinBufferSize(WalkiePcm.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "이 기기의 마이크 형식을 확인해 주세요." }
                local = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(WalkiePcm.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(maxOf(minimum, WalkiePcm.BYTES * 4)).build()
                check(local.state == AudioRecord.STATE_INITIALIZED) { "마이크 초기화 실패" }
                synchronized(audioLock) {
                    check(pressed && epoch == generation)
                    recorder = local
                    local.startRecording()
                }
                check(local.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "마이크를 시작하지 못했습니다." }
            }
            val startedAt = SystemClock.elapsedRealtime()
            var sequence = 0
            var quietMs = 0
            while (currentCoroutineContext().isActive && pressed && epoch == generation &&
                SystemClock.elapsedRealtime() - startedAt < WalkiePcm.MAX_TALK_MS) {
                val samples = ShortArray(WalkiePcm.SAMPLES)
                if (testTone) {
                    if (sequence >= 10) break
                    samples.indices.forEach { i -> samples[i] =
                        (sin(2 * PI * 440 * (sequence * WalkiePcm.SAMPLES + i) / WalkiePcm.SAMPLE_RATE) * 2600).toInt().toShort() }
                    delay((startedAt + (sequence + 1) * WalkiePcm.FRAME_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                } else {
                    var offset = 0
                    while (offset < samples.size && pressed && epoch == generation) {
                        currentCoroutineContext().ensureActive()
                        val read = local!!.read(samples, offset, samples.size - offset, AudioRecord.READ_NON_BLOCKING)
                        check(read >= 0 || !pressed) { "마이크 입력 오류: $read" }
                        if (read > 0) offset += read else delay(5)
                    }
                    if (offset != samples.size) break
                }
                if (!pressed || epoch != generation) break
                val level = WalkiePcm.inputLevel(samples)
                quietMs = if (level < 1f / 6f) quietMs + WalkiePcm.FRAME_MS else 0
                // Publish before the network request, at most five meter updates per second.
                if (sequence % 2 == 0) withContext(Dispatchers.Main) {
                    if (epoch == generation && pressed && state.transmitting) publish(state.copy(
                        inputLevel = level, quietInput = !testTone && quietMs >= 1500,
                        elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()))
                }
                request("frame", JSONObject().put("memberToken", member).put("talkId", talkId)
                    .put("sequence", sequence).put("pcmBase64", Base64.encodeToString(WalkiePcm.encode(samples), Base64.NO_WRAP)))
                sequence++
            }
        } finally {
            synchronized(audioLock) {
                if (recorder === local) recorder = null
                local?.let { runCatching { it.stop() }; it.release() }
            }
        }
    }

    private fun stopRecorder() = synchronized(audioLock) { recorder?.let { runCatching { it.stop() } }; Unit }

    private suspend fun playFrame(event: JSONObject, epoch: Int) {
        if (epoch != generation || pressed || mutedSpeakers.contains(event.optString("senderId"))) return
        val id = event.getString("talkId")
        val sequence = event.getInt("sequence")
        val samples = WalkiePcm.decode(Base64.decode(event.getString("pcmBase64"), Base64.DEFAULT))
        if (id == playbackId && sequence <= playbackSequence) return
        if (id != playbackId || sequence != playbackSequence + 1) {
            releasePlayback()
            acquireFocus(epoch)
            val minimum = AudioTrack.getMinBufferSize(WalkiePcm.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "음성 출력 형식을 확인해 주세요." }
            synchronized(audioLock) {
                if (epoch != generation || pressed || mutedSpeakers.contains(event.optString("senderId"))) return
                track = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(WalkiePcm.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, WalkiePcm.BYTES * 4)).build()
                check(track!!.state == AudioTrack.STATE_INITIALIZED) { "음성 출력 초기화 실패" }
                playbackId = id
                writtenSamples = 0
                track!!.play()
            }
        }
        val player = synchronized(audioLock) { track } ?: return
        var offset = 0
        val deadline = SystemClock.elapsedRealtime() + 500
        while (offset < samples.size && epoch == generation && !pressed &&
            !mutedSpeakers.contains(event.optString("senderId"))) {
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime() < deadline) { "음성 출력이 지연되어 중단했습니다." }
            val count = synchronized(audioLock) {
                if (track !== player || epoch != generation || mutedSpeakers.contains(event.optString("senderId"))) return
                player.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            check(count >= 0 || epoch != generation) { "음성 출력 오류: $count" }
            if (count > 0) { offset += count; writtenSamples += count } else delay(5)
        }
        playbackSequence = sequence
    }

    private suspend fun finishPlayback(id: String, epoch: Int) {
        if (id != playbackId) return
        val remaining = synchronized(audioLock) {
            track?.let { (writtenSamples - (it.playbackHeadPosition.toLong() and 0xffffffffL)).coerceAtLeast(0) } ?: 0
        }
        delay((remaining * 1000 / WalkiePcm.SAMPLE_RATE + 20).coerceAtMost(500))
        if (epoch == generation && id == playbackId) { releasePlayback(); releaseFocus(epoch) }
    }

    private fun releasePlayback() = synchronized(audioLock) {
        track?.let { runCatching { it.pause(); it.flush() }; it.release() }
        track = null
        playbackId = null
        playbackSequence = -1
        writtenSamples = 0
    }

    private fun acquireFocus(epoch: Int) = synchronized(audioLock) {
        check(epoch == generation) { "종료된 대화입니다." }
        if (focusGeneration != epoch) {
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "다른 오디오가 사용 중입니다. 잠시 후 다시 참여해 주세요."
            }
            focusGeneration = epoch
        }
    }

    private fun releaseFocus(epoch: Int? = null) = synchronized(audioLock) {
        // A just-finished sender must not abandon focus already used by the next live speaker.
        if (epoch != null && track != null) return@synchronized
        if (epoch == null || focusGeneration == epoch) {
            if (focusGeneration >= 0) manager.abandonAudioFocusRequest(focus)
            focusGeneration = -1
        }
    }

    private fun publish(value: WalkieUiState) { state = value; onState(value) }
}
