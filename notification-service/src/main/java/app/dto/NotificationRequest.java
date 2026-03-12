package app.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Message payload consumed from RabbitMQ.
 *
 * <p>Contract used by this service:
 * <ul>
 *     <li>{@code username} is optional and can be used by templates</li>
 *     <li>{@code userEmail} is required for email delivery</li>
 *     <li>{@code templateVariables} carries template values
 *     including optional {@code subject}/{@code body}</li>
 *     <li>notification type is resolved from RabbitMQ routing key, not from payload</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
public class NotificationRequest implements Serializable {
    /**
     * Human-readable username used in template substitutions.
     */
    @Schema(description = "User display name", example = "Mila")
    private String username;

    /**
     * Destination email address for the notification.
     */
    @JsonAlias({"email", "recipientEmail"})
    @Schema(description = "Target email address", example = "employee@example.com")
    private String userEmail;

    /**
     * Dynamic template values; may include {@code subject},
     * {@code body}, or domain-specific placeholders.
     */
    @JsonAlias({"params", "data", "payload", "userData"})
    @Schema(description = "Template and message variables")
    private Map<String, String> templateVariables = new HashMap<>();

    /**
     * Convenience constructor for tests and manual object creation.
     *
     * @param reqName          display username
     * @param reqEmail         recipient email
     * @param templateVar key-value template placeholders
     */
    public NotificationRequest(String reqName, String reqEmail, Map<String, String> templateVar) {
        this.username = reqName;
        this.userEmail = reqEmail;
        if (templateVar != null) {
            this.templateVariables = new HashMap<>(templateVar);
        }
    }
}
