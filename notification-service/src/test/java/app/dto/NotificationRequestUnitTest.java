package app.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationRequestUnitTest {

    @Test
    void constructorKeepsDefaultEmptyTemplateVariablesWhenNullIsProvided() {
        NotificationRequest request = new NotificationRequest("Andrija", "andrija@example.com", null);

        assertEquals("Andrija", request.getUsername());
        assertEquals("andrija@example.com", request.getUserEmail());
        assertNotNull(request.getTemplateVariables());
        assertTrue(request.getTemplateVariables().isEmpty());
    }

    @Test
    void constructorCopiesProvidedTemplateVariables() {
        NotificationRequest request = new NotificationRequest(
                "Andrija",
                "andrija@example.com",
                Map.of("activationLink", "https://example.com/activate/123")
        );

        assertEquals("https://example.com/activate/123", request.getTemplateVariables().get("activationLink"));
    }
}
