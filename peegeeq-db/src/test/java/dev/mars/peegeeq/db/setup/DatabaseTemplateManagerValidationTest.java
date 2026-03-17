package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for C1/H5 remediation: SQL injection prevention in DatabaseTemplateManager.
 * These tests verify parameter validation without requiring a database.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class DatabaseTemplateManagerValidationTest {

    private DatabaseTemplateManager manager;

    @BeforeEach
    void setUp(Vertx vertx) {
        manager = new DatabaseTemplateManager(vertx);
    }

    @Test
    void testRejectsSqlInjectionInDatabaseName() {
        assertThrows(IllegalArgumentException.class, () ->
            manager.createDatabaseFromTemplate(
                "localhost", 5432, "user", "pass",
                "test; DROP TABLE users;--", "template0", "UTF8", new HashMap<>()
            )
        );
    }

    @Test
    void testRejectsHyphenInDatabaseName() {
        assertThrows(IllegalArgumentException.class, () ->
            manager.createDatabaseFromTemplate(
                "localhost", 5432, "user", "pass",
                "my-database", "template0", "UTF8", new HashMap<>()
            )
        );
    }

    @Test
    void testRejectsSqlInjectionInTemplateName() {
        assertThrows(IllegalArgumentException.class, () ->
            manager.createDatabaseFromTemplate(
                "localhost", 5432, "user", "pass",
                "valid_db", "template0; DROP DATABASE postgres", "UTF8", new HashMap<>()
            )
        );
    }

    @Test
    void testRejectsInvalidEncoding() {
        assertThrows(IllegalArgumentException.class, () ->
            manager.createDatabaseFromTemplate(
                "localhost", 5432, "user", "pass",
                "valid_db", "template0", "UTF8'; DROP TABLE x;--", new HashMap<>()
            )
        );
    }

    @Test
    void testRejectsUnknownEncoding() {
        assertThrows(IllegalArgumentException.class, () ->
            manager.createDatabaseFromTemplate(
                "localhost", 5432, "user", "pass",
                "valid_db", "template0", "BOGUS_ENCODING", new HashMap<>()
            )
        );
    }

    @Test
    void testAcceptsNullTemplateAndEncoding() {
        // Should not throw on validation — will fail later on actual DB connection
        // but validation should pass
        assertDoesNotThrow(() -> {
            try {
                manager.createDatabaseFromTemplate(
                    "localhost", 5432, "user", "pass",
                    "valid_db", null, null, new HashMap<>()
                ).toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                // Connection failure is expected — we just want no IllegalArgumentException
                if (e.getCause() instanceof IllegalArgumentException) {
                    throw (IllegalArgumentException) e.getCause();
                }
            }
        });
    }

    @Test
    void testAcceptsValidIdentifiers() {
        // Should not throw on validation — will fail later on actual DB connection
        assertDoesNotThrow(() -> {
            try {
                manager.createDatabaseFromTemplate(
                    "localhost", 5432, "user", "pass",
                    "valid_db_name", "template0", "UTF8", new HashMap<>()
                ).toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                if (e.getCause() instanceof IllegalArgumentException) {
                    throw (IllegalArgumentException) e.getCause();
                }
            }
        });
    }

    @Test
    void testRejectsNullDatabaseName() {
        assertThrows(IllegalArgumentException.class, () ->
            manager.createDatabaseFromTemplate(
                "localhost", 5432, "user", "pass",
                null, "template0", "UTF8", new HashMap<>()
            )
        );
    }

    @Test
    void testRejectsReservedPrefix() {
        assertThrows(IllegalArgumentException.class, () ->
            manager.createDatabaseFromTemplate(
                "localhost", 5432, "user", "pass",
                "pg_something", "template0", "UTF8", new HashMap<>()
            )
        );
    }
}
