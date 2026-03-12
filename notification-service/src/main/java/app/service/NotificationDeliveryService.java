package app.service;

import app.dto.NotificationRequest;
import app.dto.ResolvedEmail;
import app.dto.RetryTask;
import app.entities.NotificationDelivery;
import app.entities.NotificationDeliveryStatus;
import app.entities.NotificationType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates incoming delivery processing and database-backed retry lifecycle.
 */
@Service
@RequiredArgsConstructor
public class NotificationDeliveryService {
    /** Maximum stored error message length. */
    private static final int MAX_ERROR_LENGTH = 1000;
    /** Placeholder recipient stored when the incoming payload does not contain one. */
    private static final String UNKNOWN_RECIPIENT = "unknown";
    /** Placeholder text stored when email content could not be rendered. */
    private static final String EMPTY_CONTENT = "";

    /**
     * Persistence layer for delivery state transitions.
     */
    private final NotificationDeliveryTxService notificationDeliveryTxService;
    /**
     * Email renderer/sender abstraction.
     */
    private final NotificationService notificationService;
    /**
     * In-memory scheduler optimization for due retries.
     */
    private final RetryTaskQueue retryTaskQueue;

    /**
     * Configured retry budget per new delivery record.
     */
    @Value("${notification.retry.max-retries:4}")
    private int defaultMaxRetries;

    /**
     * Delay in seconds before a retryable failed delivery is attempted again.
     */
    @Value("${notification.retry.delay-seconds:5}")
    private int retryDelaySeconds;

    /**
     * Batch size used while reloading retryable deliveries on startup.
     */
    @Value("${notification.retry.startup-page-size:500}")
    private int startupPageSize;

    /**
     * Validates that retry configuration values are sensible at startup.
     */
    @PostConstruct
    void validateRetryConfig() {
        if (defaultMaxRetries < 1) {
            throw new IllegalStateException(
                    "notification.retry.max-retries must be >= 1, got: " + defaultMaxRetries
            );
        }
        if (retryDelaySeconds < 1) {
            throw new IllegalStateException(
                    "notification.retry.delay-seconds must be >= 1, got: " + retryDelaySeconds
            );
        }
    }

    /**
     * Handles a newly consumed RabbitMQ message using the raw routing key.
     *
     * @param req incoming notification payload
     * @param routingKey routing key from RabbitMQ
     */
    @Transactional
    public void handleIncomingMessage(NotificationRequest req, String routingKey) {
        Optional<NotificationType> notificationType = resolveNotificationType(routingKey);
        if (notificationType.isEmpty()) {
            notificationDeliveryTxService.persistFailedAudit(
                    buildFailedAudit(
                            req,
                            NotificationType.UNKNOWN,
                            "Unsupported routing key: " + routingKey
                    )
            );
            return;
        }
        processIncomingMessage(req, notificationType.get());
    }

    /**
     * Handles a consumed RabbitMQ message after the notification type is known.
     *
     * @param req incoming notification payload
     * @param notificationType type resolved from the RabbitMQ routing key
     */
    @Transactional
    void handleIncomingMessage(
            NotificationRequest req,
            NotificationType notificationType
    ) {
        processIncomingMessage(req, notificationType);
    }

    /**
     * Validates, renders, and persists a new pending delivery.
     *
     * <p>IMPORTANT: this method never sends email directly. It only registers the
     * send attempt to run after the surrounding transaction commits successfully.
     *
     * @param req incoming notification payload
     * @param notificationType resolved notification type
     */
    private void processIncomingMessage(
            NotificationRequest req,
            NotificationType notificationType
    ) {
        try {
            validateIncoming(req);
            validateNotificationType(notificationType);
            ResolvedEmail resolvedEmail =
                    notificationService.resolveEmailContent(req, notificationType);
            String deliveryId = UUID.randomUUID().toString();
            NotificationDelivery delivery = buildPendingDelivery(
                    deliveryId, resolvedEmail, normalizeNotificationType(notificationType)
            );
            notificationDeliveryTxService.createPendingDelivery(delivery);
            runAfterCommit(() -> attemptDelivery(deliveryId));
        } catch (RuntimeException ex) {
            notificationDeliveryTxService.persistFailedAudit(
                    buildFailedAudit(
                            req,
                            normalizeNotificationType(notificationType),
                            trimError(ex)
                    )
            );
        }
    }

    /**
     * Scheduled retry worker that only inspects the queue head and processes due
     * tasks.
     */
    @Scheduled(fixedDelayString = "${notification.retry.scheduler-delay-millis:1000}")
    public void processDueRetries() {
        while (true) {
            RetryTask head = retryTaskQueue.peek();
            Instant now = Instant.now();
            if (head == null || head.nextAttemptAt().isAfter(now)) {
                return;
            }

            RetryTask dueTask = retryTaskQueue.pollDue(now);
            if (dueTask == null) {
                return;
            }
            processRetryTask(dueTask.deliveryId());
        }
    }

