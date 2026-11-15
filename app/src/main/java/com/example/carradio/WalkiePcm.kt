package com.example.carradio

import kotlin.math.log10
import kotlin.math.sqrt

/** Fixed wire format for the local PTT prototype: signed 16-bit little-endian, 16kHz mono. */
internal object WalkiePcm {
    const val SAMPLE_RATE = 16000
    const val FRAME_MS = 100
    const val SAMPLES = 1600
    const val BYTES = SAMPLES * 2
    const val MAX_TALK_MS = 15000

    /** RMS mapped from -60..0 dBFS to 0..1. This is input activity, not acoustic loudness. */
    fun inputLevel(samples: ShortArray): Float {
        require(samples.size == SAMPLES)
        val power = samples.sumOf { value -> value.toDouble() * value } / samples.size
        if (power == 0.0) return 0f
        val db = 20 * log10(sqrt(power) / 32768.0)
        return ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
    }

    fun encode(samples: ShortArray): ByteArray {
        require(samples.size == SAMPLES)
        return ByteArray(BYTES).also { bytes ->
            samples.forEachIndexed { index, value ->
                bytes[index * 2] = value.toByte()
                bytes[index * 2 + 1] = (value.toInt() shr 8).toByte()
            }
        }
    }

    fun decode(bytes: ByteArray): ShortArray {
        require(bytes.size == BYTES)
        return ShortArray(SAMPLES) { index ->
            ((bytes[index * 2].toInt() and 255) or
                (bytes[index * 2 + 1].toInt() shl 8)).toShort()
        }
    }
}
