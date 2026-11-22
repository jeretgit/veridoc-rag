package com.veridoc.document.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentDto(
        UUID id,
        String fileName,
        Long fileSize,
        String contentType,
        String status,
        Integer pageCount,
        Integer chunkCount,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static DocumentDto from(Object entity) {
        // Delegated to the mapper to avoid tight coupling here
        throw new UnsupportedOperationException("Use DocumentMapper instead");
    }
}
