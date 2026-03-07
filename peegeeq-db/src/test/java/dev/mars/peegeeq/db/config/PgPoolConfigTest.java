package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag(TestCategories.CORE)
class PgPoolConfigTest {

    @Test
    void testConnectionTimeoutMustBePositive() {
        PgPoolConfig.Builder builder = new PgPoolConfig.Builder();
        assertThrows(IllegalArgumentException.class, () -> builder.connectionTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.connectionTimeout(Duration.ofMillis(-1)));
    }

    @Test
    void testIdleTimeoutCannotBeNegative() {
        PgPoolConfig.Builder builder = new PgPoolConfig.Builder();
        assertThrows(IllegalArgumentException.class, () -> builder.idleTimeout(Duration.ofMillis(-1)));
    }
}