    /**
     * Reloads retryable deliveries from PostgreSQL into the in-memory queue on
     * startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadRetryTasksOnStartup() {
        Instant now = Instant.now();
        reloadStartupTasks(NotificationDeliveryStatus.PENDING, now);
        reloadStartupTasks(NotificationDeliveryStatus.RETRY_SCHEDULED, now);
    }

    /**
     * Executes one retry task after validating the latest database state.
     *
     * @param deliveryId internal delivery identifier
     */
    private void processRetryTask(String deliveryId) {
        Optional<@NonNull NotificationDelivery> optionalDelivery =
                notificationDeliveryTxService.findByDeliveryId(deliveryId);
        if (optionalDelivery.isEmpty()) {
            return;
        }

        NotificationDelivery delivery = optionalDelivery.get();
        if (shouldSkipAttempt(delivery, Instant.now())) {
            return;
        }
        attemptDelivery(deliveryId);
    }

    /**
     * Creates a new persisted delivery record.
     *
     * @param deliveryId generated internal UUID
     * @param resolvedEmail rendered email content
     * @param notificationType resolved notification type
     * @return persisted delivery record
     */
    private NotificationDelivery buildPendingDelivery(
            String deliveryId,
            ResolvedEmail resolvedEmail,
            NotificationType notificationType
    ) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setDeliveryId(deliveryId);
        delivery.setRetryCount(0);
        delivery.setMaxRetries(defaultMaxRetries);
        delivery.setStatus(NotificationDeliveryStatus.PENDING);
        delivery.setNotificationType(notificationType);
        updateDeliveryPayload(delivery, resolvedEmail);
        return delivery;
    }

    /**
     * Persists a terminally failed record for invalid or unsupported incoming
     * messages.
     *
     * @param request incoming payload, which may be null or malformed
     * @param notificationType resolved or fallback notification type
     * @param error error reason stored with the delivery
     * @return failed audit record ready for persistence
     */
    private NotificationDelivery buildFailedAudit(
            NotificationRequest request,
            NotificationType notificationType,
            String error
    ) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setDeliveryId(UUID.randomUUID().toString());
        delivery.setRetryCount(0);
        delivery.setMaxRetries(defaultMaxRetries);
        delivery.setStatus(NotificationDeliveryStatus.FAILED);
        delivery.setNotificationType(notificationType);
        delivery.setRecipientEmail(extractUserEmail(request));
        delivery.setSubject(EMPTY_CONTENT);
        delivery.setBody(EMPTY_CONTENT);
        delivery.setLastError(error);
        delivery.setNextAttemptAt(null);
        delivery.setLastAttemptAt(null);
        delivery.setSentAt(null);
        return delivery;
    }

    /**
     * Copies rendered email fields into a mutable delivery entity.
     *
     * @param delivery target entity
     * @param resolvedEmail source email content
     */
    private void updateDeliveryPayload(
            NotificationDelivery delivery,
            ResolvedEmail resolvedEmail
    ) {
        delivery.setRecipientEmail(resolvedEmail.recipientEmail());
        delivery.setSubject(resolvedEmail.subject());
        delivery.setBody(resolvedEmail.body());
    }

    /**
     * Performs one immediate send attempt and updates persistence state accordingly.
     *
     * @param deliveryId internal delivery identifier
     */
    private void attemptDelivery(String deliveryId) {
        Optional<@NonNull NotificationDelivery> optionalDelivery =
                notificationDeliveryTxService.findByDeliveryId(deliveryId);
        if (optionalDelivery.isEmpty()) {
            return;
        }

        NotificationDelivery delivery = optionalDelivery.get();
        Instant now = Instant.now();
        if (shouldSkipAttempt(delivery, now)) {
            return;
        }
        try {
            notificationService.sendEmail(
                    delivery.getRecipientEmail(),
                    delivery.getSubject(),
                    delivery.getBody()
            );
            notificationDeliveryTxService.markSucceeded(deliveryId, now);
        } catch (Exception ex) {
            Instant nextAttemptAt = notificationDeliveryTxService.markFailedOrRetry(
                    deliveryId,
                    now,
                    trimError(ex),
                    isRetryable(ex),
                    retryDelaySeconds
            );
            if (nextAttemptAt != null) {
                retryTaskQueue.schedule(deliveryId, nextAttemptAt);
            }
        }
    }

    /**
     * Determines whether the delivery should be skipped for now.
     *
     * @param delivery persisted delivery state
     * @param now current wall-clock timestamp
     * @return {@code true} when sending must not proceed yet
     */
    private boolean shouldSkipAttempt(NotificationDelivery delivery, Instant now) {
        if (isRecoverablePending(delivery)) {
            return false;
        }
        if (!isRecoverableScheduledRetry(delivery)) {
            return true;
        }
        if (delivery.getNextAttemptAt().isAfter(now)) {
            retryTaskQueue.schedule(delivery.getDeliveryId(), delivery.getNextAttemptAt());
            return true;
        }
        return false;
    }

    /**
     * Checks whether a pending delivery can still enter an attempt.
     *
     * @param delivery persisted delivery state
     * @return {@code true} when the delivery is pending and still retryable
     */
    private boolean isRecoverablePending(NotificationDelivery delivery) {
        return delivery.getStatus() == NotificationDeliveryStatus.PENDING
                && delivery.getRetryCount() < delivery.getMaxRetries();
    }

    /**
     * Checks whether a scheduled retry is still eligible for processing.
     *
     * @param delivery persisted delivery state
     * @return {@code true} when retry metadata is complete and retry budget
     *         remains
     */
    private boolean isRecoverableScheduledRetry(NotificationDelivery delivery) {
        return delivery.getStatus() == NotificationDeliveryStatus.RETRY_SCHEDULED
                && delivery.getRetryCount() < delivery.getMaxRetries()
                && delivery.getNextAttemptAt() != null;
    }

    /**
     * Loads one status bucket page by page and enqueues recoverable records.
     *
     * @param status lifecycle status being reloaded
     * @param now current wall-clock timestamp used for pending records
     */
    private void reloadStartupTasks(NotificationDeliveryStatus status, Instant now) {
        int pageNumber = 0;
        while (true) {
            Page<@NonNull NotificationDelivery> page =
                    notificationDeliveryTxService.findPageByStatus(
                            status,
                            PageRequest.of(pageNumber, startupPageSize)
                    );
            for (NotificationDelivery delivery : page.getContent()) {
                if (isRecoverablePending(delivery)) {
                    retryTaskQueue.schedule(delivery.getDeliveryId(), now);
                    continue;
                }
                if (isRecoverableScheduledRetry(delivery)) {
                    retryTaskQueue.schedule(delivery.getDeliveryId(), delivery.getNextAttemptAt());
                }
            }
            if (!page.hasNext()) {
                return;
            }
            pageNumber++;
        }
    }

    /**
     * Registers work that must run strictly after transaction commit.
     *
     * <p>IMPORTANT: callers must already be inside an active Spring-managed
     * transaction. Running the action earlier would break delivery guarantees.
     *
     * @param action callback that must run after commit
     * @throws IllegalStateException when no active transaction synchronization
     *         exists
     */
    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("runAfterCommit called without active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * Maps RabbitMQ routing keys to supported notification event types.
     *
     * @param routingKey routing key from AMQP message headers
     * @return resolved event type when supported
     */
    private Optional<NotificationType> resolveNotificationType(String routingKey) {
        if (routingKey == null) {
            return Optional.empty();
        }
        return switch (routingKey) {
            case NotificationType.ROUTING_KEY_EMPLOYEE_CREATED ->
                    Optional.of(NotificationType.EMPLOYEE_CREATED);
            case NotificationType.ROUTING_KEY_EMPLOYEE_PASSWORD_RESET ->
                    Optional.of(NotificationType.EMPLOYEE_PASSWORD_RESET);
            case NotificationType.ROUTING_KEY_EMPLOYEE_ACCOUNT_DEACTIVATED ->
                    Optional.of(NotificationType.EMPLOYEE_ACCOUNT_DEACTIVATED);
            default -> Optional.empty();
        };
    }

    /**
     * Validates basic incoming payload shape.
     *
     * @param request payload object from listener
     */
    private void validateIncoming(NotificationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Notification payload is required");
        }
    }

    /**
     * Validates required notification type resolved from routing key.
     *
     * @param notificationType resolved notification type
     */
    private void validateNotificationType(NotificationType notificationType) {
        if (notificationType == null) {
            throw new IllegalArgumentException("notificationType is required");
        }
    }

    /**
     * Produces a bounded string representation for persistence.
     *
     * @param ex source exception
     * @return trimmed error text up to 1000 characters
     */
    private String trimError(Exception ex) {
        String error = ex.getClass().getSimpleName();
        if (error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    /**
     * Decides whether a failed send attempt should be retried.
     *
     * @param ex delivery failure thrown by the mail layer
     * @return {@code true} when the failure is considered transient
     */
    private boolean isRetryable(Exception ex) {
        return !(ex instanceof MailAuthenticationException);
    }

    /**
     * Replaces a missing notification type with the fallback enum value.
     *
     * @param notificationType candidate notification type
     * @return original type or {@link NotificationType#UNKNOWN} when absent
     */
    private NotificationType normalizeNotificationType(NotificationType notificationType) {
        return notificationType == null ? NotificationType.UNKNOWN : notificationType;
    }

    /**
     * Extracts the recipient email for audit-only failures.
     *
     * @param request incoming payload that may be null or incomplete
     * @return request email or a placeholder when the payload is incomplete
     */
    private String extractUserEmail(NotificationRequest request) {
        if (request == null || request.getUserEmail() == null || request.getUserEmail().isBlank()) {
            return UNKNOWN_RECIPIENT;
        }
        return request.getUserEmail();
    }
}
