package app.service;

import app.dto.EmailTemplate;
import app.dto.NotificationRequest;
import app.dto.ResolvedEmail;
import app.entities.NotificationType;
import app.template.NotificationTemplateFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves rendered email content from request payload and notification templates.
 */
final class NotificationContentResolver {
    /** Error message when notification type is missing. */
    private static final String NOTIFICATION_TYPE_REQUIRED =
            "notificationType is required";

    /** Error message when notification payload is missing. */
    private static final String NOTIFICATION_PAYLOAD_REQUIRED =
            "Notification payload is required";

    /** Error message when user email is missing. */
    private static final String USER_EMAIL_REQUIRED =
            "userEmail is required";

    /** Error message when user email format is invalid. */
    private static final String USER_EMAIL_INVALID =
            "userEmail is not a valid email address";

    /** Minimal email format pattern: local@domain.tld */
    private static final java.util.regex.Pattern EMAIL_PATTERN =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Template variable key for username. */
    private static final String USERNAME_KEY = "username";

    /** Template variable key for name placeholder. */
    private static final String NAME_KEY = "name";

    /** Prefix used for template tokens. */
    private static final String TEMPLATE_TOKEN_PREFIX = "{{";

    /** Suffix used for template tokens. */
    private static final String TEMPLATE_TOKEN_SUFFIX = "}}";

    private NotificationContentResolver() {
    }

    /**
     * Resolves a notification payload into the final email recipient, subject, and body.
     *
     * @param request incoming notification payload
     * @param notificationType resolved notification type used for template selection
     * @param templateFactory template source for the notification type
     * @return fully rendered email content ready for delivery
     */
    static ResolvedEmail resolve(
            NotificationRequest request,
            NotificationType notificationType,
            NotificationTemplateFactory templateFactory
    ) {
        validateRequest(request);
        requireNotificationType(notificationType);

        EmailTemplate emailTemplate = templateFactory.resolve(notificationType);
        Map<String, String> variables = createVariables(request);
        String subject = renderTemplate(emailTemplate.subject(), variables);
        String body = renderTemplate(emailTemplate.bodyTemplate(), variables);
        return new ResolvedEmail(request.getUserEmail(), subject, body);
    }

    /**
     * Builds the template variable map used for placeholder substitution.
     *
     * @param request incoming notification payload
     * @return mutable map of template variables with username aliases applied
     */
    private static Map<String, String> createVariables(NotificationRequest request) {
        Map<String, String> variables = request.getTemplateVariables() == null
                ? new HashMap<>()
                : new HashMap<>(request.getTemplateVariables());
        addUsernameAliases(request.getUsername(), variables);
        return variables;
    }

    /**
     * Adds standard username aliases when a username is present in the payload.
     *
     * @param username source username from the payload
     * @param variables mutable template variables map to enrich
     */
    private static void addUsernameAliases(String username, Map<String, String> variables) {
        if (username == null || username.isBlank()) {
            return;
        }
        variables.putIfAbsent(USERNAME_KEY, username);
        variables.putIfAbsent(NAME_KEY, username);
    }

    /**
     * Replaces template tokens such as {@code {{name}}} with provided values.
     *
     * @param template template text containing placeholders
     * @param userData variables used for substitution
     * @return rendered template text
     */
    private static String renderTemplate(String template, Map<String, String> userData) {
        if (userData == null || userData.isEmpty()) {
            return template;
        }
        String content = template;
        for (Map.Entry<String, String> param : userData.entrySet()) {
            content = content.replace(tokenFor(param.getKey()), escapeHtml(String.valueOf(param.getValue())));
        }
        return content;
    }

    /**
     * Escapes HTML special characters in a template variable value to prevent
     * injection when email clients render HTML content.
     *
     * @param value raw template variable value
     * @return HTML-escaped value
     */
    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Formats a placeholder key into the token syntax used by templates.
     *
     * @param key template variable name
     * @return token in {@code {{key}}} format
     */
    private static String tokenFor(String key) {
        return TEMPLATE_TOKEN_PREFIX + key + TEMPLATE_TOKEN_SUFFIX;
    }

    /**
     * Validates that a notification type was resolved before rendering.
     *
     * @param notificationType notification type to validate
     */
    private static void requireNotificationType(NotificationType notificationType) {
        if (notificationType == null) {
            throw new IllegalArgumentException(NOTIFICATION_TYPE_REQUIRED);
        }
    }

    /**
     * Validates the minimum payload fields required for email rendering.
     *
     * @param request incoming notification payload
     */
    private static void validateRequest(NotificationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(NOTIFICATION_PAYLOAD_REQUIRED);
        }
        if (request.getUserEmail() == null || request.getUserEmail().isBlank()) {
            throw new IllegalArgumentException(USER_EMAIL_REQUIRED);
        }
        if (!EMAIL_PATTERN.matcher(request.getUserEmail()).matches()) {
            throw new IllegalArgumentException(USER_EMAIL_INVALID);
        }
    }
}
