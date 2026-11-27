package com.veridoc.document;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.veridoc.document.client.AiIngestException;
import com.veridoc.document.client.AiServiceClient;
import com.veridoc.document.dto.DocumentDto;
import com.veridoc.document.entity.DocumentEntity;
import com.veridoc.document.repository.DocumentRepository;
import com.veridoc.document.service.DocumentService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Covers the PROCESSING -> READY / FAILED status transitions when the AI
 * service is mocked out.
 */
class DocumentIngestFlowTest {

    private DocumentRepository repository;
    private AiServiceClient aiServiceClient;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        aiServiceClient = mock(AiServiceClient.class);
        // save() returns the entity it was handed
        when(repository.save(any(DocumentEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new DocumentService(repository, aiServiceClient, "/tmp/uploads");
    }

    private DocumentEntity newEntity(UUID id) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(id);
        entity.setFileName("doc.pdf");
        entity.setStoredPath("/tmp/uploads/" + id + ".pdf");
        entity.setFileSize(1024L);
        entity.setContentType("application/pdf");
        entity.setStatus(DocumentService.STATUS_UPLOADED);
        entity.setPageCount(0);
        entity.setChunkCount(0);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        return entity;
    }

    @Test
    void successfulIngestSetsStatusReadyWithCounts() {
        UUID docId = UUID.randomUUID();
        DocumentEntity entity = newEntity(docId);

        when(aiServiceClient.ingest(eq(docId.toString()), any(), isNull(), isNull()))
                .thenReturn(new AiServiceClient.IngestResult(12, 34));
        when(repository.findById(docId)).thenReturn(Optional.of(entity));

        DocumentDto dto = service.retryIngest(docId, null, null);

        assertNotNull(dto);
        assertEquals(DocumentService.STATUS_READY, dto.status());
        assertEquals(12, dto.pageCount());
        assertEquals(34, dto.chunkCount());
        assertNull(dto.errorMessage());
    }

    @Test
    void failedIngestSetsStatusFailedAndKeepsErrorMessage() {
        UUID docId = UUID.randomUUID();
        DocumentEntity entity = newEntity(docId);

        when(aiServiceClient.ingest(eq(docId.toString()), any(), isNull(), isNull()))
                .thenThrow(new AiIngestException("AI service call failed: timeout"));
        when(repository.findById(docId)).thenReturn(Optional.of(entity));

        DocumentDto dto = service.retryIngest(docId, null, null);

        assertNotNull(dto);
        assertEquals(DocumentService.STATUS_FAILED, dto.status());
        assertNotNull(dto.errorMessage());
        assertTrue(dto.errorMessage().contains("timeout"));
        // The document record itself must survive the failure.
        verify(repository, never()).delete(any());
    }

    @Test
    void failedIngestOverwritesPreviousSuccessCountsWithFailureState() {
        UUID docId = UUID.randomUUID();
        DocumentEntity entity = newEntity(docId);
        entity.setStatus(DocumentService.STATUS_READY);
        entity.setPageCount(5);
        entity.setChunkCount(9);

        when(aiServiceClient.ingest(any(), any(), any(), any()))
                .thenThrow(new AiIngestException("connection refused"));
        when(repository.findById(docId)).thenReturn(Optional.of(entity));

        DocumentDto dto = service.retryIngest(docId, 300, 50);

        assertEquals(DocumentService.STATUS_FAILED, dto.status());
        assertTrue(dto.errorMessage().contains("connection refused"));
    }

    @Test
    void retryPassesCustomChunkParametersToAiService() {
        UUID docId = UUID.randomUUID();
        DocumentEntity entity = newEntity(docId);

        when(aiServiceClient.ingest(any(), any(), eq(300), eq(50)))
                .thenReturn(new AiServiceClient.IngestResult(3, 7));
        when(repository.findById(docId)).thenReturn(Optional.of(entity));

        DocumentDto dto = service.retryIngest(docId, 300, 50);

        assertEquals(DocumentService.STATUS_READY, dto.status());
        verify(aiServiceClient).ingest(any(), any(), eq(300), eq(50));
    }

    @Test
    void retryRejectsOverlapNotSmallerThanChunkSize() {
        UUID docId = UUID.randomUUID();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.retryIngest(docId, 100, 100)
        );
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    void retryReturnsNullWhenDocumentMissing() {
        UUID docId = UUID.randomUUID();
        when(repository.findById(docId)).thenReturn(Optional.empty());

        assertNull(service.retryIngest(docId, null, null));
    }
}
