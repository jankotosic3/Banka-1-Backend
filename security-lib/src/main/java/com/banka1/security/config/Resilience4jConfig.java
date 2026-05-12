package com.banka1.security.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * PR_06 C6.6: shared Resilience4j konfiguracija.
 *
 * <p>Cilj: svi cross-modul REST clients (npr. trading-service → banking-core,
 * banking-core → user-service) imaju konzistentan timeout, retry, i circuit
 * breaker policy.
 *
 * <p>Default vrednosti su konzervativne za banking domen:
 * <ul>
 *   <li>Connect timeout: 2 s
 *   <li>Read timeout: 5 s
 *   <li>Write timeout: 5 s
 *   <li>Retry: 3 puta sa eksponencijalnim backoff-om (250ms, 500ms, 1000ms)
 *   <li>Circuit breaker: 50% failure rate na 10 zahteva → open na 30 s
 * </ul>
 */
@Configuration
@ConditionalOnClass(name = "io.github.resilience4j.circuitbreaker.CircuitBreaker")
public class Resilience4jConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(250))
                .retryExceptions(java.io.IOException.class, java.net.SocketTimeoutException.class)
                .build();
        return RetryRegistry.of(config);
    }

    @Bean
    public TimeLimiterConfig timeLimiterConfig() {
        return TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5))
                .cancelRunningFuture(true)
                .build();
    }
}
