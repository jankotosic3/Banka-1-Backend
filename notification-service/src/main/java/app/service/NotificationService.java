package app.service;

import app.dto.NotificationRequest;
import app.dto.ResolvedEmail;
import app.entities.NotificationType;
import app.template.NotificationTemplateFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Main service for rendering and sending email notifications.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {
    /** SMTP sender abstraction used to dispatch the final email. */
    private final JavaMailSender mailSender;

    /** Template provider used to resolve subject/body by notification type. */
    private final NotificationTemplateFactory templateFactory;

    /** Configured sender address; if blank the framework default is used. */
    @Value("${spring.mail.username:}")
    private String fromAddress = "";

    /**
     * Resolves the final rendered email content from request variables and templates.
     *
     * @param request payload from the broker containing recipient and template values
     * @param type event type used to resolve the concrete email template
     * @return resolved email payload for SMTP delivery
     */
    public ResolvedEmail resolveEmailContent(NotificationRequest request, NotificationType type) {
        return NotificationContentResolver.resolve(request, type, templateFactory);
    }

    /**
     * Sends a rendered email message.
     *
     * @param to recipient email address
     * @param subject email subject line
     * @param content rendered email body
     */
    public void sendEmail(String to, String subject, String content) {
        sendMailMessage(to, subject, content);
    }

    /**
     * Builds and sends a {@link SimpleMailMessage}.
     *
     * @param to recipient email address
     * @param subject email subject line
     * @param content rendered email body
     */
    private void sendMailMessage(String to, String subject, String content) {
        // Mutable Spring message object filled before SMTP dispatch.
        SimpleMailMessage message = new SimpleMailMessage();
        if (hasCustomFromAddress()) {
            message.setFrom(fromAddress);
        }
        message.setTo(to);
        message.setSubject(subject);
        message.setText(content);
        mailSender.send(message);
    }

    /**
     * Checks whether a custom sender address was configured.
     *
     * @return {@code true} when the configured sender should be applied
     */
    private boolean hasCustomFromAddress() {
        return fromAddress != null && !fromAddress.isBlank();
    }
}
