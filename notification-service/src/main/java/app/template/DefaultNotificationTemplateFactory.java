package app.template;

import app.dto.EmailTemplate;
import app.entities.NotificationType;
import org.springframework.stereotype.Component;

/**
 * Default mapping from event type to email subject and template.
 */
@Component
public final class DefaultNotificationTemplateFactory implements NotificationTemplateFactory {
    /**
     * Resolves email template based on notification type.
     *
     * @param type notification event type
     * @return email template containing subject and body
     */

    @Override
    public EmailTemplate resolve(NotificationType type) {
        return switch (type) {
            case EMPLOYEE_CREATED -> new EmailTemplate(
                    "Activation Email",
                    "Zdravo {{name}}, vas nalog je kreiran. "
                            + "Aktivirajte nalog klikom na link:\n{{activationLink}}"
            );
            case EMPLOYEE_PASSWORD_RESET -> new EmailTemplate(
                    "Password Reset Email",
                    "Zdravo {{name}}, resetujte lozinku klikom na link:\n{{resetLink}}"
            );
            case EMPLOYEE_ACCOUNT_DEACTIVATED -> new EmailTemplate(
                    "Account Deactivation Email",
                    "Zdravo {{name}}, vas nalog je deaktiviran."
            );
            case UNKNOWN -> throw new IllegalArgumentException(
                    "No template is defined for notification type: " + type
            );
        };
    }
}
