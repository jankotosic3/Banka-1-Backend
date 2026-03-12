package app.dto;

/**
 * Immutable rendered email content ready for SMTP sending.
 *
 * @param recipientEmail target recipient email address
 * @param subject        rendered email subject
 * @param body           rendered email body
 */
public record ResolvedEmail(
        String recipientEmail,
        String subject,
        String body
) { }
