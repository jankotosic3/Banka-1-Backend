package com.banka1.stock_service.config;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configuration of HTTP clients used by {@code stock-service}
 * to communicate with internal and external services.
 *
 * <p>Both clients are configured with explicit connect and read timeouts so that a slow or
 * unresponsive downstream provider does not block the calling thread indefinitely.
 */
@Configuration
public class RestClientConfig {

    /**
     * Creates a dedicated {@link RestClient} for {@code exchange-service}.
     *
     * <p>Configured with a 5-second connect timeout and a 30-second response timeout.
     *
     * @param properties exchange-service integration configuration
     * @return configured RestClient with the exchange service base URL
     */
    @Bean
    public RestClient exchangeServiceRestClient(ExchangeServiceClientProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(buildRequestFactory(5, 30))
                .build();
    }

    /**
     * Creates a dedicated {@link RestClient} for the external market data provider.
     *
     * <p>Configured with a 5-second connect timeout and a 30-second response timeout.
     * Alpha Vantage responses can be slow on the free tier, so the read timeout is kept
     * generous but bounded to avoid indefinite thread blocking.
     *
     * @param properties external stock data provider configuration
     * @return configured RestClient with the market data base URL
     */
    @Bean
    public RestClient stockMarketDataRestClient(StockMarketDataProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(buildRequestFactory(5, 30))
                .build();
    }

    /**
     * Builds an {@link HttpComponentsClientHttpRequestFactory} with explicit connect and
     * response timeouts.
     *
     * @param connectTimeoutSeconds maximum time to wait for a TCP connection to be established
     * @param responseTimeoutSeconds maximum time to wait for the first response byte after sending the request
     * @return configured request factory
     */
    private HttpComponentsClientHttpRequestFactory buildRequestFactory(
            int connectTimeoutSeconds,
            int responseTimeoutSeconds
    ) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(connectTimeoutSeconds))
                .setResponseTimeout(Timeout.ofSeconds(responseTimeoutSeconds))
                .build();
        HttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
