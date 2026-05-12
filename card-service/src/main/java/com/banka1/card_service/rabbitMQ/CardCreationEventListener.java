package com.banka1.card_service.rabbitMQ;

import com.banka1.card_service.dto.card_creation.request.AutoCardCreationRequestDto;
import com.banka1.card_service.service.CardRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

/**
 * Consumes account-created events that should trigger automatic card creation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardCreationEventListener {

    private final CardRequestService cardRequestService;

    /**
     * Consumes a {@code card.create} event and delegates to the existing
     * automatic card creation flow.
     *
     * @param event incoming RabbitMQ payload
     * @param routingKey RabbitMQ routing key used for this delivery
     */
    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(value = "${card.rabbit.auto.queue:card.creation.auto.queue}",
                                    durable = "true"),
                    exchange = @Exchange(value = "${card.rabbit.auto.exchange:card.events}",
                                          type = "topic", durable = "true"),
                    key = "card.create.#"))
    public void handleCardCreateEvent(
            CardCreationEventDto event,
            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey
    ) {
        log.info(
                "Received automatic card creation event. accountNumber={}, clientId={}, routingKey={}",
                event.getAccountNumber(),
                event.getClientId(),
                routingKey
        );

        AutoCardCreationRequestDto request = new AutoCardCreationRequestDto();
        request.setClientId(event.getClientId());
        request.setAccountNumber(event.getAccountNumber());

        cardRequestService.createAutomaticCard(request);
    }
}
