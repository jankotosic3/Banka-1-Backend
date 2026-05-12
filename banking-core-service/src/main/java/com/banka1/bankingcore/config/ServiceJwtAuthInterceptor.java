package com.banka1.bankingcore.config;

import com.banka1.bankingcore.security.JWTService;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

/**
 * Cache-uje sluzbeni JWT (PR_14 C14.4) — refresh tek pred isteka da se ne placa
 * potpisivanje na svakom pozivu. Ako bi neko poslao 1000 req/s, naivni "regenerise
 * po pozivu" pristup bi pojeo CPU bez razloga.
 *
 * <p>Pattern preuzet iz order-service-a ({@code ServiceJwtAuthInterceptor}).
 */
class ServiceJwtAuthInterceptor implements ClientHttpRequestInterceptor {

    private static final long MIN_REFRESH_BUFFER_MILLIS = 1_000L;
    private static final long DEFAULT_REFRESH_BUFFER_MILLIS = 30_000L;

    private final JWTService jwtService;
    private final Clock clock;
    private final long tokenValidityMillis;
    private final long refreshBufferMillis;

    private volatile CachedToken cachedToken;

    ServiceJwtAuthInterceptor(JWTService jwtService, long tokenValidityMillis) {
        this(jwtService, tokenValidityMillis, Clock.systemUTC());
    }

    ServiceJwtAuthInterceptor(JWTService jwtService, long tokenValidityMillis, Clock clock) {
        this.jwtService = jwtService;
        this.clock = clock;
        this.tokenValidityMillis = tokenValidityMillis;
        this.refreshBufferMillis = Math.min(
                DEFAULT_REFRESH_BUFFER_MILLIS,
                Math.max(MIN_REFRESH_BUFFER_MILLIS, tokenValidityMillis / 10)
        );
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        request.getHeaders().setBearerAuth(resolveToken());
        return execution.execute(request, body);
    }

    private String resolveToken() {
        Instant now = clock.instant();
        CachedToken snapshot = cachedToken;
        if (snapshot != null && now.isBefore(snapshot.refreshAt())) {
            return snapshot.value();
        }

        synchronized (this) {
            now = clock.instant();
            snapshot = cachedToken;
            if (snapshot != null && now.isBefore(snapshot.refreshAt())) {
                return snapshot.value();
            }

            String token = jwtService.generateJwtToken();
            Instant expiresAt = now.plusMillis(Math.max(tokenValidityMillis, MIN_REFRESH_BUFFER_MILLIS));
            Instant refreshAt = expiresAt.minusMillis(refreshBufferMillis);
            cachedToken = new CachedToken(token, refreshAt);
            return token;
        }
    }

    private record CachedToken(String value, Instant refreshAt) {
    }
}
