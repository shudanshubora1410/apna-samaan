package com.sam.apnasamaan;

import com.sam.apnasamaan.auth.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Shared integration-test base that supplies runtime-generated test credentials,
 * an isolated temporary document storage directory, and cleanup on context shutdown.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BaseIntegrationTest.TestCleanupConfig.class)
public abstract class BaseIntegrationTest {

    protected static final String TEST_ADMIN_EMAIL =
            "integration.admin." + UUID.randomUUID().toString().substring(0, 8) + "@apnasamaan.test";
    protected static final String TEST_ADMIN_PASSWORD = "AdminTest#" + UUID.randomUUID();
    private static final String TEST_JWT_SECRET = UUID.randomUUID().toString() + UUID.randomUUID();
    protected static final Path TEST_STORAGE_DIR = createTempStorageDir();

    @MockitoBean
    protected JavaMailSender javaMailSender;

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("app.admin.email", () -> TEST_ADMIN_EMAIL);
        registry.add("app.admin.password", () -> TEST_ADMIN_PASSWORD);
        registry.add("spring.mail.password", () -> "test-smtp-configured-token");
        registry.add("app.storage.driver-documents-dir", () -> TEST_STORAGE_DIR.toString());
        // Keep integration tests fast, offline-safe, and deterministic even when local ../.env has live API keys
        registry.add("app.maps.osrm-base-url", () -> "http://127.0.0.1:1");
        registry.add("app.maps.nominatim-base-url", () -> "http://127.0.0.1:1");
        registry.add("app.maps.timeout-millis", () -> "500");
        registry.add("app.groq.api-key", () -> "");
        registry.add("app.razorpay.key-id", () -> "");
        registry.add("app.razorpay.key-secret", () -> "");
        registry.add("app.razorpay.webhook-secret", () -> "");
        registry.add("app.razorpay.simulation-enabled", () -> "true");
        registry.add("app.matching.scheduler-enabled", () -> "false");
        registry.add("app.mail.async-enabled", () -> "false");
    }

    private static Path createTempStorageDir() {
        try {
            return Files.createTempDirectory("apna-samaan-test-docs-");
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to create temp document dir for tests", ex);
        }
    }

    @TestConfiguration
    static class TestCleanupConfig {

        private final UserRepository userRepository;

        TestCleanupConfig(UserRepository userRepository) {
            this.userRepository = userRepository;
        }

        @PreDestroy
        void cleanUpEphemeralTestArtifacts() {
            userRepository.findByEmailIgnoreCase(TEST_ADMIN_EMAIL).ifPresent(userRepository::delete);
            FileSystemUtils.deleteRecursively(TEST_STORAGE_DIR.toFile());
        }
    }
}
