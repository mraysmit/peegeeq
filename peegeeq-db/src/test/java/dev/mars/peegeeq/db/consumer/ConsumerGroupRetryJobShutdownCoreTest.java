package dev.mars.peegeeq.db.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.deadletter.DeadLetterQueueManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
class ConsumerGroupRetryJobShutdownCoreTest {

    private Pool noOpPool;
    private ControlledRetryService retryService;
    private ConsumerGroupRetryJob job;

    @BeforeEach
    void setUp(Vertx vertx) {
        PgConnectOptions noOpOptions = new PgConnectOptions()
                .setHost("127.0.0.1")
                .setPort(1)
                .setDatabase("unused")
                .setUser("unused")
            .setPassword("unused");

        noOpPool = PgBuilder.pool()
                .using(vertx)
                .connectingTo(noOpOptions)
                .build();

        PgConnectionManager connectionManager = new PgConnectionManager(vertx);
        DeadLetterQueueManager dlqManager = new DeadLetterQueueManager(noOpPool, new ObjectMapper());

        retryService = new ControlledRetryService(connectionManager, dlqManager);
        job = new ConsumerGroupRetryJob(vertx, retryService, 60_000L);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> chain = Future.succeededFuture();
        if (job != null) {
            chain = chain.compose(v -> job.stop().transform(ar -> Future.succeededFuture()));
        }
        if (noOpPool != null) {
            chain = chain.compose(v -> noOpPool.close().transform(ar -> Future.succeededFuture()));
        }

        chain.onSuccess(v -> testContext.completeNow())
             .onFailure(testContext::failNow);
    }

    @Test
    void stopWaitsForInFlightProcessing(VertxTestContext testContext) {
        Promise<ConsumerGroupRetryService.RetryResult> gate = Promise.promise();
        retryService.setDeferred(gate);

        job.start();
        Future<Void> stopFuture = job.stop();

        testContext.verify(() -> {
            assertFalse(stopFuture.isComplete(), "stop() should wait for in-flight retry processing");
            assertFalse(job.isRunning(), "job should be fenced from new runs once stop() is called");
            assertEquals(1, retryService.invocationCount(), "initial start() should trigger exactly one run");
        });

        gate.complete(new ConsumerGroupRetryService.RetryResult(0, 0));

        stopFuture
                .onSuccess(v -> testContext.verify(() -> {
                    assertTrue(stopFuture.succeeded(), "stop() should complete successfully once in-flight run completes");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void stopOnNonRunningJobCompletesImmediately() {
        Future<Void> stopFuture = job.stop();
        assertTrue(stopFuture.succeeded(), "stop() should succeed immediately when job is not running");
    }

    @Test
    void stopSucceedsWhenInFlightRunFails(VertxTestContext testContext) {
        Promise<ConsumerGroupRetryService.RetryResult> gate = Promise.promise();
        retryService.setDeferred(gate);

        job.start();
        Future<Void> stopFuture = job.stop();

        testContext.verify(() -> assertFalse(stopFuture.isComplete(), "stop() should wait for failing in-flight run"));

        gate.fail(new RuntimeException("forced failure"));

        stopFuture
                .onSuccess(v -> testContext.verify(() -> {
                    assertEquals(1, job.getTotalFailures(), "failed in-flight run should still increment failure stats");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    private static final class ControlledRetryService extends ConsumerGroupRetryService {
        private final AtomicInteger invocationCount = new AtomicInteger(0);
        private Promise<RetryResult> deferred;

        private ControlledRetryService(PgConnectionManager connectionManager, DeadLetterQueueManager dlqManager) {
            super(connectionManager, dlqManager, "test-retry-job-shutdown");
        }

        void setDeferred(Promise<RetryResult> deferred) {
            this.deferred = deferred;
        }

        int invocationCount() {
            return invocationCount.get();
        }

        @Override
        public Future<RetryResult> processFailedMessages() {
            invocationCount.incrementAndGet();
            Promise<RetryResult> current = deferred;
            deferred = null;
            if (current != null) {
                return current.future();
            }
            return Future.succeededFuture(new RetryResult(0, 0));
        }
    }
}
