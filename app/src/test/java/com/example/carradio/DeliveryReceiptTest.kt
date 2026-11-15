package com.example.carradio

import org.junit.Assert.*
import org.junit.Test

class DeliveryReceiptTest {
    @Test fun fixtureRepliesDoNotLookLikeAppAcknowledgements() {
        val pending = DeliveryReceipt("one", 3, 1, 2, 0)
        assertTrue(pending.description.contains("0/1대"))
        assertTrue(pending.description.contains("테스트 차량 2대 별도"))
        assertFalse(DeliveryReceipt("fixtures", 2, 0, 2, 0).description.contains("2/2대"))
        assertTrue(DeliveryReceipt("none", 0, 0, 0, 0).description.contains("수신 대상 없음"))
    }

    @Test fun acknowledgementCountsNeverGoBackwards() {
        val receipt = DeliveryReceipt("one", 2, 2, 0, 1)
        assertEquals(1, receipt.merge(receipt.copy(acknowledgedCount = 0)).acknowledgedCount)
        assertEquals(2, receipt.merge(receipt.copy(acknowledgedCount = 2)).acknowledgedCount)
    }

    @Test fun anOlderPollCannotEraseTheLatestSend() {
        val tracker = DeliveryTracker()
        val receipt = DeliveryReceipt("new", 1, 1, 0, 0)
        tracker.accepted(receipt, "", 200)
        tracker.observe(emptyList(), 199)
        assertEquals(receipt.description, tracker.label)
    }

    @Test fun missingServerRecordKeepsTheLastKnownConfirmationExplicit() {
        val tracker = DeliveryTracker()
        val receipt = DeliveryReceipt("one", 1, 1, 0, 1)
        tracker.accepted(receipt, "", 100)
        tracker.observe(emptyList(), 101)
        assertEquals(1, tracker.receipt!!.acknowledgedCount)
        assertTrue(tracker.label!!.contains("마지막 확인"))
        assertTrue(tracker.label!!.contains("조회 불가"))
        assertTrue(tracker.disconnectedLabel!!.contains("갱신 중단"))
    }

    @Test fun aNewSendDoesNotInheritTheOldMessagesAcknowledgement() {
        val tracker = DeliveryTracker()
        val old = DeliveryReceipt("old", 1, 1, 0, 1)
        val next = old.copy(messageId = "next", acknowledgedCount = 0)
        tracker.accepted(old, "", 100)
        tracker.accepted(next, "", 200)
        tracker.observe(listOf(old), 199)
        assertEquals(next, tracker.receipt)
        tracker.observe(listOf(next.copy(acknowledgedCount = 1)), 201)
        assertEquals(1, tracker.receipt!!.acknowledgedCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun impossibleReceiverCountsAreRejected() {
        DeliveryReceipt("one", 1, 1, 0, 2)
    }
}
