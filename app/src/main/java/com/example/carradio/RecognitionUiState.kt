package com.example.carradio

internal enum class RecognitionPhase {
    CLOSED, PERMISSION, CONNECTING, STARTING, LISTENING, FINISHING, REVIEW, ERROR,
}

/** Raw SDK RMS values, not decibels. A callback with zero is still a callback. */
internal data class RecognitionSignal(
    val callbacks: Int = 0,
    val latest: Double? = null,
    val peak: Double? = null,
) {
    fun record(value: Double): RecognitionSignal {
        val finite = value.takeIf { it.isFinite() }
        return copy(
            callbacks = callbacks + 1,
            latest = finite,
            peak = if (finite == null) peak else peak?.let { maxOf(it, finite) } ?: finite,
        )
    }
}

internal data class RecognitionUiState(
    val phase: RecognitionPhase = RecognitionPhase.CLOSED,
    val text: String = "",
    val status: String = "",
    val sdkStarted: Boolean = false,
    val signal: RecognitionSignal = RecognitionSignal(),
    val textCallbacks: Int = 0,
    val errorCode: String? = null,
) {
    val visible: Boolean get() = phase != RecognitionPhase.CLOSED
    val active: Boolean get() = phase in setOf(
        RecognitionPhase.PERMISSION, RecognitionPhase.CONNECTING, RecognitionPhase.STARTING,
        RecognitionPhase.LISTENING, RecognitionPhase.FINISHING,
    )
    val canFinish: Boolean get() =
        phase == RecognitionPhase.STARTING || phase == RecognitionPhase.LISTENING
    val canSave: Boolean get() = visible && !active && text.isNotBlank()
}
