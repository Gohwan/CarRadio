package com.example.carradio

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Base64
import java.io.File
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal data class LocationFix(val latitude: Double, val longitude: Double)
internal data class RadioOptions(
    val channel: String = "일반 대화",
    val rangeKm: Int = 1,
    val receiving: Boolean = true,
    val plate4: String = "",
)
internal data class NearbyDevice(val label: String, val distanceMeters: Int, val testVehicle: Boolean)
internal data class IncomingMessage(
    val id: String, val sender: String, val text: String,
    val distanceMeters: Int, val testVehicle: Boolean,
    val channel: String = "일반 대화",
    val kind: String = "text",
    val durationMs: Int = 0,
    val voiceFile: File? = null,
)
internal data class LocalRelayState(
    val active: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val status: String = "PC 테스트 미연결",
    val peers: List<NearbyDevice> = emptyList(),
    val messages: List<IncomingMessage> = emptyList(),
    val lastTransmission: String? = null,
    val walkieSupported: Boolean = false,
)

/** Fixed loopback destination for the local emulator test; no external endpoint. */
internal class LocalRelayController(
    context: Context,
    private val scope: CoroutineScope,
    private val onState: (LocalRelayState) -> Unit,
) {
    private val voiceCache = File(context.cacheDir, "received-voice").apply {
        mkdirs()
        listFiles()?.forEach { it.delete() }
    }
    private val preferences = context.getSharedPreferences("radio_settings", Context.MODE_PRIVATE)
    private val clientId = preferences.getString("local_client_id", null) ?: UUID.randomUUID().toString().also {
        preferences.edit { putString("local_client_id", it) }
    }
    private var options = RadioOptions()
    private var fix: LocationFix? = null
    private var state = LocalRelayState()
    private var generation = 0
    private var connectionJob: Job? = null
    private var actionJob: Job? = null
    private var disconnectJob: Job? = null
    private val deliveryTracker = DeliveryTracker()
    private val acknowledgedIds = LinkedHashSet<String>()

    fun updateOptions(value: RadioOptions) {
        if (value == options) return
        options = value
        if (state.active) publish(state.copy(
            connected = false, peers = emptyList(), messages = emptyList(),
            status = "PC 테스트 설정 갱신 중",
        ))
    }

    fun updateFix(value: LocationFix?) {
        fix = value
        if (value == null && state.active) disconnect()
    }

    fun connect() {
        if (!BuildConfig.LOCAL_DEMO) {
            publish(LocalRelayState(status = "이 빌드에는 로컬 시연 연결이 포함되지 않았습니다."))
            return
        }
        if (!VehicleIdentity.validPlate4(options.plate4)) {
            publish(LocalRelayState(status = "차량번호 뒤 4자리를 입력해 주세요."))
            return
        }
        if (fix == null) {
            publish(LocalRelayState(status = "위치 연결 후 PC 테스트를 연결해 주세요."))
            return
        }
        disconnect()
        val token = generation
        acknowledgedIds.clear()
        publish(LocalRelayState(active = true, status = "PC 중계 서버 연결 중",
            lastTransmission = deliveryTracker.disconnectedLabel))
        connectionJob = scope.launch {
            disconnectJob?.join()
            while (isActive && generation == token) {
                val currentFix = fix ?: break
                val currentOptions = options
                try {
                    val syncStartedAt = SystemClock.elapsedRealtime()
                    val result = sync(currentFix, currentOptions)
                    if (token == generation && currentOptions == options) {
                        applySnapshot(result, syncStartedAt)
                        if (result.optInt("protocol", 1) >= 2) acknowledgeReceived(token, currentOptions)
                    }
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    if (token == generation) {
                        voiceCache.listFiles()?.forEach { it.delete() }
                        publish(LocalRelayState(
                            status = "PC 중계 서버 연결 실패 · 서버 실행 확인",
                            lastTransmission = deliveryTracker.disconnectedLabel ?: state.lastTransmission,
                        ))
                    }
                    break
                }
                delay(2000)
            }
        }
    }

    fun disconnect() {
        val wasActive = state.active
        generation += 1
        connectionJob?.cancel()
        actionJob?.cancel()
        connectionJob = null
        actionJob = null
        voiceCache.listFiles()?.forEach { it.delete() }
        if (wasActive) disconnectJob = scope.launch {
            runCatching { request("/v1/disconnect", JSONObject().put("clientId", clientId)) }
        }
        publish(LocalRelayState(lastTransmission = deliveryTracker.disconnectedLabel ?: state.lastTransmission))
    }

    fun addTestVehicles() {
        if (!state.connected || state.busy) return
        val token = generation
        publish(state.copy(busy = true))
        actionJob = scope.launch {
            try {
                val currentFix = fix ?: return@launch
                val currentOptions = options
                sync(currentFix, currentOptions)
                val result = request("/v1/test-vehicles", JSONObject().put("clientId", clientId))
                if (token == generation && options == currentOptions) {
                    applySnapshot(result)
                    if (result.optInt("protocol", 1) >= 2) acknowledgeReceived(token, currentOptions)
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                if (token == generation) publish(state.copy(status = "테스트 차량 배치 실패"))
            } finally {
                if (token == generation) publish(state.copy(busy = false))
            }
        }
    }

    fun sendDraft(
        requestId: String,
        text: String,
        channel: String,
        rangeKm: Int,
        onAccepted: () -> Unit,
    ) {
        if (!state.connected || state.busy || fix == null) return
        val token = generation
        publish(state.copy(busy = true, status = "전송 중"))
        actionJob = scope.launch {
            try {
                val currentFix = fix ?: return@launch
                sync(currentFix, options)
                val response = request("/v1/messages", JSONObject()
                    .put("clientId", clientId).put("requestId", requestId)
                    .put("text", text).put("channel", channel).put("rangeKm", rangeKm))
                if (token == generation) {
                    val receipt = response.optJSONObject("delivery")?.let(::parseReceipt)
                    if (receipt != null) require(receipt.messageId == response.getString("messageId"))
                    deliveryTracker.accepted(
                        receipt,
                        "PC 서버 접수 · 수신 대상 ${response.getInt("targetCount")}대 · 서버의 수신 확인 기능 갱신 필요",
                        SystemClock.elapsedRealtime(),
                    )
                    publish(state.copy(status = "PC 테스트 연결됨", lastTransmission = deliveryTracker.label))
                    onAccepted()
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                if (token == generation) publish(state.copy(status = "전송 실패 · ${exception.message ?: "초안 유지"}"))
            } finally {
                if (token == generation) publish(state.copy(busy = false))
            }
        }
    }

    fun sendVoice(draft: VoiceDraft, onAccepted: () -> Unit) {
        if (!state.connected || state.busy || fix == null || !draft.file.isFile) return
        val token = generation
        publish(state.copy(busy = true, status = "전송 중"))
        actionJob = scope.launch {
            try {
                val currentFix = fix ?: return@launch
                sync(currentFix, options)
                val encoded = withContext(Dispatchers.IO) {
                    val bytes = draft.file.readBytes()
                    require(VoiceMessagePolicy.valid(draft.durationMs, bytes.size))
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
                val response = request("/v1/voice-messages", JSONObject()
                    .put("clientId", clientId).put("requestId", draft.requestId)
                    .put("channel", draft.channel).put("rangeKm", draft.rangeKm)
                    .put("mimeType", "audio/mp4").put("durationMs", draft.durationMs)
                    .put("audioBase64", encoded))
                if (token == generation) {
                    val receipt = parseReceipt(response.getJSONObject("delivery"))
                    require(receipt.messageId == response.getString("messageId"))
                    deliveryTracker.accepted(receipt, "PC 서버 접수", SystemClock.elapsedRealtime())
                    publish(state.copy(status = "PC 테스트 연결됨", lastTransmission = deliveryTracker.label))
                    onAccepted()
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                if (token == generation) publish(state.copy(status = "음성 전송 실패 · ${exception.message ?: "녹음 유지"}"))
            } finally {
                if (token == generation) publish(state.copy(busy = false))
            }
        }
    }

    private suspend fun sync(position: LocationFix, settings: RadioOptions): JSONObject =
        request("/v1/sync", JSONObject()
            .put("clientId", clientId).put("latitude", position.latitude).put("longitude", position.longitude)
            .put("channel", settings.channel).put("rangeKm", settings.rangeKm)
            .put("receiving", settings.receiving).put("plate4", settings.plate4))

    internal suspend fun walkieRequest(action: String, body: JSONObject): JSONObject {
        check(BuildConfig.LOCAL_DEMO)
        return request("/v1/walkie/$action", body.put("clientId", clientId))
    }

    private suspend fun applySnapshot(response: JSONObject, requestStartedAt: Long = SystemClock.elapsedRealtime()) {
        val devices = response.getJSONArray("peers")
        val inbox = response.getJSONArray("messages")
        val token = generation
        val snapshotOptions = options
        val cachedVoices = mutableMapOf<String, File>()
        val currentIds = (0 until inbox.length()).map { inbox.getJSONObject(it).getString("id") }.toSet()
        voiceCache.listFiles()?.filter { it.nameWithoutExtension !in currentIds }?.forEach { it.delete() }
        for (index in 0 until inbox.length()) {
            val item = inbox.getJSONObject(index)
            if (item.optString("kind", "text") != "voice") continue
            val id = item.getString("id")
            if (!Regex("[0-9a-fA-F-]{36}").matches(id)) continue
            val file = File(voiceCache, "$id.m4a")
            try {
                if (!file.isFile) {
                    val audio = request("/v1/voice", JSONObject().put("clientId", clientId).put("messageId", id))
                    val bytes = Base64.decode(audio.getString("audioBase64"), Base64.DEFAULT)
                    require(audio.getString("messageId") == id && audio.getString("mimeType") == "audio/mp4")
                    require(VoiceMessagePolicy.valid(audio.getInt("durationMs"), bytes.size))
                    require(String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp")
                    // A cancelled/old connection must not repopulate its deleted cache.
                    if (token != generation || snapshotOptions != options) return
                    file.writeBytes(bytes)
                }
                cachedVoices[id] = file
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Log.w("CarRadioRelay", "Voice download delayed: ${exception.javaClass.simpleName}")
            }
        }
        if (token != generation || snapshotOptions != options) return
        val deliveries = response.optJSONArray("deliveries")?.let { array ->
            List(array.length()) { index -> parseReceipt(array.getJSONObject(index)) }
        }
        deliveryTracker.observe(deliveries, requestStartedAt)
        publish(state.copy(
            active = true,
            connected = true,
            walkieSupported = response.optJSONObject("features")?.optInt("walkieTalkie", 0) == 1,
            status = "PC 테스트 연결됨",
            lastTransmission = deliveryTracker.label,
            peers = List(devices.length()) { index ->
                val item = devices.getJSONObject(index)
                NearbyDevice(item.getString("label"), item.getInt("distanceMeters"), item.getBoolean("testVehicle"))
            },
            messages = List(inbox.length()) { index ->
                val item = inbox.getJSONObject(index)
                IncomingMessage(item.getString("id"), item.getString("sender"), item.getString("text"),
                    item.getInt("distanceMeters"), item.getBoolean("testVehicle"), options.channel,
                    item.optString("kind", "text"), item.optInt("durationMs", 0), cachedVoices[item.getString("id")])
            },
        ))
    }

    private fun parseReceipt(item: JSONObject): DeliveryReceipt = DeliveryReceipt(
        item.getString("messageId"), item.getInt("targetCount"),
        item.getInt("appTargetCount"), item.getInt("testTargetCount"), item.getInt("acknowledgedCount"),
    )

    private suspend fun acknowledgeReceived(token: Int, settings: RadioOptions) {
        val ids = state.messages.filter { it.kind != "voice" || it.voiceFile?.isFile == true }.map { it.id }.filter { it !in acknowledgedIds }.take(10)
        if (ids.isEmpty()) return
        try {
            request("/v1/ack", JSONObject().put("clientId", clientId)
                .put("messageIds", JSONArray(ids)))
            if (token == generation && settings == options) {
                acknowledgedIds.addAll(ids)
                while (acknowledgedIds.size > 256) acknowledgedIds.remove(acknowledgedIds.first())
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            // The inbox is already accepted. Retry the confirmation on the next sync.
            Log.w("CarRadioRelay", "Receipt acknowledgement delayed: ${exception.javaClass.simpleName}")
        }
    }

    private fun publish(value: LocalRelayState) {
        state = value
        onState(value)
    }

    private suspend fun request(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL("http://10.0.2.2:8765$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(2048) }
                val message = runCatching { JSONObject(detail ?: "{}").optString("error") }.getOrNull()
                error(message?.takeIf { it.isNotBlank() } ?: "Local relay HTTP $status")
            }
            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(2048)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(text.length + count <= 196608) { "Local relay response too large" }
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }
}
