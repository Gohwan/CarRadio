package com.example.carradio

import org.junit.Assert.assertEquals
import org.junit.Test

class InboxAnnouncementTrackerTest {
    private fun message(id: String) = IncomingMessage(id, "테스트 차량", "메시지", 250, true)

    @Test
    fun initialSnapshotIsNotReadAndRepeatedPollDoesNotRepeatNewMessage() {
        val tracker = InboxAnnouncementTracker()
        assertEquals(emptyList<IncomingMessage>(), tracker.updates(true, listOf(message("old"))))
        assertEquals(listOf(message("new")), tracker.updates(true, listOf(message("new"), message("old"))))
        assertEquals(emptyList<IncomingMessage>(), tracker.updates(true, listOf(message("new"), message("old"))))
    }

    @Test
    fun severalNewMessagesAreReadOldestFirst() {
        val tracker = InboxAnnouncementTracker()
        tracker.updates(true, emptyList())
        assertEquals(listOf(message("one"), message("two")),
            tracker.updates(true, listOf(message("two"), message("one"))))
    }

    @Test
    fun reconnectOrChannelChangeDoesNotReadTheExistingInbox() {
        val tracker = InboxAnnouncementTracker()
        tracker.updates(true, emptyList())
        tracker.updates(true, listOf(message("one")))
        tracker.updates(false, emptyList())
        assertEquals(emptyList<IncomingMessage>(), tracker.updates(true, listOf(message("two"), message("one"))))
        assertEquals(listOf(message("three")),
            tracker.updates(true, listOf(message("three"), message("two"), message("one"))))
    }
}
