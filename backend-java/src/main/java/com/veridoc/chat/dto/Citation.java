package com.veridoc.chat.dto;

import java.math.BigDecimal;

/**
 * A single page-level source reference backing an answer.
 */
public record Citation(
        String documentId,
        String fileName,
        Integer pageNumber,
        Integer chunkIndex,
        BigDecimal score,
        String snippet
) {
}
