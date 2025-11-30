package dev.mars.peegeeq.api.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;

@Tag("core")
class ConnectionPoolConfigTest {

    @Test
    void testConstructorAndGetters() {
        Duration maxLifetime = Duration.ofMinutes(60);
        Duration connectionTimeout = Duration.ofSeconds(45);
        Duration idleTimeout = Duration.ofMinutes(15);
        
        ConnectionPoolConfig config = new ConnectionPoolConfig(5, 20, maxLifetime, connectionTimeout, idleTimeout);

        assertEquals(5, config.getMinSize());
        assertEquals(20, config.getMaxSize());
        assertEquals(maxLifetime, config.getMaxLifetime());
        assertEquals(connectionTimeout, config.getConnectionTimeout());
        assertEquals(idleTimeout, config.getIdleTimeout());
    }

    @Test
    void testDefaultConstructor() {
        ConnectionPoolConfig config = new ConnectionPoolConfig();
        
        assertEquals(2, config.getMinSize());
        assertEquals(10, config.getMaxSize());
        assertEquals(Duration.ofMinutes(30), config.getMaxLifetime());
        assertEquals(Duration.ofSeconds(30), config.getConnectionTimeout());
        assertEquals(Duration.ofMinutes(10), config.getIdleTimeout());
    }
}
