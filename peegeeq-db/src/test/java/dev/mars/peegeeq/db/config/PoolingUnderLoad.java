package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.db.client.PgClient;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.transaction.PgTransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
public class PoolingUnderLoad {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PgClientFactory clientFactory;
    private PgClient pgClient;
    private PgTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        clientFactory = new PgClientFactory();

        // Create connection config from TestContainer
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        // Create client
        pgClient = clientFactory.createClient("test-client", connectionConfig);

        // Create transaction manager
        transactionManager = new PgTransactionManager(pgClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientFactory != null) {
            clientFactory.close();
        }
    }

    @Test
    void testTransactionErrorHandling() {
        // Test that SQL errors are properly propagated
        assertThrows(SQLException.class, () -> {
            transactionManager.executeInTransaction(transaction -> {
                try (Statement stmt = transaction.getConnection().createStatement()) {
                    // Execute invalid SQL
                    stmt.execute("SELECT * FROM non_existent_table");
                }
            });
        });
    }


    @Test
    void testConnectionPoolingUnderLoad() throws Exception {
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    pgClient.withConnection(connection -> {
                        try {
                            // Simulate some work
                            Thread.sleep(100);
                            try (Statement stmt = connection.createStatement();
                                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                                assertTrue(rs.next());
                                assertEquals(1, rs.getInt(1));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new SQLException("Thread interrupted", e);
                        }
                    });
                } catch (Exception e) {
                    fail("Exception in thread: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Not all threads completed in time");
        executor.shutdown();
    }


}
