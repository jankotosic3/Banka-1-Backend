package app.integration;

import app.dto.NotificationRequest;
import app.entities.NotificationDelivery;
import app.entities.NotificationDeliveryStatus;
import app.entities.NotificationType;
import app.repository.NotificationDeliveryRepository;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test that exercises the real AMQP listener + SMTP send flow.
 *
 * <p>The test publishes a message to RabbitMQ, waits for the listener to process it,
 * verifies DB state transition to SUCCEEDED, and confirms an email was received by SMTP server.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "spring.datasource.url=jdbc:h2:mem:notification-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationFlowIntegrationTest {

    private static final String TEST_EMAIL = "andrija.volics22@gmail.com";

    private static final RabbitMQContainer RABBIT_MQ = new RabbitMQContainer("rabbitmq:3.13-management");

    private static final GreenMail GREEN_MAIL = new GreenMail(new ServerSetup(3025, "127.0.0.1", "smtp"));

    static {
        RABBIT_MQ.start();
        GREEN_MAIL.start();
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT_MQ::getHost);
        registry.add("spring.rabbitmq.port", RABBIT_MQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT_MQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT_MQ::getAdminPassword);
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> GREEN_MAIL.getSmtp().getPort());
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private NotificationDeliveryRepository notificationDeliveryRepository;

    @Value("${notification.rabbit.exchange}")
    private String exchangeName;

    @BeforeEach
    void clearState() throws Exception {
        notificationDeliveryRepository.deleteAll();
        GREEN_MAIL.purgeEmailFromAllMailboxes();
    }

    @AfterAll
    void tearDownMailServer() {
        GREEN_MAIL.stop();
        RABBIT_MQ.stop();
    }

    @Test
    void messageFromRabbitMqIsConsumedTypeResolvedAndEmailSent() throws Exception {
        NotificationRequest request = new NotificationRequest(
                "Andrija",
                TEST_EMAIL,
                Map.of(
                        "name", "Andrija",
                        "activationLink", "https://example.com/activate/123"
                )
        );

        rabbitTemplate.convertAndSend(exchangeName, "employee.created", request);

        waitForCondition(
                Duration.ofSeconds(15),
                () -> !notificationDeliveryRepository.findAllByStatus(NotificationDeliveryStatus.SUCCEEDED).isEmpty()
                        && GREEN_MAIL.getReceivedMessages().length == 1
        );

        List<NotificationDelivery> succeeded = notificationDeliveryRepository
                .findAllByStatus(NotificationDeliveryStatus.SUCCEEDED);
        assertEquals(1, succeeded.size());

        NotificationDelivery delivery = succeeded.get(0);
        assertEquals(NotificationType.EMPLOYEE_CREATED, delivery.getNotificationType());
        assertEquals(TEST_EMAIL, delivery.getRecipientEmail());
        assertEquals("Activation Email", delivery.getSubject());
        assertNotNull(delivery.getSentAt());
        assertEquals(0, delivery.getRetryCount());

        MimeMessage[] messages = GREEN_MAIL.getReceivedMessages();
        assertEquals(1, messages.length);
        assertEquals("Activation Email", messages[0].getSubject());
        assertEquals(TEST_EMAIL, messages[0].getAllRecipients()[0].toString());
    }

    @Test
    void passwordResetEventIsConsumedAndEmailSent() throws Exception {
        NotificationRequest request = new NotificationRequest(
                "Andrija",
                TEST_EMAIL,
                Map.of(
                        "name", "Andrija",
                        "resetLink", "https://example.com/reset/abc"
                )
        );

        rabbitTemplate.convertAndSend(exchangeName, "employee.password_reset", request);

        waitForCondition(
                Duration.ofSeconds(15),
                () -> !notificationDeliveryRepository.findAllByStatus(NotificationDeliveryStatus.SUCCEEDED).isEmpty()
                        && GREEN_MAIL.getReceivedMessages().length == 1
        );

        List<NotificationDelivery> succeeded = notificationDeliveryRepository
                .findAllByStatus(NotificationDeliveryStatus.SUCCEEDED);
        assertEquals(1, succeeded.size());

        NotificationDelivery delivery = succeeded.get(0);
        assertEquals(NotificationType.EMPLOYEE_PASSWORD_RESET, delivery.getNotificationType());
        assertEquals(TEST_EMAIL, delivery.getRecipientEmail());
        assertEquals("Password Reset Email", delivery.getSubject());
        assertNotNull(delivery.getSentAt());

        MimeMessage[] messages = GREEN_MAIL.getReceivedMessages();
        assertEquals(1, messages.length);
        assertEquals("Password Reset Email", messages[0].getSubject());
    }

    @Test
    void accountDeactivatedEventIsConsumedAndEmailSent() throws Exception {
        NotificationRequest request = new NotificationRequest(
                "Andrija",
                TEST_EMAIL,
                Map.of("name", "Andrija")
        );

        rabbitTemplate.convertAndSend(exchangeName, "employee.account_deactivated", request);

        waitForCondition(
                Duration.ofSeconds(15),
                () -> !notificationDeliveryRepository.findAllByStatus(NotificationDeliveryStatus.SUCCEEDED).isEmpty()
                        && GREEN_MAIL.getReceivedMessages().length == 1
        );

        List<NotificationDelivery> succeeded = notificationDeliveryRepository
                .findAllByStatus(NotificationDeliveryStatus.SUCCEEDED);
        assertEquals(1, succeeded.size());

        NotificationDelivery delivery = succeeded.get(0);
        assertEquals(NotificationType.EMPLOYEE_ACCOUNT_DEACTIVATED, delivery.getNotificationType());
        assertEquals(TEST_EMAIL, delivery.getRecipientEmail());
        assertEquals("Account Deactivation Email", delivery.getSubject());
        assertNotNull(delivery.getSentAt());

        MimeMessage[] messages = GREEN_MAIL.getReceivedMessages();
        assertEquals(1, messages.length);
        assertEquals("Account Deactivation Email", messages[0].getSubject());
    }

    @Test
    void unknownRoutingKeyIsDroppedAndPersistedAsFailed() {
        NotificationRequest request = new NotificationRequest(
                "Andrija",
                TEST_EMAIL,
                Map.of()
        );

        rabbitTemplate.convertAndSend(exchangeName, "employee.unknown_event", request);

        waitForCondition(
                Duration.ofSeconds(15),
                () -> !notificationDeliveryRepository.findAllByStatus(NotificationDeliveryStatus.FAILED).isEmpty()
        );

        List<NotificationDelivery> failed = notificationDeliveryRepository
                .findAllByStatus(NotificationDeliveryStatus.FAILED);
        assertEquals(1, failed.size());

        NotificationDelivery delivery = failed.get(0);
        assertEquals(NotificationType.UNKNOWN, delivery.getNotificationType());
        assertTrue(delivery.getLastError().contains("employee.unknown_event"));
        assertEquals(0, GREEN_MAIL.getReceivedMessages().length);
    }

    private void waitForCondition(Duration timeout, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for async processing");
            }
        }
        fail("Condition not met within timeout: " + timeout);
    }
}
