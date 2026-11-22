package com.veridoc.document.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.veridoc.document.entity.DocumentEntity;

public final class DocumentMapper {

    private DocumentMapper() {}

    public static DocumentDto toDto(DocumentEntity e) {
        return new DocumentDto(
                e.getId(),
                e.getFileName(),
                e.getFileSize(),
                e.getContentType(),
                e.getStatus(),
                e.getPageCount(),
                e.getChunkCount(),
                e.getErrorMessage(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
