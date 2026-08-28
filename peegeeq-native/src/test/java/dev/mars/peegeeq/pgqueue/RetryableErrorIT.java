package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic retry integration test using a PostgreSQL sequence-backed trigger.
 */
@Testcontainers
@ExtendWith(VertxExtension.class)
@Tag(TestCategories.INTEGRATION)
public class RetryableErrorIT {

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private static final Logger logger = LoggerFactory.getLogger(RetryableErrorIT.class);

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private PgNativeQueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext ctx) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();
        PeeGeeQTestSchemaInitializer.initializeSchema(
            postgres,
            PostgreSQLTestConstants.TEST_SCHEMA,
            SchemaComponent.NATIVE_QUEUE,
            SchemaComponent.OUTBOX,
            SchemaComponent.DEAD_LETTER_QUEUE
        );

        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("retryable-error-test", testProps);
        manager = new PeeGeeQManager(cfg, new SimpleMeterRegistry());
        manager.start()
                .compose(v -> {
                    databaseService = new PgDatabaseService(manager);
                    return initializeRetryTrigger();
                })
                .onSuccess(v -> {
                    factory = new PgNativeQueueFactory(databaseService);
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        (factory != null ? factory.close() : Future.<Void>succeededFuture())
            .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> {
                logger.error("Error during teardown", err);
                testContext.failNow(err);
            });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testConsumerRetriesOn40P01AndSucceeds(Vertx vertx, VertxTestContext testContext) throws Exception {
        String topic = "retryable-error-test-topic";
        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        MessageConsumer<String> consumer = factory.createConsumer(topic, String.class,
                new ConsumerConfig.Builder().mode(ConsumerMode.HYBRID).consumerThreads(1).build());

        Promise<Void> messageProcessed = Promise.promise();
        consumer.subscribe(msg -> {
            logger.info("Test: consumer retries on 40P01 and succeeds");
            messageProcessed.tryComplete();
            return Future.succeededFuture();
        })
            .compose(v -> producer.send("one"))
            .compose(v -> messageProcessed.future())
            .compose(v -> getRetryLogCount())
            .onSuccess(retryCount -> testContext.verify(() -> {
                assertEquals(1, retryCount, "Expected exactly one simulated 40P01 raise from trigger");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        // Should still process successfully even though the first claim attempt fails with 40P01
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Message should be processed after one retry");

        producer.close();
    }

    private Future<Void> initializeRetryTrigger() {
        return execute("CREATE SEQUENCE IF NOT EXISTS retry_attempt_sequence")
            .compose(v -> execute("CREATE SEQUENCE IF NOT EXISTS retry_failure_sequence"))
            .compose(v -> execute("SELECT setval('retry_attempt_sequence', 1, false)"))
            .compose(v -> execute("SELECT setval('retry_failure_sequence', 1, false)"))
            .compose(v -> execute("""
                CREATE OR REPLACE FUNCTION queue_claim_retry_guard() RETURNS trigger AS $$
                DECLARE attempt BIGINT;
                BEGIN
                  attempt := nextval('retry_attempt_sequence');
                  IF attempt = 1 THEN
                    PERFORM nextval('retry_failure_sequence');
                    RAISE EXCEPTION 'simulated deadlock' USING ERRCODE = '40P01';
                  END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """))
            .compose(v -> execute("DROP TRIGGER IF EXISTS queue_messages_retry_guard ON queue_messages"))
            .compose(v -> execute("""
                CREATE TRIGGER queue_messages_retry_guard
                BEFORE UPDATE ON queue_messages
                FOR EACH ROW EXECUTE FUNCTION queue_claim_retry_guard()
                """))
            .compose(v -> execute("TRUNCATE TABLE queue_messages, dead_letter_queue CASCADE"));
    }

    private Future<Void> execute(String sql) {
        return databaseService.getPool().query(sql).execute().mapEmpty();
    }

    private Future<Integer> getRetryLogCount() {
        return databaseService.getPool()
            .query("SELECT CASE WHEN is_called THEN last_value ELSE 0 END AS failure_count FROM retry_failure_sequence")
            .execute()
            .map(rows -> Math.toIntExact(rows.iterator().next().getLong("failure_count")));
    }
}
