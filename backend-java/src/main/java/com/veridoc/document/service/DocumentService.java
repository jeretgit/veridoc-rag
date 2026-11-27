package com.veridoc.document.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.veridoc.document.client.AiIngestException;
import com.veridoc.document.client.AiServiceClient;
import com.veridoc.document.dto.DocumentDto;
import com.veridoc.document.dto.DocumentMapper;
import com.veridoc.document.entity.DocumentEntity;
import com.veridoc.document.repository.DocumentRepository;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final String PDF_MAGIC = "%PDF-";
    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024; // 20 MB

    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    private final DocumentRepository repository;
    private final AiServiceClient aiServiceClient;
    private final Path uploadDir;

    public DocumentService(
            DocumentRepository repository,
            AiServiceClient aiServiceClient,
            @Value("${upload.dir:./data/uploads}") String uploadDirPath
    ) {
        this.repository = repository;
        this.aiServiceClient = aiServiceClient;
        this.uploadDir = Paths.get(uploadDirPath);
    }

    /**
     * Store an uploaded PDF, then kick off ingestion.
     *
     * The upload itself is never rolled back if ingestion fails — the document
     * row is kept with status FAILED so the user can retry.
     */
    public DocumentDto upload(MultipartFile file) throws IOException {
        DocumentEntity entity = storeFile(file);
        return ingestExisting(entity, null, null);
    }

    // ------------------------------------------------------------------
    // Upload: validation + persistence
    // ------------------------------------------------------------------

    private DocumentEntity storeFile(MultipartFile file) throws IOException {
        // --- validation ---
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty or missing");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds 20MB limit");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Only .pdf files are accepted");
        }

        // Magic number check — must start with %PDF-
        byte[] header = new byte[5];
        try (var in = file.getInputStream()) {
            int read = in.read(header);
            if (read < 5 || !new String(header).equals(PDF_MAGIC)) {
                throw new IllegalArgumentException("File does not have a valid PDF header");
            }
        }

        // --- persist to disk ---
        Files.createDirectories(uploadDir);
        UUID docId = UUID.randomUUID();
        Path target = uploadDir.resolve(docId + ".pdf").toAbsolutePath();

        if (!target.startsWith(uploadDir.toAbsolutePath())) {
            throw new IllegalStateException("Resolved path escapes upload directory");
        }

        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        // --- insert DB record ---
        DocumentEntity entity = new DocumentEntity();
        entity.setId(docId);
        entity.setFileName(originalName);
        entity.setStoredPath(target.toString());
        entity.setFileSize(file.getSize());
        entity.setContentType(
                file.getContentType() != null ? file.getContentType() : "application/pdf"
        );
        entity.setStatus(STATUS_UPLOADED);
        entity.setPageCount(0);
        entity.setChunkCount(0);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());

        return repository.save(entity);
    }

    // ------------------------------------------------------------------
    // Ingestion (shared by upload and retry)
    // ------------------------------------------------------------------

    /**
     * Run the Python ingestion pipeline for an already stored document.
     *
     * Status transitions: PROCESSING -> READY (success) or PROCESSING -> FAILED.
     * Failures never roll back the stored document.
     */
    public DocumentDto ingestExisting(
            DocumentEntity entity,
            Integer chunkSize,
            Integer chunkOverlap
    ) {
        UUID docId = entity.getId();

        entity.setStatus(STATUS_PROCESSING);
        entity.setErrorMessage(null);
        entity.setUpdatedAt(OffsetDateTime.now());
        repository.save(entity);

        try {
            AiServiceClient.IngestResult result = aiServiceClient.ingest(
                    docId.toString(),
                    entity.getStoredPath(),
                    chunkSize,
                    chunkOverlap
            );

            entity.setStatus(STATUS_READY);
            entity.setPageCount(result.pageCount());
            entity.setChunkCount(result.chunkCount());
            entity.setErrorMessage(null);
            entity.setUpdatedAt(OffsetDateTime.now());
            repository.save(entity);

            log.info("Document {} is READY ({} pages, {} chunks)",
                    docId, result.pageCount(), result.chunkCount());

        } catch (AiIngestException e) {
            // Keep the row; record why it failed so it can be retried.
            entity.setStatus(STATUS_FAILED);
            entity.setErrorMessage(e.getMessage());
            entity.setUpdatedAt(OffsetDateTime.now());
            repository.save(entity);

            log.warn("Document {} is FAILED: {}", docId, e.getMessage());
        }

        return DocumentMapper.toDto(entity);
    }

    /**
     * Retry ingestion for an existing document, optionally with custom
     * chunk parameters. Returns null when the document does not exist.
     */
    @Transactional
    public DocumentDto retryIngest(UUID id, Integer chunkSize, Integer chunkOverlap) {
        if (chunkSize != null && chunkOverlap != null && chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "chunkOverlap must be smaller than chunkSize"
            );
        }

        return repository.findById(id)
                .map(entity -> ingestExisting(entity, chunkSize, chunkOverlap))
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // Queries & deletion
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<DocumentDto> listAll() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(DocumentMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentDto getById(UUID id) {
        return repository.findById(id)
                .map(DocumentMapper::toDto)
                .orElse(null);
    }

    @Transactional
    public boolean delete(UUID id) {
        return repository.findById(id).map(entity -> {
            // Delete DB record first (cascade removes chunks)
            repository.delete(entity);
            repository.flush();

            // Safe-delete the file on disk
            safeDeleteFile(entity.getStoredPath());
            return true;
        }).orElse(false);
    }

    private void safeDeleteFile(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return;
        }
        try {
            Path filePath = Paths.get(storedPath).toAbsolutePath();
            Path uploadAbs = uploadDir.toAbsolutePath();

            // Only delete if the file is inside UPLOAD_DIR
            if (!filePath.startsWith(uploadAbs)) {
                log.warn("Skipping file deletion: path {} is outside upload dir {}",
                        filePath, uploadAbs);
                return;
            }

            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.info("Deleted file: {}", filePath);
            }
        } catch (IOException e) {
            // Log only — do NOT roll back the DB transaction
            log.error("Failed to delete file {}: {}", storedPath, e.getMessage());
        }
    }
}
