package com.veridoc.health;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final DataSource dataSource;

    @Value("${ai.service.url:http://ai-service-python:8000}")
    private String aiServiceUrl;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backend", "up");

        // Check database connectivity
        result.put("database", checkDatabase());

        // Check AI service
        Map<String, Object> aiStatus = checkAiService();
        result.put("aiService", aiStatus.get("status"));
        result.put("llmProvider", aiStatus.get("llm_provider"));

        return result;
    }

    private String checkDatabase() {
        try (var conn = dataSource.getConnection()) {
            return conn.isValid(3) ? "up" : "down";
        } catch (Exception e) {
            return "down: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> checkAiService() {
        try {
            RestTemplate restTemplate = new RestTemplate();
            var response = restTemplate.getForEntity(
                    aiServiceUrl + "/health",
                    Map.class
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", response.getBody().getOrDefault("status", "unknown"));
                body.put("llm_provider", response.getBody().getOrDefault("llm_provider", "unknown"));
                return body;
            }
        } catch (Exception e) {
            Map<String, Object> down = new LinkedHashMap<>();
            down.put("status", "down");
            down.put("llm_provider", "unknown");
            return down;
        }
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("status", "unknown");
        unknown.put("llm_provider", "unknown");
        return unknown;
    }
}
