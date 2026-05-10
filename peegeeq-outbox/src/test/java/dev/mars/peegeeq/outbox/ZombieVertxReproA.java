package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import java.util.Properties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Pure Vert.x zombie repro - class A.
 *
 * Mirrors ZombieReproA/B (Spring Boot) but uses the same explicit lifecycle pattern
 * as OutboxConsumerCrashRecoveryTest. PeeGeeQManager is created in @BeforeEach and
 * explicitly closed in @AfterEach via closeReactive(). When run together with
 * ZombieVertxReproB, NO "Connection refused" zombie errors should appear, because
 * the manager is shut down cleanly before the container stops.
 *
 * Contrast with ZombieReproA/B (Spring Boot), where Spring does not invoke
 * PeeGeeQManager.close() on context teardown, leaving timers running against a
 * dead port.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class ZombieVertxReproA {

    private static final Logger logger = LoggerFactory.getLogger(ZombieVertxReproA.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;

    @BeforeEach
    void setUp() throws Exception {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (manager != null) {
            manager.closeReactive().await();
        }
    }

    @Test
    void trivialTestA() {
        logger.info("ZombieVertxReproA: trivial test - manager started and will be closed cleanly in @AfterEach");
    }
}
