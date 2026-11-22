package com.veridoc.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.veridoc.document.controller.DocumentController;
import com.veridoc.document.dto.DocumentDto;
import com.veridoc.document.entity.DocumentEntity;
import com.veridoc.document.repository.DocumentRepository;
import com.veridoc.document.service.DocumentService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DocumentController.class)
@Import(DocumentUploadTest.TestConfig.class)
class DocumentUploadTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentRepository repository;

    @MockBean
    private DocumentService documentService;

    @TempDir
    Path tempDir;

    @TestConfiguration
    static class TestConfig {
        // No additional beans needed — DocumentService is mocked
    }

    // ========== Test: reject non-PDF file (wrong extension) ==========

    @Test
    void uploadRejectsNonPdfExtension() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "hello world".getBytes()
        );

        // Service would throw — but controller's own handler catches it
        when(documentService.upload(any())).thenThrow(
                new IllegalArgumentException("Only .pdf files are accepted")
        );

        mockMvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Only .pdf files are accepted"));
    }

    // ========== Test: reject file with .pdf extension but wrong magic bytes ==========

    @Test
    void uploadRejectsFakePdfMagicBytes() throws Exception {
        // Has .pdf extension but content is plain text (no %PDF- header)
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf", "not a real pdf".getBytes()
        );

        when(documentService.upload(any())).thenThrow(
                new IllegalArgumentException("File does not have a valid PDF header")
        );

        mockMvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("File does not have a valid PDF header"));
    }

    // ========== Test: successful upload returns 201 with metadata ==========

    @Test
    void uploadValidPdfReturns201() throws Exception {
        UUID docId = UUID.randomUUID();
        byte[] pdfContent = ("%PDF-1.4\n%fake content for test\n").getBytes();

        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", pdfContent
        );

        DocumentDto dto = new DocumentDto(
                docId, "report.pdf", (long) pdfContent.length, "application/pdf",
                "UPLOADED", 0, 0, null, OffsetDateTime.now(), OffsetDateTime.now()
        );

        when(documentService.upload(any())).thenReturn(dto);

        mockMvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.fileName").value("report.pdf"))
                .andExpect(jsonPath("$.status").value("UPLOADED"));
    }
}
