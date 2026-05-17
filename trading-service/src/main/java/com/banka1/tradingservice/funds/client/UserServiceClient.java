package com.banka1.tradingservice.funds.client;

import com.banka1.tradingservice.security.JWTService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserServiceClient {

    private final JWTService jwtService;

    @Value("${services.employee.url:http://user-service:8081}")
    private String baseUrl;

    private WebClient webClient(String bearerToken) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public EmployeeInfo getEmployee(Long id) {
        return webClient(jwtService.generateJwtToken()).get()
                .uri("/employees/{id}", id)
                .retrieve()
                .bodyToMono(EmployeeInfo.class)
                .timeout(Duration.ofSeconds(5))
                .block();
    }

    public record EmployeeInfo(Long id, String ime, String prezime, String email, String pozicija) {}
}
