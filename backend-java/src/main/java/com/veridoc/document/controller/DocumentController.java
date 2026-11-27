package com.veridoc.document.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.veridoc.document.dto.DocumentDto;
import com.veridoc.document.service.DocumentService;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Upload a PDF and immediately ingest it.
     *
     * Returns 201 with the document metadata; status is READY on success or
     * FAILED when ingestion could not complete (the upload itself is kept).
     */
    @PostMapping
    public ResponseEntity<DocumentDto> upload(@RequestParam("file") MultipartFile file)
            throws Exception {
        DocumentDto dto = documentService.upload(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping
    public ResponseEntity<List<DocumentDto>> list() {
        return ResponseEntity.ok(documentService.listAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentDto> getById(@PathVariable UUID id) {
        DocumentDto dto = documentService.getById(id);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    /**
     * Re-run ingestion for an existing document, optionally with custom
     * chunk parameters.
     */
    @PostMapping("/{id}/ingest")
    public ResponseEntity<DocumentDto> reIngest(
            @PathVariable UUID id,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "chunkOverlap", required = false) Integer chunkOverlap
    ) {
        DocumentDto dto = documentService.retryIngest(id, chunkSize, chunkOverlap);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        boolean deleted = documentService.delete(id);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("BAD_REQUEST", e.getMessage()));
    }

    public record ErrorResponse(String error, String message) {}
}
