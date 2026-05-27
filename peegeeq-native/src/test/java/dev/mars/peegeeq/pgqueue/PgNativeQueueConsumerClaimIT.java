package dev.mars.peegeeq.pgqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Future;
import io.vertx.core.Promise;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.*;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ExtendWith(VertxExtension.class)
@Testcontainers
class PgNativeQueueConsumerClaimIT {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueConsumerClaimIT.class);


    private static final String TOPIC = "it-claim-topic";

    @Container
    static final PostgreSQLContainer postgres =
        PeeGeeQTestContainerFactory.createContainer(BASIC);

    private PeeGeeQManager manager;
    private VertxPoolAdapter adapter;
    private Pool pool;
    private ObjectMapper mapper;

    @BeforeAll
    static void beforeAll() {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SCHEMA_VERSION, NATIVE_QUEUE, DEAD_LETTER_QUEUE);
    }

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Configure system properties for TestContainers
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .build();

        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    // Create adapter using DatabaseService interfaces
                    PgDatabaseService databaseService = new PgDatabaseService(manager);
                    adapter = new VertxPoolAdapter(
                        databaseService.getVertx(),
                        databaseService.getPool(),
                        databaseService
                    );
                    pool = adapter.getPool();
                    mapper = new ObjectMapper();
                    mapper.registerModule(new JavaTimeModule());
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        (manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(err -> {
                    logger.error("Teardown close failed", err);
                    ctx.failNow(err);
                });
        assertTrue(ctx.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void visibleAt_serverTime_is_honored_and_consumer_batchSize_from_consumerConfig(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: visible at server time is honored and consumer batch size from consumer config");
        ConsumerConfig consumerConfig = ConsumerConfig.builder()
            .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
            .pollingInterval(Duration.ofSeconds(1))
            .consumerThreads(1)
            .batchSize(1) // ensure single-claim batches
            .build();

        PgNativeQueueConsumer<String> consumer = new PgNativeQueueConsumer<>(
            adapter, mapper, TOPIC, String.class, null, null, consumerConfig
        );

        AtomicInteger processedCount = new AtomicInteger();
        Promise<Void> firstReceived = Promise.promise();

        consumer.subscribe(msg -> {
            int n = processedCount.incrementAndGet();
            if (n == 1) {
                testContext.verify(() -> assertEquals("now-msg", msg.getPayload()));
                firstReceived.tryComplete();
            } else if (n == 2) {
                testContext.verify(() -> assertEquals("future-msg", msg.getPayload()));
                testContext.completeNow();
            }
            return Future.succeededFuture();
        });

        // Insert one message visible now
        String insertNow = """
            INSERT INTO queue_messages (topic, payload, headers, correlation_id, status, created_at, visible_at, priority)
            VALUES ($1, $2::jsonb, $3::jsonb, $4, 'AVAILABLE', now(), now(), 5)
            RETURNING id
        """;
        JsonObject payloadNow = new JsonObject().put("value", "now-msg");
        JsonObject headers = new JsonObject();
        pool.preparedQuery(insertNow)
            .execute(Tuple.of(TOPIC, payloadNow, headers, "c1"))
            // Insert one message visible in the future (10s)
            .compose(v -> {
                String insertFuture = """
                    INSERT INTO queue_messages (topic, payload, headers, correlation_id, status, created_at, visible_at, priority)
                    VALUES ($1, $2::jsonb, $3::jsonb, $4, 'AVAILABLE', now(), now() + make_interval(secs => 10), 5)
                    RETURNING id
                """;
                JsonObject payloadFuture = new JsonObject().put("value", "future-msg");
                return pool.preparedQuery(insertFuture)
                    .execute(Tuple.of(TOPIC, payloadFuture, headers, "c2"));
            })
            // Trigger consumer via NOTIFY
            .compose(v -> pool.query("SELECT pg_notify('" + ("queue_" + TOPIC) + "', 'test')").execute())
            // After firstReceived, update visibility and notify again
            .compose(v -> firstReceived.future())
            .compose(v -> {
                testContext.verify(() -> assertEquals(1, processedCount.get(), "Only the visible-now message should be processed initially"));
                String makeVisibleNow = "UPDATE queue_messages SET visible_at = now() WHERE topic = $1 AND payload = $2::jsonb";
                JsonObject payloadFuture = new JsonObject().put("value", "future-msg");
                return pool.preparedQuery(makeVisibleNow)
                    .execute(Tuple.of(TOPIC, payloadFuture));
            })
            .compose(v -> pool.query("SELECT pg_notify('" + ("queue_" + TOPIC) + "', 'test2')").execute())
            .onFailure(testContext::failNow);

        // Now the second should be processed
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        assertEquals(2, processedCount.get());

        consumer.close();
    }
}



