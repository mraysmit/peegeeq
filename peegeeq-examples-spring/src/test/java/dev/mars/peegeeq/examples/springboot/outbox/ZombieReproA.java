package dev.mars.peegeeq.examples.springboot.outbox;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.examples.springboot.SpringBootOutboxApplication;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zombie-shutdown regression test A.
 * Two classes (A and B) with identical @SpringBootTest properties but each with its own
 * @DynamicPropertySource method. Spring creates a separate ApplicationContext per class
 * because the context cache key includes the @DynamicPropertySource method reference.
 * Each context starts its own PeeGeeQManager (Vert.x), and Spring must close each manager
 * after its dependent beans without leaving background timers running into the next context.
 */
@Tag(TestCategories.INTEGRATION)
@SpringBootTest(
    classes = SpringBootOutboxApplication.class,
    properties = {
        "spring.profiles.active=test",
        "logging.level.dev.mars.peegeeq=INFO",
        "logging.level.dev.mars.peegeeq.examples.springboot=INFO",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration"
    }
)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ZombieReproA {

    private static final Logger logger = LoggerFactory.getLogger(ZombieReproA.class);

    @Autowired
    private PeeGeeQManager peeGeeQManager;

    @Container
    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    @BeforeAll
    static void initSchema() {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
    }

    @Test
    void managerStartsInFirstIsolatedContext() {
        assertTrue(peeGeeQManager.isStarted(), "First isolated context should start its manager");
        logger.info("ZombieReproA: manager is started; Spring will own context shutdown");
    }
}
