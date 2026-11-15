package com.example.carradio

import org.junit.Assert.*
import org.junit.Test

class RecognitionUiStateTest {
    @Test fun requestIsNotProofOfListening() {
        val state = RecognitionUiState(phase = RecognitionPhase.STARTING, text = "partial")
        assertTrue(state.active)
        assertTrue(state.canFinish)
        assertFalse(state.sdkStarted)
        assertFalse(state.canSave)
    }

    @Test fun onlyNonblankReviewedTextCanBeSaved() {
        assertTrue(RecognitionUiState(RecognitionPhase.REVIEW, "hello").canSave)
        assertFalse(RecognitionUiState(RecognitionPhase.REVIEW, "  ").canSave)
        assertFalse(RecognitionUiState(RecognitionPhase.CLOSED, "hello").canSave)
    }

    @Test fun zeroCallbackIsDistinctFromNoCallbackAndPeakIsRetained() {
        val signal = RecognitionSignal().record(0.0).record(0.25).record(0.05)
        assertEquals(3, signal.callbacks)
        assertEquals(0.05, signal.latest!!, 0.0)
        assertEquals(0.25, signal.peak!!, 0.0)
    }

    @Test fun invalidRmsDoesNotCorruptPeak() {
        val signal = RecognitionSignal().record(0.25).record(Double.NaN)
        assertEquals(2, signal.callbacks)
        assertNull(signal.latest)
        assertEquals(0.25, signal.peak!!, 0.0)
    }
}
