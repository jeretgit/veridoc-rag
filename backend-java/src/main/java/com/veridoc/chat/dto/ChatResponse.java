package com.veridoc.chat.dto;

import java.util.List;

/**
 * Answer returned to the frontend, mapped from the Python service's
 * snake_case payload into camelCase.
 */
public record ChatResponse(
        String answer,
        boolean grounded,
        String modelUsed,
        List<Citation> citations
) {
}
