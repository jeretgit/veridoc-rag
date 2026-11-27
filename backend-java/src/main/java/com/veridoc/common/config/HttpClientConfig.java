package com.veridoc.common.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class HttpClientConfig {

    /**
     * RestTemplate dedicated to the AI service.
     *
     * Two things are deliberately configured here:
     *
     * 1. HTTP/1.1 is forced. Spring Boot 3.4 defaults to
     *    {@code JdkClientHttpRequestFactory}, whose client offers an h2c
     *    upgrade header on plain-HTTP requests. Uvicorn does not speak h2c and
     *    can end up dropping the POST body, so the Python ingest endpoint
     *    receives an empty payload. Pinning {@code Version.HTTP_1_1} removes
     *    the upgrade negotiation.
     *
     * 2. The read timeout is generous (120s by default) because locally
     *    embedding a large PDF is slow — the few-seconds default would abort
     *    ingestion mid-flight.
     */
    @Bean("aiServiceRestTemplate")
    public RestTemplate aiServiceRestTemplate(
            RestTemplateBuilder builder,
            @Value("${ai.service.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${ai.service.read-timeout-ms:120000}") long readTimeoutMs
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return builder.requestFactory(() -> requestFactory).build();
    }
}
