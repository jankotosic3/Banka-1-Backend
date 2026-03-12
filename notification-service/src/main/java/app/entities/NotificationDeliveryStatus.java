package app.entities;

/**
 * Delivery lifecycle states persisted for each notification.
 */
public enum NotificationDeliveryStatus {
    /**
     * Record was created but a send attempt has not started yet.
     */
    PENDING,
    /**
     * Previous attempt failed and a retry is scheduled.
     */
    RETRY_SCHEDULED,
    /**
     * Email was sent successfully.
     */
    SUCCEEDED,
    /**
     * Retry budget is exhausted and delivery is terminally failed.
     */
    FAILED
}
