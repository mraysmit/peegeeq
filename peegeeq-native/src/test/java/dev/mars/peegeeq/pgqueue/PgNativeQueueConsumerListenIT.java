package dev.mars.peegeeq.pgqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PgNativeQueueConsumerListenIT {

    private static final String TOPIC = "it-listen-topic";

    @Container
    static final PostgreSQLContainer<?> postgres =
        PeeGeeQTestContainerFactory.createContainer(BASIC);

    private Vertx vertx;
    private VertxPoolAdapter adapter;
    private ObjectMapper mapper;

    @BeforeAll
    static void beforeAll() {
        // Initialize minimal schema for native queue tests
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SCHEMA_VERSION, NATIVE_QUEUE, DEAD_LETTER_QUEUE);
    }

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        adapter = new VertxPoolAdapter(vertx);

        // Build connection configs from container
        PgConnectionConfig connCfg = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .sslEnabled(false)
            .build();

        PgPoolConfig poolCfg = new PgPoolConfig.Builder()
            .maxSize(4)
            .maxWaitQueueSize(16)
            .shared(true)
            .build();

        Pool pool = adapter.createPool(connCfg, poolCfg);
        assertNotNull(pool);

        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    @AfterEach
    void tearDown() {
        if (adapter != null && adapter.getPool() != null) {
            try { adapter.getPool().close(); } catch (Exception ignore) {}
        }
        if (vertx != null) {
            try { vertx.close().toCompletionStage().toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join(); } catch (Exception ignore) {}
        }
    }

    @Test
    void listenNotify_onlyMode_deliversMessage() {
        // Arrange: consumer in LISTEN_NOTIFY_ONLY mode
        ConsumerConfig consumerConfig = ConsumerConfig.builder()
            .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
            .pollingInterval(Duration.ofSeconds(1)) // unused in LISTEN_ONLY but required by builder
            .consumerThreads(1)
            .batchSize(1)
            .build();

        PgNativeQueueConsumer<String> consumer = new PgNativeQueueConsumer<>(
            adapter, mapper, TOPIC, String.class, null, null, consumerConfig
        );

        CompletableFuture<Void> received = new CompletableFuture<>();

        consumer.subscribe(msg -> {
            try {
                assertEquals("hello", msg.getPayload());
                received.complete(null);
            } catch (AssertionError ae) {
                received.completeExceptionally(ae);
            }
            return received;
        });

        // Act: send a message
        PgNativeQueueProducer<String> producer = new PgNativeQueueProducer<>(
            adapter, mapper, TOPIC, String.class, null
        );
        producer.send("hello", Map.of()).join();

        // Assert: message is received within timeout
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(received::isDone);
        assertTrue(received.isDone());

        // Cleanup
        consumer.close();
        producer.close();
    }
}

