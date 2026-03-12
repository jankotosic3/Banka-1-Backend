package app.service;

import app.dto.NotificationRequest;
import app.dto.ResolvedEmail;
import app.dto.RetryTask;
import app.entities.NotificationDelivery;
import app.entities.NotificationDeliveryStatus;
import app.entities.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationDeliveryService}.
 *
 * <p>These tests validate DB state transitions and retry scheduling logic.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDeliveryServiceUnitTest {

    /**
     * Test email used in assertions and payload examples.
     */
    private static final String TEST_EMAIL = "dimitrije.tomic99@gmail.com";

    @Mock
    private NotificationDeliveryTxService notificationDeliveryTxService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private RetryTaskQueue retryTaskQueue;

    @InjectMocks
    private NotificationDeliveryService notificationDeliveryService;

    private Map<String, NotificationDelivery> deliveriesById;
    private Map<String, NotificationDeliveryStatus> createdStatusesById;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(notificationDeliveryService, "defaultMaxRetries", 4);
        ReflectionTestUtils.setField(notificationDeliveryService, "retryDelaySeconds", 5);
        ReflectionTestUtils.setField(notificationDeliveryService, "startupPageSize", 100);
        deliveriesById = new HashMap<>();
        createdStatusesById = new HashMap<>();

        lenient().when(notificationDeliveryTxService.createPendingDelivery(any(NotificationDelivery.class)))
                .thenAnswer(invocation -> {
                    NotificationDelivery delivery = invocation.getArgument(0);
                    createdStatusesById.put(delivery.getDeliveryId(), delivery.getStatus());
                    deliveriesById.put(delivery.getDeliveryId(), delivery);
                    return delivery;
                });
        lenient().when(notificationDeliveryTxService.persistFailedAudit(any(NotificationDelivery.class)))
                .thenAnswer(invocation -> {
                    NotificationDelivery delivery = invocation.getArgument(0);
                    deliveriesById.put(delivery.getDeliveryId(), delivery);
                    return delivery;
                });
        lenient().when(notificationDeliveryTxService.findByDeliveryId(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(deliveriesById.get(invocation.getArgument(0))));
        lenient().when(notificationDeliveryTxService.findPageByStatus(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        lenient().doAnswer(invocation -> {
            String deliveryId = invocation.getArgument(0);
            Instant attemptedAt = invocation.getArgument(1);
            NotificationDelivery delivery = deliveriesById.get(deliveryId);
            if (delivery != null) {
                delivery.setStatus(NotificationDeliveryStatus.SUCCEEDED);
                delivery.setLastAttemptAt(attemptedAt);
                delivery.setSentAt(attemptedAt);
                delivery.setLastError(null);
                delivery.setNextAttemptAt(null);
            }
            return null;
        }).when(notificationDeliveryTxService).markSucceeded(anyString(), any(Instant.class));
        lenient().doAnswer(invocation -> {
            String deliveryId = invocation.getArgument(0);
            Instant attemptedAt = invocation.getArgument(1);
            String error = invocation.getArgument(2);
            boolean retryable = invocation.getArgument(3);
            int retryDelaySeconds = invocation.getArgument(4);
            NotificationDelivery delivery = deliveriesById.get(deliveryId);
            if (delivery == null) {
                return null;
            }

            int updatedRetryCount = delivery.getRetryCount() + 1;
            delivery.setRetryCount(updatedRetryCount);
            delivery.setLastAttemptAt(attemptedAt);
            delivery.setSentAt(null);
            delivery.setLastError(error);

            if (retryable && updatedRetryCount < delivery.getMaxRetries()) {
                delivery.setStatus(NotificationDeliveryStatus.RETRY_SCHEDULED);
                delivery.setNextAttemptAt(attemptedAt.plusSeconds(retryDelaySeconds));
                return delivery.getNextAttemptAt();
            }

            delivery.setStatus(NotificationDeliveryStatus.FAILED);
            delivery.setNextAttemptAt(null);
            return null;
        }).when(notificationDeliveryTxService).markFailedOrRetry(
                anyString(), any(Instant.class), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), anyInt()
        );
    }

    @Test
    void handleIncomingMessageCreatesNewDeliveryAndMarksSucceededOnSendSuccess() {
        NotificationRequest request = new NotificationRequest("Dimitrije", TEST_EMAIL, Map.of("subject", "Hello", "body", "Body"));
        when(notificationService.resolveEmailContent(request, NotificationType.EMPLOYEE_CREATED))
                .thenReturn(new ResolvedEmail(TEST_EMAIL, "Hello", "Body"));

        runHandleIncomingMessageInTransaction(
                () -> notificationDeliveryService.handleIncomingMessage(
                        request,
                        NotificationType.EMPLOYEE_CREATED
                )
        );

        verify(notificationService).sendEmail(TEST_EMAIL, "Hello", "Body");
        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(notificationDeliveryTxService).createPendingDelivery(deliveryCaptor.capture());

        NotificationDelivery created = deliveryCaptor.getValue();
        assertNotNull(created.getDeliveryId());
        UUID.fromString(created.getDeliveryId());
        assertEquals(NotificationType.EMPLOYEE_CREATED, created.getNotificationType());
        assertEquals(NotificationDeliveryStatus.PENDING, createdStatusesById.get(created.getDeliveryId()));

        NotificationDelivery finalSaved = deliveriesById.get(created.getDeliveryId());
        assertEquals(NotificationDeliveryStatus.SUCCEEDED, finalSaved.getStatus());
        assertEquals(0, finalSaved.getRetryCount());
        assertNotNull(finalSaved.getSentAt());
        verify(notificationDeliveryTxService).markSucceeded(created.getDeliveryId(), finalSaved.getLastAttemptAt());
    }

    @Test
    void handleIncomingMessageSchedulesRetryUsingConfiguredDefaultDelayOnSendFailure() {
        NotificationRequest request = new NotificationRequest("Dimitrije", TEST_EMAIL, Map.of("subject", "Hello", "body", "Body"));
        when(notificationService.resolveEmailContent(request, NotificationType.EMPLOYEE_CREATED))
                .thenReturn(new ResolvedEmail(TEST_EMAIL, "Hello", "Body"));
        doThrow(new IllegalStateException("SMTP unavailable"))
                .when(notificationService).sendEmail(TEST_EMAIL, "Hello", "Body");

        runHandleIncomingMessageInTransaction(
                () -> notificationDeliveryService.handleIncomingMessage(request, NotificationType.EMPLOYEE_CREATED)
        );

        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(notificationDeliveryTxService).createPendingDelivery(deliveryCaptor.capture());
        NotificationDelivery finalSaved = deliveriesById.get(deliveryCaptor.getValue().getDeliveryId());

        assertEquals(NotificationDeliveryStatus.RETRY_SCHEDULED, finalSaved.getStatus());
        assertEquals(1, finalSaved.getRetryCount());
        assertNotNull(finalSaved.getNextAttemptAt());
        assertNotNull(finalSaved.getLastAttemptAt());
        assertEquals(5, finalSaved.getNextAttemptAt().getEpochSecond() - finalSaved.getLastAttemptAt().getEpochSecond());

        verify(retryTaskQueue).schedule(finalSaved.getDeliveryId(), finalSaved.getNextAttemptAt());
    }

    @Test
    void handleIncomingMessageFailsImmediatelyOnMailAuthenticationException() {
        NotificationRequest request = new NotificationRequest("Dimitrije", TEST_EMAIL, Map.of("subject", "Hello", "body", "Body"));
        when(notificationService.resolveEmailContent(request, NotificationType.EMPLOYEE_CREATED))
                .thenReturn(new ResolvedEmail(TEST_EMAIL, "Hello", "Body"));
        doThrow(new MailAuthenticationException("Authentication failed"))
                .when(notificationService).sendEmail(TEST_EMAIL, "Hello", "Body");

        runHandleIncomingMessageInTransaction(
                () -> notificationDeliveryService.handleIncomingMessage(request, NotificationType.EMPLOYEE_CREATED)
        );

        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(notificationDeliveryTxService).createPendingDelivery(deliveryCaptor.capture());
        NotificationDelivery finalSaved = deliveriesById.get(deliveryCaptor.getValue().getDeliveryId());

        assertEquals(NotificationDeliveryStatus.FAILED, finalSaved.getStatus());
        assertEquals(1, finalSaved.getRetryCount());
        verify(retryTaskQueue, never()).schedule(any(), any());
    }

    @Test
    void handleIncomingMessagePersistsFailedAuditWhenPayloadIsInvalid() {
        notificationDeliveryService.handleIncomingMessage(null, NotificationType.EMPLOYEE_CREATED);

        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(notificationDeliveryTxService).persistFailedAudit(deliveryCaptor.capture());
        NotificationDelivery saved = deliveryCaptor.getValue();

        assertEquals(NotificationDeliveryStatus.FAILED, saved.getStatus());
        assertEquals(NotificationType.EMPLOYEE_CREATED, saved.getNotificationType());
        assertEquals("unknown", saved.getRecipientEmail());
        assertEquals("IllegalArgumentException", saved.getLastError());
        verify(notificationService, never()).sendEmail(any(), any(), any());
    }

    @Test
    void handleIncomingMessagePersistsFailedAuditWhenRoutingKeyIsUnsupported() {
        NotificationRequest request = new NotificationRequest("Dimitrije", TEST_EMAIL, Map.of());

        notificationDeliveryService.handleIncomingMessage(request, "employee.unknown");

        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(notificationDeliveryTxService).persistFailedAudit(deliveryCaptor.capture());
        NotificationDelivery saved = deliveryCaptor.getValue();

        assertEquals(NotificationDeliveryStatus.FAILED, saved.getStatus());
        assertEquals(NotificationType.UNKNOWN, saved.getNotificationType());
        assertEquals(TEST_EMAIL, saved.getRecipientEmail());
        assertEquals("Unsupported routing key: employee.unknown", saved.getLastError());
        verify(notificationService, never()).sendEmail(any(), any(), any());
    }

    @Test
    void processDueRetriesLoadsFromDbAndMarksSucceededWhenTaskIsDue() {
        Instant now = Instant.now();
        RetryTask dueTask = new RetryTask("delivery-1", now.minusSeconds(1));
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setDeliveryId("delivery-1");
        delivery.setRecipientEmail(TEST_EMAIL);
        delivery.setSubject("Hello");
        delivery.setBody("Body");
        delivery.setStatus(NotificationDeliveryStatus.RETRY_SCHEDULED);
        delivery.setNotificationType(NotificationType.EMPLOYEE_CREATED);
        delivery.setRetryCount(1);
        delivery.setMaxRetries(4);
        delivery.setNextAttemptAt(now.minusSeconds(1));
        deliveriesById.put(delivery.getDeliveryId(), delivery);

        when(retryTaskQueue.peek()).thenReturn(dueTask).thenReturn(null);
        when(retryTaskQueue.pollDue(any(Instant.class))).thenReturn(dueTask);

        notificationDeliveryService.processDueRetries();

        verify(notificationService).sendEmail(TEST_EMAIL, "Hello", "Body");
        assertEquals(NotificationDeliveryStatus.SUCCEEDED, deliveriesById.get("delivery-1").getStatus());
    }

    @Test
    void processDueRetriesDoesNothingWhenNextTaskIsNotDueYet() {
        Instant future = Instant.now().plusSeconds(30);
        when(retryTaskQueue.peek()).thenReturn(new RetryTask("delivery-future", future));

        notificationDeliveryService.processDueRetries();

        verify(retryTaskQueue, never()).pollDue(any(Instant.class));
        verify(notificationDeliveryTxService, never()).findByDeliveryId(any());
        verify(notificationService, never()).sendEmail(any(), any(), any());
    }

    @Test
    void processDueRetriesReschedulesTaskWhenDatabaseStateWasMovedIntoFuture() {
        Instant now = Instant.now();
        RetryTask dueTask = new RetryTask("delivery-1", now.minusSeconds(1));
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setDeliveryId("delivery-1");
        delivery.setRecipientEmail(TEST_EMAIL);
        delivery.setSubject("Hello");
        delivery.setBody("Body");
        delivery.setStatus(NotificationDeliveryStatus.RETRY_SCHEDULED);
        delivery.setNotificationType(NotificationType.EMPLOYEE_CREATED);
        delivery.setRetryCount(1);
        delivery.setMaxRetries(4);
        delivery.setNextAttemptAt(now.plusSeconds(60));
        deliveriesById.put(delivery.getDeliveryId(), delivery);

        when(retryTaskQueue.peek()).thenReturn(dueTask).thenReturn(null);
        when(retryTaskQueue.pollDue(any(Instant.class))).thenReturn(dueTask);

        notificationDeliveryService.processDueRetries();

        verify(retryTaskQueue).schedule("delivery-1", delivery.getNextAttemptAt());
        verify(notificationService, never()).sendEmail(any(), any(), any());
    }

    @Test
    void processDueRetriesMarksDeliveryFailedWhenRetryBudgetIsExhausted() {
        Instant now = Instant.now();
        RetryTask dueTask = new RetryTask("delivery-1", now.minusSeconds(1));
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setDeliveryId("delivery-1");
        delivery.setRecipientEmail(TEST_EMAIL);
        delivery.setSubject("Hello");
        delivery.setBody("Body");
        delivery.setStatus(NotificationDeliveryStatus.RETRY_SCHEDULED);
        delivery.setNotificationType(NotificationType.EMPLOYEE_CREATED);
        delivery.setRetryCount(3);
        delivery.setMaxRetries(4);
        delivery.setNextAttemptAt(now.minusSeconds(1));
        deliveriesById.put(delivery.getDeliveryId(), delivery);

        when(retryTaskQueue.peek()).thenReturn(dueTask).thenReturn(null);
        when(retryTaskQueue.pollDue(any(Instant.class))).thenReturn(dueTask);
        doThrow(new IllegalStateException("SMTP unavailable"))
                .when(notificationService).sendEmail(TEST_EMAIL, "Hello", "Body");

        notificationDeliveryService.processDueRetries();

        NotificationDelivery finalSaved = deliveriesById.get("delivery-1");

        assertEquals(NotificationDeliveryStatus.FAILED, finalSaved.getStatus());
        assertEquals(4, finalSaved.getRetryCount());
        assertNull(finalSaved.getNextAttemptAt());
        verify(retryTaskQueue, never()).schedule("delivery-1", now.minusSeconds(1));
    }

    @Test
    void processDueRetriesSkipsMissingDeliveryRecord() {
        Instant now = Instant.now();
        RetryTask dueTask = new RetryTask("missing-delivery", now.minusSeconds(1));

        when(retryTaskQueue.peek()).thenReturn(dueTask).thenReturn(null);
        when(retryTaskQueue.pollDue(any(Instant.class))).thenReturn(dueTask);

        notificationDeliveryService.processDueRetries();

        verify(notificationService, never()).sendEmail(any(), any(), any());
        verify(notificationDeliveryTxService, never()).markSucceeded(anyString(), any(Instant.class));
        verify(notificationDeliveryTxService, never()).markFailedOrRetry(
                anyString(), any(Instant.class), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), anyInt()
        );
    }

    @Test
    void loadRetryTasksOnStartupQueuesOnlyRetryableRecords() {
        NotificationDelivery retryable = new NotificationDelivery();
        retryable.setDeliveryId("delivery-retryable");
        retryable.setRetryCount(1);
        retryable.setMaxRetries(4);
        retryable.setStatus(NotificationDeliveryStatus.RETRY_SCHEDULED);
        retryable.setNextAttemptAt(Instant.parse("2026-03-07T12:00:05Z"));

        NotificationDelivery exhausted = new NotificationDelivery();
        exhausted.setDeliveryId("delivery-exhausted");
        exhausted.setRetryCount(4);
        exhausted.setMaxRetries(4);
        exhausted.setStatus(NotificationDeliveryStatus.RETRY_SCHEDULED);
        exhausted.setNextAttemptAt(Instant.parse("2026-03-07T12:00:10Z"));

        NotificationDelivery pending = new NotificationDelivery();
        pending.setDeliveryId("delivery-pending");
        pending.setRetryCount(0);
        pending.setMaxRetries(4);
        pending.setStatus(NotificationDeliveryStatus.PENDING);

        when(notificationDeliveryTxService.findPageByStatus(eq(NotificationDeliveryStatus.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pending)));
        when(notificationDeliveryTxService.findPageByStatus(eq(NotificationDeliveryStatus.RETRY_SCHEDULED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(retryable, exhausted)));

        notificationDeliveryService.loadRetryTasksOnStartup();

        verify(retryTaskQueue).schedule("delivery-retryable", retryable.getNextAttemptAt());
        verify(retryTaskQueue, never()).schedule("delivery-exhausted", exhausted.getNextAttemptAt());
        verify(retryTaskQueue, atLeastOnce()).schedule(org.mockito.ArgumentMatchers.eq("delivery-pending"), any(Instant.class));
    }

    @Test
    void handleIncomingMessageWithNullRoutingKeyPersistsFailedAudit() {
        NotificationRequest request = new NotificationRequest("Dimitrije", TEST_EMAIL, Map.of());

        notificationDeliveryService.handleIncomingMessage(request, (String) null);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(notificationDeliveryTxService).persistFailedAudit(captor.capture());
        NotificationDelivery saved = captor.getValue();

        assertEquals(NotificationDeliveryStatus.FAILED, saved.getStatus());
        assertEquals(NotificationType.UNKNOWN, saved.getNotificationType());
        verify(notificationService, never()).sendEmail(any(), any(), any());
    }

    @Test
    void handleIncomingMessagePersistsFailedAuditWhenContentResolutionThrows() {
        NotificationRequest request = new NotificationRequest("Dimitrije", TEST_EMAIL, Map.of());
        when(notificationService.resolveEmailContent(request, NotificationType.EMPLOYEE_CREATED))
                .thenThrow(new IllegalArgumentException("template error"));

        runHandleIncomingMessageInTransaction(
                () -> notificationDeliveryService.handleIncomingMessage(request, NotificationType.EMPLOYEE_CREATED)
        );

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(notificationDeliveryTxService).persistFailedAudit(captor.capture());
        NotificationDelivery saved = captor.getValue();

        assertEquals(NotificationDeliveryStatus.FAILED, saved.getStatus());
        assertEquals(NotificationType.EMPLOYEE_CREATED, saved.getNotificationType());
        assertEquals("IllegalArgumentException", saved.getLastError());
        verify(notificationService, never()).sendEmail(any(), any(), any());
    }

    @Test
    void validateRetryConfigThrowsWhenMaxRetriesIsZero() {
        ReflectionTestUtils.setField(notificationDeliveryService, "defaultMaxRetries", 0);

        assertThrows(IllegalStateException.class,
                () -> notificationDeliveryService.validateRetryConfig());
    }

    @Test
    void validateRetryConfigThrowsWhenDelaySecondsIsZero() {
        ReflectionTestUtils.setField(notificationDeliveryService, "retryDelaySeconds", 0);

        assertThrows(IllegalStateException.class,
                () -> notificationDeliveryService.validateRetryConfig());
    }

    @Test
    void loadRetryTasksOnStartupPaginatesThroughMultiplePages() {
        NotificationDelivery p1 = new NotificationDelivery();
        p1.setDeliveryId("delivery-page1");
        p1.setRetryCount(0);
        p1.setMaxRetries(4);
        p1.setStatus(NotificationDeliveryStatus.PENDING);

        NotificationDelivery p2 = new NotificationDelivery();
        p2.setDeliveryId("delivery-page2");
        p2.setRetryCount(1);
        p2.setMaxRetries(4);
        p2.setStatus(NotificationDeliveryStatus.RETRY_SCHEDULED);
        p2.setNextAttemptAt(Instant.parse("2026-03-11T10:00:00Z"));

        org.springframework.data.domain.PageImpl<NotificationDelivery> page1 =
                new org.springframework.data.domain.PageImpl<>(
                        List.of(p1),
                        org.springframework.data.domain.PageRequest.of(0, 1),
                        2
                );
        org.springframework.data.domain.PageImpl<NotificationDelivery> page2 =
                new org.springframework.data.domain.PageImpl<>(
                        List.of(p2),
                        org.springframework.data.domain.PageRequest.of(1, 1),
                        2
                );

        ReflectionTestUtils.setField(notificationDeliveryService, "startupPageSize", 1);

        when(notificationDeliveryTxService.findPageByStatus(
                eq(NotificationDeliveryStatus.PENDING), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page1)
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        when(notificationDeliveryTxService.findPageByStatus(
                eq(NotificationDeliveryStatus.RETRY_SCHEDULED), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page2)
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        notificationDeliveryService.loadRetryTasksOnStartup();

        verify(retryTaskQueue, atLeastOnce()).schedule(eq("delivery-page1"), any(Instant.class));
        verify(retryTaskQueue).schedule("delivery-page2", p2.getNextAttemptAt());
    }

    private void runHandleIncomingMessageInTransaction(Runnable action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            action.run();
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

}
