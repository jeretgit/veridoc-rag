package com.veridoc.common.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class HttpClientConfig {

    /**
     * RestTemplate dedicated to the AI service.
     *
     * Local embedding of a large PDF can take a while, so the read timeout is
     * generous (120s by default) instead of the usual few seconds.
     */
    @Bean("aiServiceRestTemplate")
    public RestTemplate aiServiceRestTemplate(
            RestTemplateBuilder builder,
            @Value("${ai.service.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${ai.service.read-timeout-ms:120000}") long readTimeoutMs
    ) {
        return builder
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }
}
