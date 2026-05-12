package com.banka1.card_service.rest_client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Internal client-service adapter used to resolve notification recipients by client ID.
 */
@Service
@RequiredArgsConstructor
public class ClientService {

    private final RestClient clientServiceClient;

    /**
     * If we need to send an EMAIL, we first need the CLIENT data necessary to call notification-service
     * To get that data, we call client-service, specifically the endpoint "/clients/customers/{id}".
     *
     * @param clientId internal client identifier
     * @return recipient details needed for email notification publishing
     */
    public ClientNotificationRecipientDto getNotificationRecipient(Long clientId) {
        return clientServiceClient.get()
                .uri("/clients/customers/{id}", clientId)
                .retrieve()
                .body(ClientNotificationRecipientDto.class);
    }
}
