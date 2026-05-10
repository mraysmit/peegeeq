package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Properties;
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
 * Pure Vert.x zombie repro - class B.
 *
 * See ZombieVertxReproA for full explanation. When run together with ZombieVertxReproA,
 * no "Connection refused" zombie errors should appear. This confirms the zombie seen in
 * ZombieReproA/B (Spring Boot) is caused by Spring not invoking PeeGeeQManager.close()
 * during context teardown, not by anything inherent in PeeGeeQManager itself.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class ZombieVertxReproB {

    private static final Logger logger = LoggerFactory.getLogger(ZombieVertxReproB.class);

    private static final String[] SYSTEM_PROPERTIES = {
        "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
        "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.ssl.enabled"
    };

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
    void trivialTestB() {
        logger.info("ZombieVertxReproB: trivial test - no zombie should appear from A's manager");
    }
}
