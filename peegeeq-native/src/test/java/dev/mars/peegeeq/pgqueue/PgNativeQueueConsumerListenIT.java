package dev.mars.peegeeq.pgqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;

import java.util.concurrent.TimeUnit;

import io.vertx.core.Future;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.*;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ExtendWith(VertxExtension.class)
@Testcontainers
class PgNativeQueueConsumerListenIT {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueConsumerListenIT.class);


    private static final String TOPIC = "it-listen-topic";

    @Container
    static final PostgreSQLContainer postgres =
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
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
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
        manager.start().await();

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
        logger.info("Tearing down: closing resources and manager");
        if (manager != null) {
            try {
                manager.closeReactive().await();
            } catch (Exception ignore) {}
        }
    }

    @Test
    void listenNotify_onlyMode_deliversMessage(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: listen notify only mode delivers message");
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

        consumer.subscribe(msg -> {
            testContext.verify(() -> assertEquals("hello", msg.getPayload()));
            testContext.completeNow();
            return Future.succeededFuture();
        });

        // Act: send a message
        PgNativeQueueProducer<String> producer = new PgNativeQueueProducer<>(
            adapter, mapper, TOPIC, String.class, null
        );
        producer.send("hello", Map.of());

        // Assert: message is received within timeout
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

        // Cleanup
        consumer.close();
        producer.close();
    }
}



