package app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger/OpenAPI documentation for Notification Service.
 *
 * <p>This service is event-driven. It does not expose business REST endpoints.
 * Swagger is kept for project documentation and for clear payload examples.
 */
@Configuration
public class SwaggerConfig {

    /**
     * Builds OpenAPI metadata and event payload schema.
     *
     * @return configured OpenAPI documentation instance
     */
    @Bean
    public OpenAPI notificationOpenAPI() {
        ObjectSchema requestSchema = new ObjectSchema();
        requestSchema.setDescription("RabbitMQ message payload consumed by notification service.");
        requestSchema.addProperty(
                "username",
                new StringSchema()
                        .description("User display name")
                        .example("Ana")
        );
        requestSchema.addProperty(
                "userEmail",
                new StringSchema()
                        .description("Target email")
                        .example("employee@example.com")
        );
        requestSchema.addProperty(
                "templateVariables",
                new MapSchema()
                        .description(
                                "Template placeholders used by event templates "
                                        + "(name, activationLink, resetLink, ...)."
                        )
                        .additionalProperties(new StringSchema())
                        .example(java.util.Map.of(
                                "name", "Ana",
                                "activationLink", "https://example.com/activate"
                        ))
        );
        requestSchema.setRequired(List.of("userEmail", "templateVariables"));

        return new OpenAPI()
                .info(new Info()
                        .title("Notification Service API Documentation")
                        .version("1.0")
                        .description("""
                                Event flow:
                                1. Service listens to RabbitMQ queue notification-service-queue.
                                2. The event is resolved from the routing key:
                                   employee.created,
                                   employee.password_reset,
                                   employee.account_deactivated.
                                3. Each consumed message is persisted as a
                                    new delivery with an internal UUID.
                                4. Subject/body are selected by the resolved event and
                                    rendered with templateVariables.
                                5. Failed sends are persisted and retried by a scheduler using
                                    PostgreSQL + in-memory priority queue.
                                """))
                .components(new Components()
                        .addSchemas("NotificationRequestEvent", requestSchema));
    }
}
