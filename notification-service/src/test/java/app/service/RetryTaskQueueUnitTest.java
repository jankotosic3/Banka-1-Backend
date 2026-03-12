package app.service;

import app.dto.RetryTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link RetryTaskQueue}.
 */
class RetryTaskQueueUnitTest {

    @Test
    void scheduleWithNullDeliveryIdIsIgnored() {
        RetryTaskQueue queue = new RetryTaskQueue();
        queue.schedule(null, Instant.now());
        assertNull(queue.peek());
    }

    @Test
    void scheduleWithNullNextAttemptAtIsIgnored() {
        RetryTaskQueue queue = new RetryTaskQueue();
        queue.schedule("delivery-1", null);
        assertNull(queue.peek());
    }

    @Test
    void scheduleSameDeliveryIdWithSameTimestampDoesNotAddDuplicateEntry() {
        RetryTaskQueue queue = new RetryTaskQueue();
        Instant t = Instant.parse("2026-03-07T12:00:05Z");

        queue.schedule("delivery-1", t);
        queue.schedule("delivery-1", t);

        Instant afterT = t.plusSeconds(1);
        RetryTask first = queue.pollDue(afterT);
        assertNotNull(first);
        assertNull(queue.pollDue(afterT));
    }

    @Test
    void pollDueOnEmptyQueueReturnsNull() {
        RetryTaskQueue queue = new RetryTaskQueue();
        assertNull(queue.pollDue(Instant.now()));
    }

    @Test
    void pollDueDiscardsMultipleStaleEntriesAndReturnsCurrentOne() {
        RetryTaskQueue queue = new RetryTaskQueue();
        Instant stale1 = Instant.parse("2026-03-07T12:00:01Z");
        Instant stale2 = Instant.parse("2026-03-07T12:00:02Z");
        Instant current = Instant.parse("2026-03-07T12:00:05Z");

        queue.schedule("delivery-1", stale1);
        queue.schedule("delivery-1", stale2);
        queue.schedule("delivery-1", current);

        Instant afterAll = current.plusSeconds(1);
        RetryTask polled = queue.pollDue(afterAll);
        assertNotNull(polled);
        assertEquals(current, polled.nextAttemptAt());
        assertNull(queue.pollDue(afterAll));
    }

    @Test
    void queueReturnsEarliestTaskFirst() {
        RetryTaskQueue queue = new RetryTaskQueue();
        Instant base = Instant.parse("2026-03-07T12:00:00Z");

        queue.schedule("delivery-late", base.plusSeconds(10));
        queue.schedule("delivery-early", base.plusSeconds(5));

        RetryTask head = queue.peek();
        assertEquals("delivery-early", head.deliveryId());

        assertNull(queue.pollDue(base.plusSeconds(4)));
        assertEquals("delivery-early", queue.pollDue(base.plusSeconds(5)).deliveryId());
        assertEquals("delivery-late", queue.pollDue(base.plusSeconds(10)).deliveryId());
    }

    @Test
    void queueIgnoresStaleEntryWhenSameDeliveryIsRescheduled() {
        RetryTaskQueue queue = new RetryTaskQueue();
        Instant firstAttempt = Instant.parse("2026-03-07T12:00:05Z");
        Instant secondAttempt = Instant.parse("2026-03-07T12:00:10Z");

        queue.schedule("delivery-1", firstAttempt);
        queue.schedule("delivery-1", secondAttempt);

        assertNull(queue.pollDue(firstAttempt));
        assertEquals("delivery-1", queue.pollDue(secondAttempt).deliveryId());
    }
}
