package com.sam.apnasamaan.driver.service;

import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.common.exception.ResourceNotFoundException;
import com.sam.apnasamaan.driver.config.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Validates and stores private driver documents outside the static web root.
 * Inspects file magic bytes (header signatures) rather than trusting client-supplied
 * filenames or Content-Type headers.
 */
@Slf4j
@Service
public class DocumentStorageService {

    public static final long MAX_DOCUMENT_SIZE_BYTES = 5L * 1024L * 1024L; // 5 MB
    private static final int HEADER_PEEK_BYTES = 8;

    private final Path baseDirectory;

    public DocumentStorageService(StorageProperties storageProperties) {
        this.baseDirectory = Paths.get(storageProperties.driverDocumentsDir())
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(this.baseDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create driver document storage directory", ex);
        }
    }

    public StoredDocument store(MultipartFile file, String fieldLabel) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException(fieldLabel + " file is required and must not be empty");
        }
        if (file.getSize() > MAX_DOCUMENT_SIZE_BYTES) {
            throw new BadRequestException(fieldLabel + " exceeds the 5 MB maximum size limit");
        }

        String sanitizedOriginalName = sanitizeOriginalFilename(file.getOriginalFilename());

        try (PushbackInputStream stream = new PushbackInputStream(file.getInputStream(), HEADER_PEEK_BYTES)) {
            byte[] header = stream.readNBytes(HEADER_PEEK_BYTES);
            DetectedFormat format = detectFormatByMagicBytes(header, fieldLabel);
            stream.unread(header);

            String storedFilename = UUID.randomUUID() + format.extension();
            Path targetPath = resolveSafePath(storedFilename);

            long writtenBytes = copyBounded(stream, targetPath, fieldLabel);
            return new StoredDocument(
                    storedFilename,
                    sanitizedOriginalName,
                    format.contentType(),
                    writtenBytes
            );
        } catch (IOException ex) {
            log.error("Failed storing {} document on disk: {}", fieldLabel, ex.getMessage());
            throw new IllegalStateException("Could not store uploaded document on disk", ex);
        }
    }

    public Resource loadAsResource(String storedFilename) {
        Path filePath = resolveSafePath(storedFilename);
        if (!Files.isRegularFile(filePath) || !Files.isReadable(filePath)) {
            throw new ResourceNotFoundException("Stored document file is not available on disk");
        }
        return new PathResource(filePath);
    }

    public void deleteQuietly(String storedFilename) {
        if (storedFilename == null || storedFilename.isBlank()) {
            return;
        }
        try {
            Path filePath = resolveSafePath(storedFilename);
            Files.deleteIfExists(filePath);
        } catch (Exception ex) {
            log.warn("Could not delete old document file {}: {}", storedFilename, ex.getMessage());
        }
    }

    private long copyBounded(InputStream source, Path targetPath, String fieldLabel) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;

        try (OutputStream out = Files.newOutputStream(
                targetPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                // Enforce limit on actual byte stream too, in case multipart header reported a fake size
                if (total > MAX_DOCUMENT_SIZE_BYTES) {
                    out.close();
                    Files.deleteIfExists(targetPath);
                    throw new BadRequestException(fieldLabel + " exceeds the 5 MB maximum size limit");
                }
                out.write(buffer, 0, read);
            }
        } catch (BadRequestException ex) {
            Files.deleteIfExists(targetPath);
            throw ex;
        }

        if (total == 0) {
            Files.deleteIfExists(targetPath);
            throw new BadRequestException(fieldLabel + " file must not be empty");
        }
        return total;
    }

    private DetectedFormat detectFormatByMagicBytes(byte[] header, String fieldLabel) {
        // JPEG starts with: FF D8 FF
        if (header.length >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF) {
            return new DetectedFormat(MediaType.IMAGE_JPEG_VALUE, ".jpg");
        }

        // PNG starts with: 89 50 4E 47 0D 0A 1A 0A
        if (header.length >= 8
                && (header[0] & 0xFF) == 0x89
                && header[1] == 0x50
                && header[2] == 0x4E
                && header[3] == 0x47
                && header[4] == 0x0D
                && header[5] == 0x0A
                && header[6] == 0x1A
                && header[7] == 0x0A) {
            return new DetectedFormat(MediaType.IMAGE_PNG_VALUE, ".png");
        }

        // PDF starts with ASCII "%PDF-" (25 50 44 46 2D)
        if (header.length >= 5
                && header[0] == 0x25
                && header[1] == 0x50
                && header[2] == 0x44
                && header[3] == 0x46
                && header[4] == 0x2D) {
            return new DetectedFormat(MediaType.APPLICATION_PDF_VALUE, ".pdf");
        }

        throw new BadRequestException(
                fieldLabel + " must be a real JPEG, PNG, or PDF file (max 5 MB)"
        );
    }

    private Path resolveSafePath(String storedFilename) {
        if (storedFilename.contains("..") || storedFilename.contains("/") || storedFilename.contains("\\")) {
            throw new BadRequestException("Invalid document filename");
        }
        Path resolved = baseDirectory.resolve(storedFilename).normalize();
        if (!resolved.startsWith(baseDirectory)) {
            throw new BadRequestException("Document path escapes storage directory");
        }
        return resolved;
    }

    private String sanitizeOriginalFilename(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "document";
        }
        // Strip directory segments (both Unix '/' and Windows '\') and unsafe control/quote chars
        String leaf = rawName.replace('\\', '/');
        int slashIdx = leaf.lastIndexOf('/');
        if (slashIdx >= 0) {
            leaf = leaf.substring(slashIdx + 1);
        }
        String cleaned = leaf.replaceAll("[^a-zA-Z0-9._ -]", "_").trim();
        if (cleaned.isEmpty()) {
            return "document";
        }
        return cleaned.length() > 180 ? cleaned.substring(0, 180) : cleaned;
    }

    private record DetectedFormat(String contentType, String extension) {
    }

    public record StoredDocument(
            String storedFilename,
            String originalFilename,
            String contentType,
            long fileSizeBytes
    ) {
    }
}
