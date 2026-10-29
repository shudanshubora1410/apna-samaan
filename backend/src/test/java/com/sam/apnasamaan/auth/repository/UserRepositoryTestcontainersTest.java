package com.sam.apnasamaan.auth.repository;

import com.sam.apnasamaan.auth.entity.Role;
import com.sam.apnasamaan.auth.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against an isolated Testcontainers MySQL 8 container when Docker is available (e.g. GitHub Actions CI).
 * Automatically skipped on developer machines without Docker so local `mvn clean verify` never breaks.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class UserRepositoryTestcontainersTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("apna_samaan_tc")
            .withUsername("tc_user")
            .withPassword(java.util.UUID.randomUUID().toString());

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private UserRepository userRepository;

    @Test
    void saveAndFindByEmailIgnoreCase_worksAgainstMySqlContainer() {
        User saved = userRepository.save(User.builder()
                .name("Meera Iyer")
                .email("meera.iyer@example.com")
                .phone("9876543210")
                .passwordHash("$2a$10$abcdefghijklmnopqrstuv1234567890123456789012345678901")
                .role(Role.CUSTOMER)
                .enabled(true)
                .build());

        Optional<User> found = userRepository.findByEmailIgnoreCase("MEERA.IYER@EXAMPLE.COM");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }
}
