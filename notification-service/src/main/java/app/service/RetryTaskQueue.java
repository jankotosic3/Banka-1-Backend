package app.service;

import app.dto.RetryTask;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * In-memory retry scheduler queue ordered by {@code nextAttemptAt}.
 *
 * <p>This queue is a runtime optimization only. PostgreSQL remains the source of truth.
 */
@Component
public class RetryTaskQueue {
    /**
     * Priority queue where the earliest retry timestamp is always at the head.
     */
    private final PriorityBlockingQueue<RetryTask> queue =
            new PriorityBlockingQueue<>(16, Comparator.comparing(RetryTask::nextAttemptAt));

    /**
     * Latest scheduled retry timestamp per deliveryId used to discard stale
     * queue entries.
     */
    private final ConcurrentHashMap<String, Instant> scheduledByDeliveryId =
            new ConcurrentHashMap<>();

    /**
     * Adds or updates a retry task.
     *
     * @param deliveryId    internal delivery identifier
     * @param nextAttemptAt next retry timestamp
     */
    public void schedule(String deliveryId, Instant nextAttemptAt) {
        if (deliveryId == null || nextAttemptAt == null) {
            return;
        }
        Instant previous = scheduledByDeliveryId.put(deliveryId, nextAttemptAt);
        if (previous != null && previous.equals(nextAttemptAt)) {
            return;
        }
        queue.offer(new RetryTask(deliveryId, nextAttemptAt));
    }

    /**
     * Returns the next scheduled task without removing it.
     *
     * @return queue head, or {@code null} if queue is empty
     */
    public RetryTask peek() {
        return queue.peek();
    }

    /**
     * Pops and returns the next due task when its timestamp is in the past/present.
     *
     * @param now current wall-clock timestamp
     * @return due task or {@code null} when nothing is ready
     */
    public RetryTask pollDue(Instant now) {
        while (true) {
            RetryTask head = queue.peek();
            if (head == null || head.nextAttemptAt().isAfter(now)) {
                return null;
            }
            queue.poll();

            Instant currentSchedule = scheduledByDeliveryId.get(head.deliveryId());
            if (currentSchedule == null || !currentSchedule.equals(head.nextAttemptAt())) {
                continue;
            }
            scheduledByDeliveryId.remove(head.deliveryId(), currentSchedule);
            return head;
        }
    }
}
