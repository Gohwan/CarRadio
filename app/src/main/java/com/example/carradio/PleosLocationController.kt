package com.example.carradio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class LocationUiState(
    val active: Boolean = false,
    val description: String = "위치 미연결",
    val coordinates: String? = null,
    val fix: LocationFix? = null,
)

/** Foreground-only SDK session. Coordinates stay in memory and are never transmitted. */
internal class PleosLocationController(
    context: Context,
    private val onState: (LocationUiState) -> Unit,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var sdk: FusedLocation? = null
    private var listener: FusedLocationListener? = null
    private var initialized = false
    private var registered = false
    private var generation = 0
    private var latestTimestamp: Long? = null
    private var hasLiveFix = false
    private var state = LocationUiState()

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, PERMISSION) ==
        PackageManager.PERMISSION_GRANTED

    fun start() {
        releaseSession()
        if (!hasPermission()) {
            publish(LocationUiState(description = "위치 권한이 필요합니다."))
            return
        }
        val currentGeneration = generation
        publish(LocationUiState(active = true, description = "위치 서비스 연결 중 · 좌표 대기"))
        try {
            val service = FusedLocation(context)
            sdk = service
            if (!service.isSupported()) {
                releaseSession()
                publish(LocationUiState(description = "이 기기에서 Pleos 위치 서비스를 사용할 수 없습니다."))
                return
            }
            // Release is also attempted if initialization throws after partial service binding.
            initialized = true
            service.initialize()
            val updates = object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    postIfCurrent(currentGeneration) { acceptLocation(location, cached = false) }
                }

                override fun onError(exception: Exception) {
                    postIfCurrent(currentGeneration) { fail(exception) }
                }
            }
            listener = updates
            registered = true
            service.registerFusedLocationCallback(updates)
            service.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    postIfCurrent(currentGeneration) { acceptLocation(location, cached = true) }
                }

                override fun onError(exception: Exception) {
                    postIfCurrent(currentGeneration) {
                        Log.w(TAG, "Cached location unavailable: ${exception.javaClass.simpleName}")
                        if (state.coordinates == null) {
                            publish(LocationUiState(
                                active = true,
                                description = "최근 좌표 조회 실패 · 새 좌표 대기",
                            ))
                        }
                    }
                }
            })
        } catch (exception: Exception) {
            fail(exception)
        }
    }

    fun permissionDenied() {
        releaseSession()
        publish(LocationUiState(description = "위치 권한 미허용 · 위치 연결을 눌러 다시 확인"))
    }

    fun disconnect() {
        releaseSession()
        publish(LocationUiState())
    }

    fun pause() {
        val wasActive = state.active
        releaseSession()
        if (wasActive) publish(LocationUiState(description = "위치 수신 일시 중지"))
    }

    private fun acceptLocation(location: LocationData?, cached: Boolean) {
        if (location == null || !location.latitude.isFinite() || !location.longitude.isFinite() ||
            location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0
        ) {
            if (state.coordinates == null) {
                publish(LocationUiState(active = true, description = "위치 좌표 인식 불가 · 새 좌표 대기"))
            }
            return
        }
        // A delayed cache response must not replace a live update or a newer timestamp.
        if (cached && hasLiveFix) return
        val timestamp = location.timestampMillis
        val previousTimestamp = latestTimestamp
        if (previousTimestamp != null && previousTimestamp > 0 &&
            (timestamp <= 0 || timestamp < previousTimestamp)
        ) return
        latestTimestamp = timestamp
        if (!cached) hasLiveFix = true
        val time = if (timestamp > 0) {
            SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(timestamp))
        } else {
            "시각 인식 불가"
        }
        val source = if (cached) "최근 저장 좌표" else "최근 수신 좌표"
        publish(LocationUiState(
            active = true,
            description = "$source · $time",
            coordinates = String.format(
                Locale.US, "위도 %.5f\n경도 %.5f", location.latitude, location.longitude,
            ),
            fix = LocationFix(location.latitude, location.longitude),
        ))
    }

    private fun fail(exception: Exception) {
        Log.w(TAG, "Location service failed: ${exception.javaClass.simpleName}")
        releaseSession()
        val message = if (exception is SecurityException) {
            "위치 권한 또는 차량의 위치 공유 설정을 확인해 주세요."
        } else {
            "위치 서비스 연결 실패 · 다시 연결해 주세요."
        }
        publish(LocationUiState(description = message))
    }

    private fun postIfCurrent(token: Int, action: () -> Unit) {
        main.post {
            if (token == generation) action()
        }
    }

    private fun publish(value: LocationUiState) {
        state = value
        onState(value)
    }

    private fun releaseSession() {
        generation += 1
        val service = sdk
        val updates = listener
        val shouldUnregister = registered
        val shouldRelease = initialized
        sdk = null
        listener = null
        registered = false
        initialized = false
        latestTimestamp = null
        hasLiveFix = false
        if (service != null && updates != null && shouldUnregister && hasPermission()) {
            runCatching { service.unregisterFusedLocationCallback(updates) }
                .onFailure { Log.w(TAG, "Location callback cleanup failed: ${it.javaClass.simpleName}") }
        }
        if (service != null && shouldRelease) {
            runCatching { service.release() }
                .onFailure { Log.w(TAG, "Location service cleanup failed: ${it.javaClass.simpleName}") }
        }
    }

    companion object {
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        private const val TAG = "CarRadioLocation"
    }
}
