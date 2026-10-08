package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.CORE)
class PgNodeConfigTest {
    private static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");

    @Test void defaultsUseCanonicalTimingAndNamespace() {
        var config = PgNodeConfig.defaults("cluster", "incarnation", "pg-node-1", MEMBERS);
        assertEquals("peegeeq/pg/cluster/incarnation/primary-lock", config.controlKey());
        assertEquals(Duration.ofSeconds(30), config.sessionTtl());
        assertEquals(Duration.ofSeconds(15), config.watchdogTimeout());
        assertEquals(Duration.ofSeconds(5), config.loopInterval());
        assertEquals(Duration.ofSeconds(3), config.requestTimeout());
        assertEquals(Duration.ofSeconds(5), config.stopTimeout());
    }

    @Test void rejectsNamespaceTraversal() {
        assertThrows(IllegalArgumentException.class,
            () -> PgNodeConfig.defaults("../other", "incarnation", "pg-node-1", MEMBERS));
    }

    @Test void rejectsCaseInsensitiveMemberCollision() {
        assertThrows(IllegalArgumentException.class,
            () -> PgNodeConfig.defaults("cluster", "incarnation", "pg-node-1",
                List.of("pg-node-1", "PG-NODE-1")));
    }

    @Test void rejectsUnknownLocalNode() {
        assertThrows(IllegalArgumentException.class,
            () -> PgNodeConfig.defaults("cluster", "incarnation", "unknown", MEMBERS));
    }

    @Test void rejectsFractionalOrShortConsulTtl() {
        for (Duration ttl : List.of(Duration.ofMillis(30500), Duration.ofSeconds(9))) {
            assertThrows(IllegalArgumentException.class, () -> new PgNodeConfig(
                "cluster", "incarnation", "pg-node-1", MEMBERS, ttl,
                Duration.ofSeconds(1), Duration.ofMillis(500), Duration.ofSeconds(1)));
        }
    }

    @Test void rejectsTimingWithoutExclusionSlack() {
        assertThrows(IllegalArgumentException.class, () -> new PgNodeConfig(
            "cluster", "incarnation", "pg-node-1", MEMBERS, Duration.ofSeconds(30),
            Duration.ofSeconds(12), Duration.ofSeconds(3), Duration.ofSeconds(5)));
    }

    @Test void membershipIsImmutable() {
        List<String> mutable = new ArrayList<>(MEMBERS);
        var config = PgNodeConfig.defaults("cluster", "incarnation", "pg-node-1", mutable);
        mutable.clear();
        assertEquals(MEMBERS, config.memberNodeIds());
        assertThrows(UnsupportedOperationException.class, () -> config.memberNodeIds().clear());
    }

    @Test void metadataAndIntentAreImmutable() {
        var intent = new JsonObject().put("phase", "WITHDRAWN");
        var record = new PgControlRecord("key", 1, 2, "session", intent);
        intent.put("phase", "SERVING");
        record.intent().put("phase", "SERVING");
        assertEquals("WITHDRAWN", record.intent().getString("phase"));
        assertThrows(IllegalArgumentException.class,
            () -> new PgControlRecord("key", 0, 2, "session", intent));
    }
}
