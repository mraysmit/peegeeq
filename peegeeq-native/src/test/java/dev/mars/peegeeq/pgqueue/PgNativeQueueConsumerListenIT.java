package dev.mars.peegeeq.pgqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PgNativeQueueConsumerListenIT {

    private static final String TOPIC = "it-listen-topic";

    @Container
    static final PostgreSQLContainer<?> postgres =
        PeeGeeQTestContainerFactory.createContainer(BASIC);

    private PeeGeeQManager manager;
    private VertxPoolAdapter adapter;
    private ObjectMapper mapper;

    @BeforeAll
    static void beforeAll() {
        // Initialize minimal schema for native queue tests
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SCHEMA_VERSION, NATIVE_QUEUE, DEAD_LETTER_QUEUE);
    }

    @BeforeEach
    void setUp() {
        // Configure system properties for TestContainers
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create adapter using DatabaseService interfaces
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        adapter = new VertxPoolAdapter(
            databaseService.getVertx(),
            databaseService.getPool(),
            databaseService
        );

        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            try { manager.close(); } catch (Exception ignore) {}
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

