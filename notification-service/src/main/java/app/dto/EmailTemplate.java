package app.dto;

/**
 * Email metadata and template text for one notification event.
 *
 * @param subject email subject line
 * @param bodyTemplate template body with placeholders such as {{name}}
 */
public record EmailTemplate(
        String subject,
        String bodyTemplate
) { }
