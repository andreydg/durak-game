package com.example.durakgame.service.store;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteReceiptsTest {
    @Test
    void plainWritesPruneExpiredReceiptsWithoutChangingRecentCreationTimes() {
        long now = 2 * WriteReceipts.RETENTION_MS;
        Map<String, Long> original = Map.of(
                "old", now - WriteReceipts.RETENTION_MS - 1,
                "at-cutoff", now - WriteReceipts.RETENTION_MS,
                "recent", now - WriteReceipts.RETENTION_MS + 1);

        Map<String, Long> retained = WriteReceipts.merge(original, null, now);

        assertEquals(Map.of("recent", now - WriteReceipts.RETENTION_MS + 1), retained);
        assertEquals(3, original.size(), "cleanup must not mutate a stored entry");
    }

    @Test
    void newWriteAddsReceiptAndDoesNotRefreshExistingCreationTimes() {
        Map<String, Long> first = WriteReceipts.merge(Map.of(), "first", 100);
        Map<String, Long> second = WriteReceipts.merge(first, "second", 200);
        assertEquals(Map.of("first", 100L, "second", 200L), second);
        assertEquals(second, WriteReceipts.merge(second, "first", 300));
    }

    @Test
    void retainsOnlyTheNewest1024Receipts() {
        Map<String, Long> existing = new HashMap<>();
        for (int i = 0; i < WriteReceipts.MAX_RECEIPTS + 10; i++) {
            existing.put("receipt-" + i, (long) i);
        }
        Map<String, Long> retained = WriteReceipts.merge(existing, "new", 2000);

        assertEquals(1024, retained.size());
        assertTrue(retained.containsKey("new"));
        assertFalse(retained.containsKey("receipt-10"));
        assertTrue(retained.containsKey("receipt-11"));
        assertEquals(1034, existing.size());
        assertEquals(1024, WriteReceipts.merge(existing, null, 2000).size());
    }

    @Test
    void newestReceiptSurvivesEvenWhenTheBucketIsFullAtTheSameMillisecond() {
        Map<String, Long> existing = new HashMap<>();
        for (int i = 0; i < WriteReceipts.MAX_RECEIPTS; i++) {
            existing.put("a-" + i, 100L);
        }
        Map<String, Long> retained = WriteReceipts.merge(existing, "z-new", 100);
        assertEquals(1024, retained.size());
        assertTrue(retained.containsKey("z-new"));
    }
}
