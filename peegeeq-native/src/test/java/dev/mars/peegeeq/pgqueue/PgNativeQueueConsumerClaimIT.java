package dev.mars.peegeeq.pgqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PgNativeQueueConsumerClaimIT {

    private static final String TOPIC = "it-claim-topic";

    @Container
    static final PostgreSQLContainer<?> postgres =
        PeeGeeQTestContainerFactory.createContainer(BASIC);

    private Vertx vertx;
    private VertxPoolAdapter adapter;
    private Pool pool;
    private ObjectMapper mapper;

    @BeforeAll
    static void beforeAll() {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SCHEMA_VERSION, NATIVE_QUEUE, DEAD_LETTER_QUEUE);
    }

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        adapter = new VertxPoolAdapter(vertx);

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

        pool = adapter.createPool(connCfg, poolCfg);
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
    void visibleAt_serverTime_is_honored_and_consumer_batchSize_from_consumerConfig() {
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
        CompletableFuture<Void> firstReceived = new CompletableFuture<>();
        CompletableFuture<Void> secondReceived = new CompletableFuture<>();

        consumer.subscribe(msg -> {
            int n = processedCount.incrementAndGet();
            if (n == 1) {
                assertEquals("now-msg", msg.getPayload());
                firstReceived.complete(null);
            } else if (n == 2) {
                assertEquals("future-msg", msg.getPayload());
                secondReceived.complete(null);
            }
            return CompletableFuture.completedFuture(null);
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
            .toCompletionStage().toCompletableFuture().join();

        // Insert one message visible in the future (10s)
        String insertFuture = """
            INSERT INTO queue_messages (topic, payload, headers, correlation_id, status, created_at, visible_at, priority)
            VALUES ($1, $2::jsonb, $3::jsonb, $4, 'AVAILABLE', now(), now() + make_interval(secs => 10), 5)
            RETURNING id
        """;
        JsonObject payloadFuture = new JsonObject().put("value", "future-msg");
        pool.preparedQuery(insertFuture)
            .execute(Tuple.of(TOPIC, payloadFuture, headers, "c2"))
            .toCompletionStage().toCompletableFuture().join();

        // Trigger consumer via NOTIFY
        pool.query("SELECT pg_notify('" + ("queue_" + TOPIC) + "', 'test')").execute()
            .toCompletionStage().toCompletableFuture().join();

        // Expect only the now-visible message to be processed quickly
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(firstReceived::isDone);
        assertEquals(1, processedCount.get(), "Only the visible-now message should be processed initially");

        // Update the future message to become visible now and notify again
        String makeVisibleNow = "UPDATE queue_messages SET visible_at = now() WHERE topic = $1 AND payload = $2::jsonb";
        pool.preparedQuery(makeVisibleNow)
            .execute(Tuple.of(TOPIC, payloadFuture))
            .toCompletionStage().toCompletableFuture().join();
        pool.query("SELECT pg_notify('" + ("queue_" + TOPIC) + "', 'test2')").execute()
            .toCompletionStage().toCompletableFuture().join();

        // Now the second should be processed
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(secondReceived::isDone);
        assertEquals(2, processedCount.get());

        consumer.close();
    }
}

