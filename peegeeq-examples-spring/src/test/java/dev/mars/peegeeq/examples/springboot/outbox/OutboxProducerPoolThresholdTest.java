package dev.mars.peegeeq.examples.springboot.outbox;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.examples.springboot.config.PeeGeeQProperties;
import dev.mars.peegeeq.outbox.OutboxFactory;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterization test that empirically determines the outbox producer's connection pool
 * degradation threshold.
 *
 * <p>Ramps concurrent {@code producer.send()} calls from low to high, counting
 * {@code ConnectionPoolTooBusyException} failures at each level. Stops at the first
 * level where failures appear, then prints a diagnostic report and asserts the empirical
 * threshold matches the formula: {@code maxSize + maxWaitQueueSize + 1}.
 *
 * <p>The pool is configured small ({@code max-size=5}, {@code max-wait-queue-size=10})
 * to keep the ramp fast. The formula threshold is therefore 16 concurrent sends.
 * Any change to pool defaults that breaks this formula will surface as a clear test
 * failure with printed diagnostics.
 *
 * <h2>Why this matters in production</h2>
 * <p>Each {@code producer.send()} acquires one pool connection for the INSERT. At burst
 * scale, once {@code maxSize + maxWaitQueueSize} concurrent sends are in-flight, the
 * ({@code maxSize + maxWaitQueueSize + 1})-th caller is rejected immediately with
 * {@code ConnectionPoolTooBusyException}. This test documents and verifies that boundary.
 */
