package com.banka1.card_service.rabbitMQ;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Client for publishing messages to RabbitMQ.
 * Encapsulates {@link RabbitTemplate} and the configured exchange name,
 * so that services do not depend on RabbitMQ infrastructure directly.
 *
 * Note: email notification sending requires {@code clientId} on the Card entity
 * to resolve the recipient email address. This will be wired up once the card
 * creation subissue is complete.
 */
@Component
@RequiredArgsConstructor
public class RabbitClient {

    /** Spring AMQP template that performs the actual message publishing. */
    private final RabbitTemplate rabbitTemplate;

    /** Name of the RabbitMQ exchange to which messages are published. */
    @Value("${rabbitmq.exchange}")
    private String exchange;

    /**
     * Publishes a card notification event to the configured exchange.
     *
     * @param routingKey routing key that determines which queue receives the message
     * @param payload message payload to publish
     */
    // TODO: uncomment and implement once clientId is available on Card entity (card creation subissue)
    // public void sendCardNotification(String routingKey, Object payload) {
    //     rabbitTemplate.convertAndSend(exchange, routingKey, payload);
    // }
}
