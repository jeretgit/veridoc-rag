package com.veridoc.chat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.veridoc.chat.client.ChatAiClient;
import com.veridoc.chat.client.ChatServiceException;
import com.veridoc.chat.dto.ChatRequest;
import com.veridoc.chat.dto.ChatResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies the snake_case -> camelCase mapping and error handling of the
 * Python service client.
 */
class ChatAiClientTest {

    private RestTemplate restTemplate;
    private ChatAiClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new ChatAiClient(restTemplate, "http://ai-service-python:8000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsSnakeCasePayloadToCamelCaseDto() {
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("document_id", "11111111-1111-1111-1111-111111111111");
        citation.put("file_name", "handbook.pdf");
        citation.put("page_number", 3);
        citation.put("chunk_index", 7);
        citation.put("score", 0.8123);
        citation.put("snippet", "Employees receive 25 days of annual leave.");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answer", "You get 25 days [1].");
        body.put("grounded", true);
        body.put("model_used", "llama3.1:8b");
        body.put("citations", List.of(citation));

        when(restTemplate.postForEntity(any(String.class), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        ChatResponse response = client.query(
                new ChatRequest("How many days?", null, 5, 0.35, null));

        assertEquals("You get 25 days [1].", response.answer());
        assertTrue(response.grounded());
        assertEquals("llama3.1:8b", response.modelUsed());
        assertEquals(1, response.citations().size());

        var c = response.citations().get(0);
        assertEquals("11111111-1111-1111-1111-111111111111", c.documentId());
        assertEquals("handbook.pdf", c.fileName());
        assertEquals(3, c.pageNumber());
        assertEquals(7, c.chunkIndex());
        assertEquals(0, new BigDecimal("0.8123").compareTo(c.score()));
        assertTrue(c.snippet().contains("25 days"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusalPayloadMapsToEmptyCitations() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answer", "I could not find relevant information...");
        body.put("grounded", false);
        body.put("model_used", "llama3.1:8b");
        body.put("citations", List.of());

        when(restTemplate.postForEntity(any(String.class), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        ChatResponse response = client.query(
                new ChatRequest("Unrelated question", null, null, null, null));

        assertFalse(response.grounded());
        assertTrue(response.citations().isEmpty());
    }

    @Test
    void upstreamHttpErrorBecomesChatServiceException() {
        when(restTemplate.postForEntity(any(String.class), any(), eq(Map.class)))
                .thenThrow(new HttpClientErrorException(
                        HttpStatus.BAD_REQUEST, "Bad Request"));

        ChatServiceException ex = assertThrows(
                ChatServiceException.class,
                () -> client.query(new ChatRequest("q", null, null, null, null)));

        assertTrue(ex.getMessage().contains("400"));
    }

    @Test
    void unreachableServiceBecomesChatServiceException() {
        when(restTemplate.postForEntity(any(String.class), any(), eq(Map.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException(
                        "Read timed out"));

        ChatServiceException ex = assertThrows(
                ChatServiceException.class,
                () -> client.query(new ChatRequest("q", null, null, null, null)));

        assertTrue(ex.getMessage().contains("Read timed out"));
    }
}
