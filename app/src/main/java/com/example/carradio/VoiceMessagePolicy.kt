package com.example.carradio

internal object VehicleIdentity {
    fun validPlate4(value: String): Boolean = value.length == 4 && value.all { it in '0'..'9' }
    fun editPlate4(value: String): String = value.filter { it in '0'..'9' }.take(4)
}

internal object VoiceMessagePolicy {
    const val RECORD_SAMPLE_RATE_HZ = 48000
    const val RECORD_BIT_RATE = 64000
    const val MAX_DURATION_MS = 15000
    const val MAX_BYTES = 128 * 1024
    fun valid(durationMs: Int, size: Int): Boolean =
        durationMs in 300..MAX_DURATION_MS && size in 16..MAX_BYTES
}

