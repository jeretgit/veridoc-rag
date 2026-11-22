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

import com.veridoc.document.dto.DocumentDto;
import com.veridoc.document.dto.DocumentMapper;
import com.veridoc.document.entity.DocumentEntity;
import com.veridoc.document.repository.DocumentRepository;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final String PDF_MAGIC = "%PDF-";
    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024; // 20 MB

    private final DocumentRepository repository;
    private final Path uploadDir;

    public DocumentService(
            DocumentRepository repository,
            @Value("${upload.dir:./data/uploads}") String uploadDirPath
    ) {
        this.repository = repository;
        this.uploadDir = Paths.get(uploadDirPath);
    }

    @Transactional
    public DocumentDto upload(MultipartFile file) throws IOException {
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
        String storedName = docId + ".pdf";
        Path target = uploadDir.resolve(storedName).toAbsolutePath();

        // Safety: ensure target is inside uploadDir
        if (!target.startsWith(uploadDir.toAbsolutePath())) {
            throw new IllegalStateException("Resolved path escapes upload directory");
        }

        // Reset stream position (we read 5 bytes for magic check)
        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        // --- insert DB record ---
        DocumentEntity entity = new DocumentEntity();
        entity.setId(docId);
        entity.setFileName(originalName);
        entity.setStoredPath(target.toString());
        entity.setFileSize(file.getSize());
        entity.setContentType(file.getContentType() != null ? file.getContentType() : "application/pdf");
        entity.setStatus("UPLOADED");
        entity.setPageCount(0);
        entity.setChunkCount(0);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());

        repository.save(entity);

        return DocumentMapper.toDto(entity);
    }

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
                log.warn("Skipping file deletion: path {} is outside upload dir {}", filePath, uploadAbs);
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