@Tag(TestCategories.PERFORMANCE)
@SpringBootTest(
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration"
    }
)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxProducerPoolThresholdTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxProducerPoolThresholdTest.class);

    @Container
    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    /**
     * Configures the shared database connection and overrides pool settings to deliberately
     * small values ({@code max-size=5}, {@code max-wait-queue-size=10}) so the degradation
     * threshold (16) is reached quickly during the ramp.
     *
     * <p>{@code @DynamicPropertySource} has higher precedence than
     * {@code @SpringBootTest(properties=...)}, so the overrides here are authoritative.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
        // Override pool to a small controlled size: threshold = 5 + 10 + 1 = 16
        // Use peegeeq.pool.* so Spring binds into PeeGeeQProperties.Pool
        registry.add("peegeeq.pool.max-size", () -> "5");
        registry.add("peegeeq.pool.max-wait-queue-size", () -> "10");
    }

    @Autowired
    private OutboxFactory outboxFactory;

    @Autowired
    private PeeGeeQProperties properties;

    @Autowired
    private PeeGeeQManager peeGeeQManager;

    private static PeeGeeQManager peeGeeQManagerRef;

    private final List<MessageProducer<?>> activeProducers = new ArrayList<>();

    @BeforeAll
    static void initializeSchema() {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
    }

    @AfterEach
    void tearDown(VertxTestContext tearDownContext) {
        for (MessageProducer<?> producer : activeProducers) {
            try {
                producer.close();
            } catch (Exception e) {
                logger.warn("Error closing producer: {}", e.getMessage());
            }
        }
        activeProducers.clear();
        peeGeeQManagerRef = peeGeeQManager;
        tearDownContext.completeNow();
    }

    @AfterAll
    static void closeManager(VertxTestContext testContext) {
        if (peeGeeQManagerRef == null) {
            testContext.completeNow();
            return;
        }
        peeGeeQManagerRef.closeReactive()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    /**
     * Ramps concurrent outbox sends from low to high until the first
     * {@code ConnectionPoolTooBusyException} is observed. Prints a degradation report
     * and asserts the empirical threshold equals {@code maxSize + maxWaitQueueSize + 1}.
     *
     * <p>With the overridden pool config (maxSize=5, maxWaitQueueSize=10), the expected
     * threshold is 16.
     */
    @Test
    void testConnectionPoolDegradationThreshold(VertxTestContext testContext) throws InterruptedException {
        int maxSize = properties.getPool().getMaxSize();
        int maxWaitQueueSize = properties.getPool().getMaxWaitQueueSize();
        int formulaThreshold = maxSize + maxWaitQueueSize + 1;

        logger.info("=== Connection Pool Degradation Threshold Characterization ===");
        logger.info("  Pool config: maxSize={}, maxWaitQueueSize={}", maxSize, maxWaitQueueSize);
        logger.info("  Formula threshold: {} + {} + 1 = {}", maxSize, maxWaitQueueSize, formulaThreshold);

        String topic = "threshold-" + UUID.randomUUID().toString().substring(0, 8);
        MessageProducer<ThresholdPayload> producer = outboxFactory.createProducer(topic, ThresholdPayload.class);
        activeProducers.add(producer);

        // Ramp sequence: below threshold, just below, at threshold, just above
        int[] rampSequence = {
            1,
            Math.max(1, formulaThreshold / 4),
            Math.max(2, formulaThreshold / 2),
            formulaThreshold - 2,
            formulaThreshold - 1,
            formulaThreshold,
            formulaThreshold + 2,
            formulaThreshold + 5
        };

        int[] empiricalThreshold = {-1};

        Future<Void> chain = Future.succeededFuture();
        for (int rampN : rampSequence) {
            final int n = rampN;
            chain = chain.compose(ignored -> {
                if (empiricalThreshold[0] != -1) {
                    // Threshold already found; continue ramp to show failure count growth
                }
                return fireNConcurrentSends(producer, n)
                    .map(failures -> {
                        String marker = failures > 0 ? " [THRESHOLD REACHED]" : " [below threshold]";
                        logger.info("  N={}: pool-too-busy failures={}{}", n, failures, marker);
                        if (failures > 0 && empiricalThreshold[0] == -1) {
                            empiricalThreshold[0] = n;
                        }
                        return (Void) null;
                    });
            });
        }

        chain.onComplete(testContext.succeeding(ignored -> testContext.verify(() -> {
            logger.info("=== POOL DEGRADATION THRESHOLD REPORT ===");
            logger.info("  Pool config   : maxSize={}, maxWaitQueueSize={}", maxSize, maxWaitQueueSize);
            logger.info("  Formula       : maxSize({}) + maxWaitQueueSize({}) + 1 = {}",
                maxSize, maxWaitQueueSize, formulaThreshold);
            logger.info("  Empirical     : {} concurrent sends", empiricalThreshold[0]);
            if (empiricalThreshold[0] == formulaThreshold) {
                logger.info("  Verdict       : EMPIRICAL MATCHES FORMULA ✓");
            } else {
                logger.warn("  Verdict       : MISMATCH — empirical={}, formula={}",
                    empiricalThreshold[0], formulaThreshold);
            }
            assertEquals(formulaThreshold, empiricalThreshold[0],
                "Empirical degradation threshold should equal maxSize + maxWaitQueueSize + 1");
            testContext.completeNow();
        })));

        testContext.awaitCompletion(60, TimeUnit.SECONDS);
    }

    /**
     * Fires {@code n} concurrent producer sends. Each send is wrapped via
     * {@code .transform()} so failures become values rather than failing the
     * composite future. Returns the count of sends that failed with a pool-too-busy
     * exception.
     *
     * <p>{@code Future.join()} is used (not {@code Future.all()}) so all N futures
     * settle before the count is returned, regardless of individual failures.
     */
    private Future<Long> fireNConcurrentSends(MessageProducer<ThresholdPayload> producer, int n) {
        List<Future<Throwable>> futures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ThresholdPayload payload = new ThresholdPayload("msg-" + i, "threshold-probe");
            futures.add(
                producer.send(payload)
                    .transform(ar -> Future.succeededFuture(ar.failed() ? ar.cause() : null))
            );
        }
        return Future.join(futures)
            .map(composite -> {
                long count = 0;
                for (int i = 0; i < n; i++) {
                    Throwable t = composite.resultAt(i);
                    if (t != null && "ConnectionPoolTooBusyException".equals(t.getClass().getSimpleName())) {
                        count++;
                    }
                }
                return count;
            });
    }

    /** Minimal payload for threshold probing. */
    public static class ThresholdPayload {
        private String id;
        private String data;

        public ThresholdPayload() {}

        public ThresholdPayload(String id, String data) {
            this.id = id;
            this.data = data;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
    }
}
