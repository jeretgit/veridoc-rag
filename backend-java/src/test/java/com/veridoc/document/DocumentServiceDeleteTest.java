package com.veridoc.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;

import com.veridoc.document.entity.DocumentEntity;
import com.veridoc.document.repository.DocumentRepository;
import com.veridoc.document.service.DocumentService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentServiceDeleteTest {

    @TempDir
    Path tempUploadDir;

    private DocumentRepository repository;
    private DocumentService service;
    private Path testFile;

    @BeforeEach
    void setUp() throws IOException {
        repository = mock(DocumentRepository.class);

        // Create a fake PDF file on disk
        testFile = tempUploadDir.resolve("test-doc.pdf");
        Files.writeString(testFile, "%PDF-1.4 fake content");

        service = new DocumentService(repository, tempUploadDir.toString());
    }

    @Test
    void deleteRemovesDbRecordAndDiskFile() {
        UUID docId = UUID.randomUUID();

        DocumentEntity entity = new DocumentEntity();
        entity.setId(docId);
        entity.setFileName("test-doc.pdf");
        entity.setStoredPath(testFile.toString());
        entity.setFileSize(100L);
        entity.setContentType("application/pdf");
        entity.setStatus("UPLOADED");
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());

        when(repository.findById(docId)).thenReturn(Optional.of(entity));

        boolean result = service.delete(docId);

        assertTrue(result);
        verify(repository).delete(entity);
        verify(repository).flush();
        assertFalse(Files.exists(testFile), "Disk file should be deleted");
    }

    @Test
    void deleteReturnsFalseForNonExistentId() {
        UUID randomId = UUID.randomUUID();
        when(repository.findById(randomId)).thenReturn(Optional.empty());

        boolean result = service.delete(randomId);

        assertFalse(result);
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteSkipsFileOutsideUploadDir() {
        UUID docId = UUID.randomUUID();

        // Point stored_path somewhere outside the upload dir
        Path outside = Path.of("/tmp/malicious.pdf");

        DocumentEntity entity = new DocumentEntity();
        entity.setId(docId);
        entity.setFileName("malicious.pdf");
        entity.setStoredPath(outside.toString());
        entity.setFileSize(100L);
        entity.setContentType("application/pdf");
        entity.setStatus("UPLOADED");
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());

        when(repository.findById(docId)).thenReturn(Optional.of(entity));

        boolean result = service.delete(docId);

        // DB record deleted, but file outside upload dir is NOT touched
        assertTrue(result);
        verify(repository).delete(entity);
        // No exception thrown — file deletion simply skipped
    }
}
