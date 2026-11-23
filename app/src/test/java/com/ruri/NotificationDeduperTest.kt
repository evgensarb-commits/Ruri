package com.ruri

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDeduperTest {
    @Test fun unchangedUpdatesAreCoalescedButChangedTextIsRecorded() {
        val key = "test-notification-key"
        NotificationDeduper.remove(key)
        assertTrue(NotificationDeduper.shouldRecord(key, 100))
        assertFalse(NotificationDeduper.shouldRecord(key, 100))
        assertTrue(NotificationDeduper.shouldRecord(key, 101))
        NotificationDeduper.remove(key)
        assertTrue(NotificationDeduper.shouldRecord(key, 101))
        NotificationDeduper.remove(key)
    }
}
