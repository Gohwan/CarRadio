package com.example.carradio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WalkiePcmTest {
    @Test fun inputMeterHasZeroForSilenceAndDoesNotOverflowAtFullScale() {
        assertEquals(0f, WalkiePcm.inputLevel(ShortArray(WalkiePcm.SAMPLES)), 0f)
        assertEquals(1f, WalkiePcm.inputLevel(ShortArray(WalkiePcm.SAMPLES) { Short.MIN_VALUE }), 0.0001f)
        assertEquals(1f, WalkiePcm.inputLevel(ShortArray(WalkiePcm.SAMPLES) { Short.MAX_VALUE }), 0.0001f)
    }

    @Test fun inputMeterMeasuresRmsInsteadOfOneLoudPeak() {
        val constant = ShortArray(WalkiePcm.SAMPLES) { 16384 }
        val alternating = ShortArray(WalkiePcm.SAMPLES) { if (it % 2 == 0) 16384 else -16384 }
        assertEquals(0.8996567f, WalkiePcm.inputLevel(constant), 0.0001f)
        assertEquals(WalkiePcm.inputLevel(constant), WalkiePcm.inputLevel(alternating), 0.0001f)
        val peak = ShortArray(WalkiePcm.SAMPLES).also { it[0] = Short.MIN_VALUE }
        assertEquals(0.46598f, WalkiePcm.inputLevel(peak), 0.0001f)
    }

    @Test fun wireFormatIsSignedLittleEndianAndExactlyOneHundredMilliseconds() {
        val samples = ShortArray(WalkiePcm.SAMPLES)
        samples[0] = Short.MIN_VALUE
        samples[1] = Short.MAX_VALUE
        samples[2] = -1
        samples[3] = 0x1234
        val bytes = WalkiePcm.encode(samples)
        assertEquals(3200, bytes.size)
        assertArrayEquals(byteArrayOf(0, -128, -1, 127, -1, -1, 0x34, 0x12), bytes.copyOfRange(0, 8))
        assertArrayEquals(samples, WalkiePcm.decode(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedNetworkFrames() { WalkiePcm.decode(ByteArray(3199)) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedNetworkFrames() { WalkiePcm.decode(ByteArray(3202)) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPartialMicrophoneFrames() { WalkiePcm.encode(ShortArray(1599)) }
}
