package com.veridoc.chat.client;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import com.veridoc.chat.dto.ChatRequest;
import com.veridoc.chat.dto.ChatResponse;
import com.veridoc.chat.dto.Citation;

/**
 * Calls the Python service's {@code POST /internal/query}.
 *
 * Reuses the {@code aiServiceRestTemplate} bean so HTTP/1.1 and the extended
 * read timeout apply here too — LLM generation on CPU can be slow.
 */
@Component
public class ChatAiClient {

    private static final Logger log = LoggerFactory.getLogger(ChatAiClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public ChatAiClient(
            @Qualifier("aiServiceRestTemplate") RestTemplate restTemplate,
            @Value("${ai.service.url:http://localhost:8000}") String baseUrl
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    @SuppressWarnings("unchecked")
    public ChatResponse query(ChatRequest request) {
        String url = baseUrl + "/internal/query";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", request.question());
        if (request.documentIds() != null && !request.documentIds().isEmpty()) {
            payload.put("document_ids",
                    request.documentIds().stream().map(Object::toString).toList());
        }
        if (request.topK() != null) {
            payload.put("top_k", request.topK());
        }
        if (request.minScore() != null) {
            payload.put("min_score", request.minScore());
        }
        if (request.model() != null && !request.model().isBlank()) {
            payload.put("model", request.model());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            var response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, headers), Map.class);

            Map<String, Object> body = response.getBody();
            if (body == null) {
                throw new ChatServiceException("AI service returned an empty response");
            }
            return mapResponse(body);

        } catch (RestClientResponseException e) {
            String detail = e.getResponseBodyAsString();
            log.warn("AI query rejected (HTTP {}): {}", e.getStatusCode().value(), detail);
            throw new ChatServiceException(
                    "AI service returned HTTP " + e.getStatusCode().value()
                            + ": " + truncate(detail),
                    e);
        } catch (ChatServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI query failed: {}", e.getMessage());
            throw new ChatServiceException(
                    "AI service call failed: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage(),
                    e);
        }
    }

    /** Map the snake_case payload onto the camelCase response DTO. */
    private ChatResponse mapResponse(Map<String, Object> body) {
        String answer = (String) body.getOrDefault("answer", "");
        boolean grounded = Boolean.TRUE.equals(body.get("grounded"));
        String modelUsed = (String) body.getOrDefault("model_used", "unknown");

        List<Citation> citations = new ArrayList<>();
        Object rawCitations = body.get("citations");
        if (rawCitations instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    citations.add(mapCitation(map));
                }
            }
        }

        return new ChatResponse(answer, grounded, modelUsed, citations);
    }

    private Citation mapCitation(Map<?, ?> map) {
        return new Citation(
                asString(map.get("document_id")),
                asString(map.get("file_name")),
                asInteger(map.get("page_number")),
                asInteger(map.get("chunk_index")),
                asDecimal(map.get("score")),
                asString(map.get("snippet"))
        );
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static BigDecimal asDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }
}
