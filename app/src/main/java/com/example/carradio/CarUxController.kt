package com.example.carradio

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.util.Log

/** Read-only, foreground-scoped AAOS UX restriction subscription. No privileged driving-state API. */
internal class CarUxController(context: Context, private val onState: (DrivingUiState) -> Unit) {
    // Keep the UI context so the manager uses this activity's display restrictions.
    private val context = context
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var generation = 0
    private var timeout: Runnable? = null

    fun start() {
        release()
        val epoch = generation
        onState(DrivingUiState())
        if (Build.VERSION.SDK_INT < 29 || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            onState(DrivingUiState(description = "주행 상태 인식 불가 · 차량 플랫폼 미지원"))
            return
        }
        try {
            val pending = Runnable {
                if (epoch == generation && manager == null) onState(
                    DrivingUiState(description = "주행 상태 인식 불가 · 차량 서비스 연결 대기"))
            }
            timeout = pending
            main.postDelayed(pending, 5000)
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connection, ready ->
                main.post {
                    if (epoch != generation) return@post
                    clearManager()
                    if (!ready) {
                        onState(DrivingUiState(description = "주행 상태 인식 불가 · 차량 서비스 연결 끊김"))
                        return@post
                    }
                    try {
                        val current = connection.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                            ?: error("UX manager unavailable")
                        manager = current
                        current.registerListener {
                            main.post {
                                if (epoch == generation && manager === current) read(current)
                            }
                        }
                        timeout?.let(main::removeCallbacks)
                        timeout = null
                        read(current)
                    } catch (error: Exception) { fail(error) }
                }
            }
            if (car == null) fail(IllegalStateException("Car service unavailable"))
        } catch (error: Exception) { fail(error) }
        catch (error: LinkageError) { fail(error) }
    }

    fun stop() {
        release()
        onState(DrivingUiState(description = "주행 상태 수신 중지"))
    }

    private fun read(current: CarUxRestrictionsManager) {
        try {
            val restrictions = current.currentCarUxRestrictions
                ?: error("No current UX restrictions")
            val flags = restrictions.activeRestrictions
            val knownFlags = CarUxRestrictions.UX_RESTRICTIONS_NO_DIALPAD or
                CarUxRestrictions.UX_RESTRICTIONS_NO_FILTERING or
                CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH or
                CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD or
                CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO or
                CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT or
                CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP or
                CarUxRestrictions.UX_RESTRICTIONS_NO_TEXT_MESSAGE or
                CarUxRestrictions.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION
            val unsupported = flags and knownFlags.inv() != 0
            val limited = restrictions.isRequiresDistractionOptimization || flags != 0
            onState(DrivingUiState(known = true,
                requiresOptimization = restrictions.isRequiresDistractionOptimization,
                activeRestrictions = flags,
                noTextMessages = flags and CarUxRestrictions.UX_RESTRICTIONS_NO_TEXT_MESSAGE != 0,
                stringLimit = restrictions.maxRestrictedStringLength.takeIf {
                    flags and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH != 0 },
                contentLimit = restrictions.maxCumulativeContentItems.takeIf {
                    flags and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT != 0 },
                depthLimit = restrictions.maxContentDepth.takeIf {
                    flags and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT != 0 },
                unsupportedRestrictions = unsupported,
                description = when {
                    unsupported -> "주행 상태 인식 불가 · 지원되지 않는 제한 항목"
                    limited -> "플랫폼 주행 제한 적용 중"
                    else -> "플랫폼 주행 제한 없음"
                }))
        } catch (error: Exception) { fail(error) }
    }

    private fun clearManager() {
        manager?.let { runCatching { it.unregisterListener() } }
        manager = null
    }

    private fun release() {
        generation++
        timeout?.let(main::removeCallbacks)
        timeout = null
        clearManager()
        car?.let { runCatching { it.disconnect() } }
        car = null
    }

    private fun fail(error: Throwable) {
        Log.w("CarRadioDriving", "UX restriction service unavailable: ${error.javaClass.simpleName}")
        release()
        onState(DrivingUiState(description = "주행 상태 인식 불가 · 상태 다시 확인을 눌러 주세요."))
    }
}
