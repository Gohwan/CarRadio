package com.example.carradio

/** The first snapshot is a baseline; later polls produce each new ID once, oldest first. */
internal class InboxAnnouncementTracker(private val capacity: Int = 256) {
    private val seen = LinkedHashSet<String>()
    private var baselineNeeded = true

    fun updates(connected: Boolean, newestFirst: List<IncomingMessage>): List<IncomingMessage> {
        if (!connected) {
            baselineNeeded = true
            return emptyList()
        }
        val result = if (baselineNeeded) emptyList() else newestFirst.asReversed().filter { it.id !in seen }
        newestFirst.asReversed().forEach { seen.add(it.id) }
        while (seen.size > capacity) seen.remove(seen.first())
        baselineNeeded = false
        return result
    }
}
