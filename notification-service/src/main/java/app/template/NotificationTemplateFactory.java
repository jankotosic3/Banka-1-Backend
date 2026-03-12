package app.template;

import app.dto.EmailTemplate;
import app.entities.NotificationType;

/**
 * Returns the email subject and body template for a notification type.
 */
public interface NotificationTemplateFactory {
    /**
     * Resolves the email template for a given notification type.
     *
     * @param type notification type that determines which template should be used
     * @return resolved email template containing subject and body
     */
    EmailTemplate resolve(NotificationType type);
}
