package app.service;

import app.dto.NotificationRequest;
import app.dto.ResolvedEmail;
import app.dto.EmailTemplate;
import app.entities.NotificationType;
import app.template.NotificationTemplateFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;

import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link NotificationService}.
 *
 * <p>These tests use a mocked {@link JavaMailSender}; no real email is sent.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceUnitTest {

    /**
     * Test email used in assertions and sample payloads.
     */
    private static final String TEST_EMAIL = "dimitrije.tomic99@gmail.com";

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private NotificationTemplateFactory templateFactory;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    void sendEmailBuildsExpectedEmailMessage() {
        notificationService.sendEmail(
                TEST_EMAIL,
                "Activation Email",
                "Zdravo Dimitrije, vas nalog je kreiran. Aktivirajte nalog klikom na link:\nhttps://example.com/activate/123"
        );

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());

        SimpleMailMessage sent = messageCaptor.getValue();
        assertArrayEquals(new String[]{TEST_EMAIL}, sent.getTo());
        assertEquals("Activation Email", sent.getSubject());
        assertEquals("Zdravo Dimitrije, vas nalog je kreiran. Aktivirajte nalog klikom na link:\nhttps://example.com/activate/123", sent.getText());
    }

    @Test
    void resolveEmailContentRendersTemplateForProvidedNotificationType() {
        NotificationRequest request = new NotificationRequest(
                "Dimitrije",
                TEST_EMAIL,
                Map.of("name", "Dimitrije", "resetLink", "https://example.com/reset/123")
        );
        when(templateFactory.resolve(NotificationType.EMPLOYEE_PASSWORD_RESET)).thenReturn(
                new EmailTemplate(
                        "Password Reset Email",
                        "Zdravo {{name}}, resetujte lozinku klikom na link:\n{{resetLink}}"
                )
        );

        ResolvedEmail resolved = notificationService.resolveEmailContent(
                request,
                NotificationType.EMPLOYEE_PASSWORD_RESET
        );

        assertEquals(TEST_EMAIL, resolved.recipientEmail());
        assertEquals("Password Reset Email", resolved.subject());
        assertEquals("Zdravo Dimitrije, resetujte lozinku klikom na link:\nhttps://example.com/reset/123", resolved.body());
    }

    @Test
    void resolveEmailContentFailsWhenUserEmailMissing() {
        NotificationRequest request = new NotificationRequest("Dimitrije", "", Map.of("name", "Dimitrije"));

        assertThrows(
                IllegalArgumentException.class,
                () -> notificationService.resolveEmailContent(request, NotificationType.EMPLOYEE_CREATED)
        );
    }

    @Test
    void resolveEmailContentFailsWhenUserEmailIsNull() {
        NotificationRequest request = new NotificationRequest("Dimitrije", null, Map.of("name", "Dimitrije"));

        assertThrows(
                IllegalArgumentException.class,
                () -> notificationService.resolveEmailContent(request, NotificationType.EMPLOYEE_CREATED)
        );
    }

    @Test
    void sendEmailWithConfiguredFromAddressSetsFromHeader() {
        ReflectionTestUtils.setField(notificationService, "fromAddress", "sender@example.com");

        notificationService.sendEmail(TEST_EMAIL, "Subject", "Body");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertEquals("sender@example.com", captor.getValue().getFrom());
    }

    @Test
    void sendEmailWithBlankFromAddressDoesNotSetFromHeader() {
        ReflectionTestUtils.setField(notificationService, "fromAddress", "");

        notificationService.sendEmail(TEST_EMAIL, "Subject", "Body");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertNull(captor.getValue().getFrom());
    }
}
