package com.example.carradio

/** App acknowledgement is transport processing, not proof that a person read the text. */
internal data class DeliveryReceipt(
    val messageId: String,
    val targetCount: Int,
    val appTargetCount: Int,
    val testTargetCount: Int,
    val acknowledgedCount: Int,
) {
    init {
        require(targetCount >= 0 && appTargetCount >= 0 && testTargetCount >= 0)
        require(targetCount == appTargetCount + testTargetCount)
        require(acknowledgedCount in 0..appTargetCount)
    }

    val description: String get() = buildString {
        append("PC 서버 접수")
        when {
            targetCount == 0 -> append(" · 수신 대상 없음")
            appTargetCount == 0 -> append(" · 앱 수신 확인 대상 없음")
            else -> {
                append(" · 앱 수신 확인 ${acknowledgedCount}/${appTargetCount}대")
                if (acknowledgedCount < appTargetCount) append(" · 확인 대기")
            }
        }
        if (testTargetCount > 0) append(" · 자동응답 테스트 차량 ${testTargetCount}대 별도")
    }

    fun merge(newer: DeliveryReceipt): DeliveryReceipt {
        require(messageId == newer.messageId && targetCount == newer.targetCount &&
            appTargetCount == newer.appTargetCount && testTargetCount == newer.testTargetCount)
        return copy(acknowledgedCount = maxOf(acknowledgedCount, newer.acknowledgedCount))
    }
}

/** Late polls must not overwrite a newer send or erase a previously confirmed receiver. */
internal class DeliveryTracker {
    var receipt: DeliveryReceipt? = null
        private set
    var label: String? = null
        private set
    private var acceptedAt = 0L

    fun accepted(value: DeliveryReceipt?, fallback: String, at: Long) {
        receipt = value
        label = value?.description ?: fallback
        acceptedAt = at
    }

    fun observe(values: List<DeliveryReceipt>?, requestStartedAt: Long) {
        val existing = receipt ?: return
        if (requestStartedAt < acceptedAt) return
        val value = values?.find { it.messageId == existing.messageId }
        if (value == null) {
            label = "마지막 확인 · ${existing.description} · 현재 상태 조회 불가"
        } else {
            receipt = existing.merge(value)
            label = receipt?.description
        }
    }

    val disconnectedLabel: String? get() = label?.let { "$it · 연결 해제 후 갱신 중단" }
}
