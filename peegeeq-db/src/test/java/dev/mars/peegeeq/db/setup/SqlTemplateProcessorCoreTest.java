package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for SqlTemplateProcessor using TestContainers.
 *
 * <p>These tests are tagged as CORE because they:
 * <ul>
 *   <li>Run fast (each test completes in <1 second)</li>
 *   <li>Are isolated (each test focuses on a single method)</li>
 *   <li>Test one component at a time (SqlTemplateProcessor only)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 2.0
 */
@Tag(TestCategories.CORE)
public class SqlTemplateProcessorCoreTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SqlTemplateProcessorCoreTest.class);

    private PgConnectionManager connectionManager;
    private Pool reactivePool;
    private SqlTemplateProcessor sqlTemplateProcessor;

    @BeforeEach
    void setUp() throws Exception {
        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        reactivePool = connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        // Create SQL template processor
        sqlTemplateProcessor = new SqlTemplateProcessor();
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testSqlTemplateProcessorCreation() {
        assertNotNull(sqlTemplateProcessor);
    }

    @Test
    void testApplyTemplateWithSimpleTable(VertxTestContext testContext) {
        // Apply the test-simple-table template
        Map<String, String> parameters = new HashMap<>();
        parameters.put("TABLE_NAME", "test_simple_table");

        reactivePool.withConnection(connection ->
            sqlTemplateProcessor.applyTemplate(connection, "test-simple-table.sql", parameters)
        )
        // Verify table was created
        .compose(v -> reactivePool.withConnection(connection ->
            connection.preparedQuery("SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'test_simple_table')")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getBoolean(0))
        ))
        .compose(tableExists -> {
            assertTrue(tableExists);
            // Clean up
            return reactivePool.withConnection(connection ->
                connection.query("DROP TABLE IF EXISTS test_simple_table").execute()
                    .map(rowSet -> (Void) null)
            );
        })
        .onSuccess(v -> testContext.completeNow())
        .onFailure(testContext::failNow);
    }

    @Test
    void testApplyTemplateWithMultipleParameters(VertxTestContext testContext) {
        // Create a table with a different name using the same template
        Map<String, String> parameters = new HashMap<>();
        parameters.put("TABLE_NAME", "test_another_table");

        reactivePool.withConnection(connection ->
            sqlTemplateProcessor.applyTemplate(connection, "test-simple-table.sql", parameters)
        )
        // Verify table was created
        .compose(v -> reactivePool.withConnection(connection ->
            connection.preparedQuery("SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'test_another_table')")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getBoolean(0))
        ))
        .compose(tableExists -> {
            assertTrue(tableExists);
            // Clean up
            return reactivePool.withConnection(connection ->
                connection.query("DROP TABLE IF EXISTS test_another_table").execute()
                    .map(rowSet -> (Void) null)
            );
        })
        .onSuccess(v -> testContext.completeNow())
        .onFailure(testContext::failNow);
    }

    @Test
    void testApplyTemplateWithNonExistentTemplate(VertxTestContext testContext) {
        // Capture log output from SqlTemplateProcessor instead of printing it.
        // This keeps test output clean while still verifying the error was logged.
        ch.qos.logback.classic.Logger stpLogger = (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger(SqlTemplateProcessor.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        stpLogger.addAppender(listAppender);
        Level originalLevel = stpLogger.getLevel();
        stpLogger.setLevel(Level.ERROR);
        // Detach console appenders so the expected error doesn't pollute test output
        stpLogger.setAdditive(false);

        Map<String, String> parameters = new HashMap<>();

        reactivePool.withConnection(connection ->
            sqlTemplateProcessor.applyTemplate(connection, "non-existent-template.sql", parameters)
        )
        .onSuccess(v -> {
            stpLogger.detachAppender(listAppender);
            stpLogger.setAdditive(true);
            stpLogger.setLevel(originalLevel);
            listAppender.stop();
            testContext.failNow(new AssertionError("Should have thrown an exception"));
        })
        .onFailure(cause -> {
            try {
                // Verify the expected error was actually logged
                assertEquals(1, listAppender.list.size(), "Expected exactly one log event");
                ILoggingEvent event = listAppender.list.get(0);
                assertEquals(Level.ERROR, event.getLevel());
                assertTrue(event.getFormattedMessage().contains("Failed to load template"),
                    "Expected log message to contain 'Failed to load template'");
                testContext.completeNow();
            } catch (Throwable t) {
                testContext.failNow(t);
            } finally {
                stpLogger.detachAppender(listAppender);
                stpLogger.setAdditive(true);
                stpLogger.setLevel(originalLevel);
                listAppender.stop();
            }
        });
    }

    // ========================================
    // Template parameter injection prevention
    // ========================================

    @Test
    void testApplyTemplateRejectsParameterWithSingleQuote(VertxTestContext testContext) {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("TABLE_NAME", "test'; DROP TABLE users;--");

        reactivePool.withConnection(connection ->
            sqlTemplateProcessor.applyTemplate(connection, "test-simple-table.sql", parameters)
        )
        .onSuccess(v -> testContext.failNow(new AssertionError("Should have thrown IllegalArgumentException")))
        .onFailure(cause -> {
            if (cause instanceof IllegalArgumentException) {
                testContext.completeNow();
            } else {
                testContext.failNow(cause);
            }
        });
    }

    @Test
    void testApplyTemplateRejectsParameterWithSemicolon(VertxTestContext testContext) {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("TABLE_NAME", "test; DROP TABLE users");

        reactivePool.withConnection(connection ->
            sqlTemplateProcessor.applyTemplate(connection, "test-simple-table.sql", parameters)
        )
        .onSuccess(v -> testContext.failNow(new AssertionError("Should have thrown IllegalArgumentException")))
        .onFailure(cause -> {
            if (cause instanceof IllegalArgumentException) {
                testContext.completeNow();
            } else {
                testContext.failNow(cause);
            }
        });
    }

    @Test
    void testApplyTemplateRejectsParameterWithSqlComment(VertxTestContext testContext) {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("TABLE_NAME", "test--drop");

        reactivePool.withConnection(connection ->
            sqlTemplateProcessor.applyTemplate(connection, "test-simple-table.sql", parameters)
        )
        .onSuccess(v -> testContext.failNow(new AssertionError("Should have thrown IllegalArgumentException")))
        .onFailure(cause -> {
            if (cause instanceof IllegalArgumentException) {
                testContext.completeNow();
            } else {
                testContext.failNow(cause);
            }
        });
    }

    @Test
    void testApplyTemplateRejectsParameterWithBlockComment(VertxTestContext testContext) {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("TABLE_NAME", "test/*injection*/");

        reactivePool.withConnection(connection ->
            sqlTemplateProcessor.applyTemplate(connection, "test-simple-table.sql", parameters)
        )
        .onSuccess(v -> testContext.failNow(new AssertionError("Should have thrown IllegalArgumentException")))
        .onFailure(cause -> {
            if (cause instanceof IllegalArgumentException) {
                testContext.completeNow();
            } else {
                testContext.failNow(cause);
            }
        });
    }
}
