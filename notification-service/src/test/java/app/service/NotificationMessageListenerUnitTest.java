package app.service;

import app.dto.NotificationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link NotificationMessageListener} listener delegation.
 */
@ExtendWith(MockitoExtension.class)
class NotificationMessageListenerUnitTest {

    @Mock
    private NotificationDeliveryService notificationDeliveryService;

    @InjectMocks
    private NotificationMessageListener notificationMessageListener;

    @Test
    void receiveMessageDelegatesPayloadAndRoutingKeyToDeliveryService() {
        NotificationRequest request = new NotificationRequest(
                "Dimitrije",
                "dimitrije.tomic99@gmail.com",
                Map.of("name", "Dimitrije", "activationLink", "https://example.com/activate")
        );

        notificationMessageListener.receiveMessage(request, "employee.created");

        verify(notificationDeliveryService).handleIncomingMessage(request, "employee.created");
    }

    @Test
    void receiveMessageDelegatesNullPayloadToDeliveryService() {
        notificationMessageListener.receiveMessage(null, "employee.created");

        verify(notificationDeliveryService).handleIncomingMessage(null, "employee.created");
    }
}
