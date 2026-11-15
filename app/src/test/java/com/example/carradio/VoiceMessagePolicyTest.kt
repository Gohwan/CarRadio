package com.example.carradio

import org.junit.Assert.*
import org.junit.Test

class VoiceMessagePolicyTest {
    @Test fun requestedRecordingRateLeavesContainerHeadroomWithinTheExistingUploadLimit() {
        val audioBytes = VoiceMessagePolicy.RECORD_BIT_RATE.toLong() * VoiceMessagePolicy.MAX_DURATION_MS / 8000
        assertTrue(audioBytes + 8 * 1024 <= VoiceMessagePolicy.MAX_BYTES)
    }

    @Test fun plateKeepsLeadingZeroesAndRequiresAsciiDigits() {
        assertTrue(VehicleIdentity.validPlate4("0007"))
        for (value in listOf("", "123", "12345", "１２３４", "12a4", " 1234")) {
            assertFalse(VehicleIdentity.validPlate4(value))
        }
        assertEquals("0007", VehicleIdentity.editPlate4("0a0 07-8"))
    }

    @Test fun voiceDurationAndBytesAreBounded() {
        assertTrue(VoiceMessagePolicy.valid(300, 16))
        assertTrue(VoiceMessagePolicy.valid(15000, 131072))
        assertFalse(VoiceMessagePolicy.valid(299, 16))
        assertFalse(VoiceMessagePolicy.valid(15001, 16))
        assertFalse(VoiceMessagePolicy.valid(1000, 15))
        assertFalse(VoiceMessagePolicy.valid(1000, 131073))
    }
}
