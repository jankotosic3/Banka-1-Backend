package app.integration;

import app.dto.NotificationRequest;
import app.entities.NotificationDelivery;
import app.entities.NotificationDeliveryStatus;
import app.entities.NotificationType;
import app.repository.NotificationDeliveryRepository;
import app.service.NotificationDeliveryService;
import app.service.RetryTaskQueue;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailParseException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.datasource.url=jdbc:h2:mem:notification-retry-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "notification.retry.delay-seconds=7",
        "management.endpoint.health.group.readiness.include=readinessState,db,rabbit"
})
class NotificationRetryIntegrationTest {

    @Autowired
    private NotificationDeliveryService notificationDeliveryService;

    @Autowired
    private NotificationDeliveryRepository notificationDeliveryRepository;

    @Autowired
    private RetryTaskQueue retryTaskQueue;

    @Autowired
    private ControlledMailSender controlledMailSender;

    @BeforeEach
    void setUp() {
        notificationDeliveryRepository.deleteAll();
        controlledMailSender.reset();
    }

    @Test
    void retryableFailureIsPersistedRetriedAndEventuallySucceeds() {
        controlledMailSender.failNext(new IllegalStateException("SMTP unavailable"));
        NotificationRequest request = new NotificationRequest(
                "Andrija",
                "andrija@example.com",
                Map.of("name", "Andrija", "activationLink", "https://example.com/activate/123")
        );

        notificationDeliveryService.handleIncomingMessage(request, NotificationType.ROUTING_KEY_EMPLOYEE_CREATED);

        waitForCondition(Duration.ofSeconds(5), () -> {
            List<NotificationDelivery> deliveries = notificationDeliveryRepository.findAll();
            return deliveries.size() == 1
                    && deliveries.getFirst().getStatus() == NotificationDeliveryStatus.RETRY_SCHEDULED;
        });
        NotificationDelivery scheduled = singleDelivery();
        assertEquals(NotificationDeliveryStatus.RETRY_SCHEDULED, scheduled.getStatus());
        assertEquals(1, scheduled.getRetryCount());
        assertNotNull(scheduled.getNextAttemptAt());
        assertEquals(7, Duration.between(scheduled.getLastAttemptAt(), scheduled.getNextAttemptAt()).getSeconds());
        assertEquals(1, controlledMailSender.attemptCount());
        assertEquals(0, controlledMailSender.sentCount());

        makeRetryDue(scheduled.getDeliveryId());
        notificationDeliveryService.processDueRetries();

        NotificationDelivery succeeded = deliveryById(scheduled.getDeliveryId());
        assertEquals(NotificationDeliveryStatus.SUCCEEDED, succeeded.getStatus());
        assertEquals(1, succeeded.getRetryCount());
        assertNotNull(succeeded.getSentAt());
        assertNull(succeeded.getNextAttemptAt());
        assertNull(succeeded.getLastError());
        assertEquals(2, controlledMailSender.attemptCount());
        assertEquals(1, controlledMailSender.sentCount());
    }

