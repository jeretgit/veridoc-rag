package com.veridoc.chat.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Incoming question from the frontend.
 *
 * Field names are camelCase on the wire; the service translates them to the
 * snake_case contract expected by the Python service.
 */
public record ChatRequest(
        @NotBlank(message = "question must not be blank")
        @Size(max = 4000, message = "question must be at most 4000 characters")
        String question,

        List<UUID> documentIds,

        @Min(value = 1, message = "topK must be at least 1")
        @Max(value = 50, message = "topK must be at most 50")
        Integer topK,

        Double minScore,

        String model
) {
}
