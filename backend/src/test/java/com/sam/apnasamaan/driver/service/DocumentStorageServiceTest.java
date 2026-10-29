package com.sam.apnasamaan.driver.service;

import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.driver.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStorageServiceTest {

    @TempDir
    Path tempDir;

    private DocumentStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new DocumentStorageService(new StorageProperties(tempDir.toString()));
    }

    @Test
    void store_acceptsRealJpegPngAndPdf_andUsesRandomUuidFilenames() throws IOException {
        byte[] jpegBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46};
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        byte[] pdfBytes = "%PDF-1.7 sample driver licence content".getBytes(StandardCharsets.UTF_8);

        MockMultipartFile jpeg = new MockMultipartFile("file", "..\\folder\\dl photo.jpeg", "application/octet-stream", jpegBytes);
        MockMultipartFile png = new MockMultipartFile("file", "rc_scan.png", MediaType.IMAGE_PNG_VALUE, pngBytes);
        MockMultipartFile pdf = new MockMultipartFile("file", "licence.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);

        DocumentStorageService.StoredDocument storedJpeg = storageService.store(jpeg, "Driving licence");
        DocumentStorageService.StoredDocument storedPng = storageService.store(png, "Vehicle RC");
        DocumentStorageService.StoredDocument storedPdf = storageService.store(pdf, "Driving licence");

        assertThat(storedJpeg.contentType()).isEqualTo(MediaType.IMAGE_JPEG_VALUE);
        assertThat(storedJpeg.storedFilename()).endsWith(".jpg").doesNotContain("dl");
        assertThat(storedJpeg.originalFilename()).isEqualTo("dl photo.jpeg");

        assertThat(storedPng.contentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(storedPng.storedFilename()).endsWith(".png");

        assertThat(storedPdf.contentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(storedPdf.storedFilename()).endsWith(".pdf");

        Resource loaded = storageService.loadAsResource(storedPdf.storedFilename());
        assertThat(loaded.getContentAsByteArray()).isEqualTo(pdfBytes);

        storageService.deleteQuietly(storedPdf.storedFilename());
        assertThat(Files.exists(tempDir.resolve(storedPdf.storedFilename()))).isFalse();
    }

    @Test
    void store_rejectsSpoofedExtensionWithNonMatchingMagicBytes() {
        byte[] fakeExecutableOrScript = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile spoofed = new MockMultipartFile(
                "file", "licence.jpg", MediaType.IMAGE_JPEG_VALUE, fakeExecutableOrScript
        );

        assertThatThrownBy(() -> storageService.store(spoofed, "Driving licence"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("real JPEG, PNG, or PDF");
    }

    @Test
    void store_rejectsEmptyAndOversizedFiles_leavingZeroOrphanFiles() throws IOException {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[0]);
        assertThatThrownBy(() -> storageService.store(empty, "Vehicle RC"))
                .isInstanceOf(BadRequestException.class);

        byte[] over5Mb = new byte[(int) DocumentStorageService.MAX_DOCUMENT_SIZE_BYTES + 10];
        over5Mb[0] = 0x25; // %PDF-
        over5Mb[1] = 0x50;
        over5Mb[2] = 0x44;
        over5Mb[3] = 0x46;
        over5Mb[4] = 0x2D;
        MockMultipartFile huge = new MockMultipartFile("file", "huge.pdf", MediaType.APPLICATION_PDF_VALUE, over5Mb);

        assertThatThrownBy(() -> storageService.store(huge, "Vehicle RC"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("5 MB");

        try (var files = Files.list(tempDir)) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    void loadAsResource_blocksPathTraversalAttempts() {
        assertThatThrownBy(() -> storageService.loadAsResource("../secret.txt"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> storageService.loadAsResource("sub/file.pdf"))
                .isInstanceOf(BadRequestException.class);
    }
}
