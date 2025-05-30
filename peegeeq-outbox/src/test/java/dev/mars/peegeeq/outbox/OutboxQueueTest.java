package dev.mars.peegeeq.outbox;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
public class OutboxQueueTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private Vertx vertx;
    private OutboxQueue<JsonObject> queue;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();

        // Create connection options from TestContainer
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());

        // Create pool options
        PoolOptions poolOptions = new PoolOptions()
                .setMaxSize(5);

        // Create queue
        queue = new OutboxQueue<>(vertx, connectOptions, poolOptions, 
                new ObjectMapper(), "outbox_messages", JsonObject.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        queue.close()
            .onComplete(ar -> {
                vertx.close()
                    .onComplete(v -> latch.countDown());
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Failed to close resources");
    }

    @Test
    void testSendMessage() throws Exception {
        JsonObject message = new JsonObject().put("test", "value");
        CountDownLatch latch = new CountDownLatch(1);

        queue.send(message)
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    latch.countDown();
                } else {
                    fail("Failed to send message: " + ar.cause().getMessage());
                }
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Failed to send message");
    }

    @Test
    void testAcknowledgeMessage() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        queue.acknowledge("test-message-id")
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    latch.countDown();
                } else {
                    fail("Failed to acknowledge message: " + ar.cause().getMessage());
                }
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Failed to acknowledge message");
    }

    @Test
    void testCreateMessage() throws Exception {
        JsonObject payload = new JsonObject().put("test", "value");
        CountDownLatch latch = new CountDownLatch(1);

        vertx.runOnContext(v -> {
            try {
                var message = queue.createMessage(payload);

                assertNotNull(message);
                assertNotNull(message.getId());
                assertEquals(payload, message.getPayload());
                assertNotNull(message.getCreatedAt());
                assertNotNull(message.getHeaders());

                latch.countDown();
            } catch (Exception e) {
                fail("Failed to create message: " + e.getMessage());
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Failed to create message");
    }
}
