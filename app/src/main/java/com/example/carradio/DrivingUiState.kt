package com.example.carradio

internal enum class DrivingTestMode(val label: String) {
    AUTOMATIC("자동 · 차량 상태 적용"),
    RESTRICTED("주행 제한 화면"),
    TEXT_BLOCKED("문자 제한 화면"),
    UNAVAILABLE("상태 인식 불가 화면"),
}

internal enum class SignalPreset(val title: String, val text: String) {
    DANGER("위험 알리기", "위험 알림 · 도로 위험 주의"),
    HELP("도움 요청하기", "도움 요청 · 도움이 필요합니다."),
}

/** Platform restrictions, never a speed/gear guess. Unknown state cannot unlock editing or TX. */
internal data class DrivingUiState(
    val known: Boolean = false,
    val requiresOptimization: Boolean = true,
    val activeRestrictions: Int = 0,
    val noTextMessages: Boolean = true,
    val stringLimit: Int? = null,
    val contentLimit: Int? = null,
    val depthLimit: Int? = null,
    val unsupportedRestrictions: Boolean = false,
    val description: String = "주행 상태 확인 중",
    val simulated: Boolean = false,
) {
    val canConfigure: Boolean get() = known && !requiresOptimization && activeRestrictions == 0 && !unsupportedRestrictions
    val compact: Boolean get() = !canConfigure
    val canTalk: Boolean get() = known && !unsupportedRestrictions

    fun canSendPreset(preset: SignalPreset): Boolean = known && !unsupportedRestrictions && !noTextMessages &&
        (stringLimit == null || preset.text.length <= stringLimit) &&
        (contentLimit == null || contentLimit >= 1) && (depthLimit == null || depthLimit >= 1)

    fun speakerLabel(label: String?): String? = label?.takeIf {
        known && !unsupportedRestrictions && !noTextMessages && (contentLimit == null || contentLimit >= 1)
    }?.take((stringLimit ?: 80).coerceIn(0, 80))?.takeIf { it.isNotEmpty() }

    /** Debug overlays can only tighten the platform result and are never saved to preferences. */
    fun withTestMode(mode: DrivingTestMode): DrivingUiState = when {
        mode == DrivingTestMode.AUTOMATIC -> this
        !known || mode == DrivingTestMode.UNAVAILABLE -> copy(
            known = false, requiresOptimization = true, noTextMessages = true,
            description = "주행 상태 인식 불가 · 테스트", simulated = true)
        else -> copy(requiresOptimization = true,
            noTextMessages = noTextMessages || mode == DrivingTestMode.TEXT_BLOCKED,
            description = if (mode == DrivingTestMode.TEXT_BLOCKED) "문자 표시·전송 제한 · 테스트" else "주행 제한 화면 · 테스트",
            simulated = true)
    }

    companion object {
        fun unrestricted() = DrivingUiState(known = true, requiresOptimization = false,
            noTextMessages = false, description = "플랫폼 주행 제한 없음")
    }
}
