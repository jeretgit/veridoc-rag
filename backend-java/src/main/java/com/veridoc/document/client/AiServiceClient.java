package com.veridoc.document.client;

import java.util.LinkedHashMap;
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

/**
 * HTTP client for the Python AI service ingestion endpoint.
 */
@Component
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public AiServiceClient(
            @Qualifier("aiServiceRestTemplate") RestTemplate restTemplate,
            @Value("${ai.service.url:http://localhost:8000}") String baseUrl
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    /**
     * Result of an ingestion call.
     */
    public record IngestResult(int pageCount, int chunkCount) {}

    /**
     * Ask the Python service to parse, chunk, embed and persist a PDF.
     *
     * @throws AiIngestException when the service is unreachable or returns an error
     */
    public IngestResult ingest(
            String documentId,
            String filePath,
            Integer chunkSize,
            Integer chunkOverlap
    ) {
        String url = baseUrl + "/internal/ingest";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document_id", documentId);
        payload.put("file_path", filePath);
        if (chunkSize != null) {
            payload.put("chunk_size", chunkSize);
        }
        if (chunkOverlap != null) {
            payload.put("chunk_overlap", chunkOverlap);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            var response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(payload, headers),
                    Map.class
            );

            Map<?, ?> body = response.getBody();
            if (body == null) {
                throw new AiIngestException("AI service returned an empty response");
            }

            int pageCount = intValue(body.get("page_count"));
            int chunkCount = intValue(body.get("chunk_count"));
            log.info(
                    "Ingested document {} via AI service: {} pages, {} chunks",
                    documentId, pageCount, chunkCount
            );
            return new IngestResult(pageCount, chunkCount);

        } catch (RestClientResponseException e) {
            String detail = e.getResponseBodyAsString();
            log.warn(
                    "AI service rejected document {} (HTTP {}): {}",
                    documentId, e.getStatusCode().value(), detail
            );
            throw new AiIngestException(
                    "AI service returned HTTP " + e.getStatusCode().value()
                            + ": " + truncate(detail),
                    e
            );
        } catch (AiIngestException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI service call failed for document {}: {}", documentId, e.getMessage());
            throw new AiIngestException(
                    "AI service call failed: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage(),
                    e
            );
        }
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }
}