    @Test
    void retryableFailuresEventuallyExhaustRetryBudgetAndMarkDeliveryFailed() {
        controlledMailSender.failNext(new IllegalStateException("SMTP unavailable"));
        controlledMailSender.failNext(new IllegalStateException("SMTP unavailable"));
        controlledMailSender.failNext(new IllegalStateException("SMTP unavailable"));
        controlledMailSender.failNext(new IllegalStateException("SMTP unavailable"));

        NotificationRequest request = new NotificationRequest(
                "Andrija",
                "andrija@example.com",
                Map.of("name", "Andrija", "activationLink", "https://example.com/activate/123")
        );

        notificationDeliveryService.handleIncomingMessage(request, NotificationType.ROUTING_KEY_EMPLOYEE_CREATED);

        waitForCondition(Duration.ofSeconds(5), () -> {
            List<NotificationDelivery> deliveries = notificationDeliveryRepository.findAll();
            return deliveries.size() == 1
                    && deliveries.getFirst().getStatus() == NotificationDeliveryStatus.RETRY_SCHEDULED;
        });
        NotificationDelivery delivery = singleDelivery();
        assertEquals(NotificationDeliveryStatus.RETRY_SCHEDULED, delivery.getStatus());
        assertEquals(1, delivery.getRetryCount());

        while (delivery.getStatus() == NotificationDeliveryStatus.RETRY_SCHEDULED) {
            makeRetryDue(delivery.getDeliveryId());
            notificationDeliveryService.processDueRetries();
            delivery = deliveryById(delivery.getDeliveryId());
        }

        assertEquals(NotificationDeliveryStatus.FAILED, delivery.getStatus());
        assertEquals(4, delivery.getRetryCount());
        assertNull(delivery.getNextAttemptAt());
        assertNull(delivery.getSentAt());
        assertTrue(delivery.getLastError().contains("IllegalStateException"));
        assertEquals(4, controlledMailSender.attemptCount());
        assertEquals(0, controlledMailSender.sentCount());
    }

    private void makeRetryDue(String deliveryId) {
        NotificationDelivery delivery = deliveryById(deliveryId);
        Instant dueNow = Instant.now().minusSeconds(1);
        delivery.setNextAttemptAt(dueNow);
        notificationDeliveryRepository.save(delivery);
        retryTaskQueue.schedule(deliveryId, dueNow);
    }

    private NotificationDelivery singleDelivery() {
        List<NotificationDelivery> deliveries = notificationDeliveryRepository.findAll();
        assertEquals(1, deliveries.size());
        return deliveries.get(0);
    }

    private NotificationDelivery deliveryById(String deliveryId) {
        return notificationDeliveryRepository.findByDeliveryId(deliveryId).orElseThrow();
    }

    private void waitForCondition(Duration timeout, java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for retry processing", ex);
            }
        }
        throw new AssertionError("Condition not met within timeout: " + timeout);
    }

    @TestConfiguration
    static class MailTestConfiguration {
        @Bean
        @Primary
        ControlledMailSender controlledMailSender() {
            return new ControlledMailSender();
        }
    }

    static class ControlledMailSender implements JavaMailSender {
        private final AtomicInteger attempts = new AtomicInteger();
        private final Deque<RuntimeException> failures = new ArrayDeque<>();
        private final CopyOnWriteArrayList<SimpleMailMessage> sentMessages = new CopyOnWriteArrayList<>();

        void failNext(RuntimeException ex) {
            failures.add(ex);
        }

        void reset() {
            attempts.set(0);
            failures.clear();
            sentMessages.clear();
        }

        int attemptCount() {
            return attempts.get();
        }

        int sentCount() {
            return sentMessages.size();
        }

        @Override
        public MimeMessage createMimeMessage() {
            return new MimeMessage(Session.getInstance(new Properties()));
        }

        @Override
        public MimeMessage createMimeMessage(InputStream contentStream) {
            try {
                return new MimeMessage(Session.getInstance(new Properties()), contentStream);
            } catch (Exception ex) {
                throw new MailParseException("Failed to parse mime message", ex);
            }
        }

        @Override
        public void send(MimeMessage mimeMessage) {
            throw new UnsupportedOperationException("MimeMessage sending is not used in these tests");
        }

        @Override
        public void send(MimeMessage... mimeMessages) {
            throw new UnsupportedOperationException("MimeMessage sending is not used in these tests");
        }

        @Override
        public void send(SimpleMailMessage simpleMessage) {
            attempts.incrementAndGet();
            RuntimeException failure = failures.pollFirst();
            if (failure != null) {
                throw failure;
            }
            sentMessages.add(new SimpleMailMessage(simpleMessage));
        }

        @Override
        public void send(SimpleMailMessage... simpleMessages) {
            for (SimpleMailMessage simpleMessage : simpleMessages) {
                send(simpleMessage);
            }
        }
    }
}
