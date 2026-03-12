package app.service;

import app.dto.NotificationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

/**
 * RabbitMQ consumer entry point for notification messages.
 */
@Service
@RequiredArgsConstructor
public class NotificationMessageListener {
    /**
     * Delivery orchestration service handling persistence and retries.
     */
    private final NotificationDeliveryService notificationDeliveryService;

    /**
     * Consumes one RabbitMQ payload and delegates processing.
     *
     * @param request    incoming notification message
     * @param routingKey RabbitMQ routing key used for event type resolution
     */
    @RabbitListener(queues = "${notification.rabbit.queue}")
    public void receiveMessage(
            NotificationRequest request,
            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey
    ) {
        notificationDeliveryService.handleIncomingMessage(request, routingKey);
    }
}
