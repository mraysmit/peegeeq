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
        assertEquals("peegeeq/pg/cluster/incarnation/primary-lock", config.controlName());
        assertEquals(Duration.ofSeconds(30), config.leaseTtl());
        assertEquals(Duration.ofSeconds(15), config.watchdogTimeout());
        assertEquals(Duration.ofSeconds(5), config.loopInterval());
        assertEquals(Duration.ofSeconds(3), config.requestTimeout());
        assertEquals(Duration.ofSeconds(5), config.stopTimeout());
        assertEquals(PgWatchdogMode.AUTOMATIC, config.watchdogMode());
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

    @Test void rejectsNonPositiveOrSubMillisecondLeaseTtl() {
        for (Duration ttl : List.of(Duration.ZERO, Duration.ofSeconds(-30), Duration.ofNanos(30_000_000_500L))) {
            assertThrows(IllegalArgumentException.class, () -> timing(ttl,
                Duration.ofSeconds(1), Duration.ofMillis(500), Duration.ofSeconds(1), PgWatchdogMode.OFF));
        }
    }

    @Test void leaseTtlRangeBelongsToTheAdapter() {
        // A coordinator's accepted TTL range is that adapter's rule, not a node-configuration rule.
        for (Duration ttl : List.of(Duration.ofMillis(30500), Duration.ofSeconds(9))) {
            var config = timing(ttl, Duration.ofSeconds(1), Duration.ofMillis(500), Duration.ofSeconds(1),
                PgWatchdogMode.AUTOMATIC);
            assertEquals(ttl, config.leaseTtl());
        }
    }

    @Test void rejectsMissingWatchdogMode() {
        assertThrows(NullPointerException.class, () -> timing(Duration.ofSeconds(30),
            Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofSeconds(5), null));
    }

    @Test void leaseRuleRejectsLoopAndRetriesLongerThanTtlInEveryMode() {
        // loop + 2 x retry = 31 s exceeds the 30 s lease TTL.
        for (PgWatchdogMode mode : PgWatchdogMode.values()) {
            assertThrows(IllegalArgumentException.class, () -> timing(Duration.ofSeconds(30),
                Duration.ofSeconds(25), Duration.ofSeconds(3), Duration.ofSeconds(1), mode), mode.name());
        }
    }

    @Test void leaseRuleRejectsStopBudgetThatCannotFinishBeforeExpiryInEveryMode() {
        // loop + retry + stop = 30 s leaves no time before the earliest expiry of a 30 s lease.
        for (PgWatchdogMode mode : PgWatchdogMode.values()) {
            assertThrows(IllegalArgumentException.class, () -> timing(Duration.ofSeconds(30),
                Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(15), mode), mode.name());
        }
    }

    @Test void watchdogTimingIsRejectedOnlyInRequiredMode() {
        // loop + retry = 15 s leaves no slack against the 15 s watchdog timeout. The lease rule holds.
        Duration ttl = Duration.ofSeconds(30), loop = Duration.ofSeconds(12),
            request = Duration.ofSeconds(3), stop = Duration.ofSeconds(5);
        assertThrows(IllegalArgumentException.class, () -> timing(ttl, loop, request, stop, PgWatchdogMode.REQUIRED));
        for (PgWatchdogMode mode : List.of(PgWatchdogMode.AUTOMATIC, PgWatchdogMode.OFF)) {
            var config = timing(ttl, loop, request, stop, mode);
            assertFalse(config.watchdogTimingUsable(), mode.name());
            assertEquals(Duration.ofSeconds(25), config.ownershipBudget(), mode.name());
        }
    }

    @Test void offModeBudgetIsTheLeaseTtlLessTheStopBudget() {
        var config = timing(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(3),
            Duration.ofSeconds(5), PgWatchdogMode.OFF);
        assertEquals(Duration.ofSeconds(25), config.ownershipBudget());
    }

    @Test void modesThatMayUseAWatchdogKeepTheWatchdogBudget() {
        for (PgWatchdogMode mode : List.of(PgWatchdogMode.AUTOMATIC, PgWatchdogMode.REQUIRED)) {
            var config = timing(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(3),
                Duration.ofSeconds(5), mode);
            assertTrue(config.watchdogTimingUsable(), mode.name());
            assertEquals(Duration.ofSeconds(15), config.ownershipBudget(), mode.name());
        }
    }

    @Test void ownershipBudgetLeavesStopTimeBeforeExpiryAndRoomForOneCycle() {
        for (PgWatchdogMode mode : PgWatchdogMode.values()) {
            var config = timing(Duration.ofSeconds(10), Duration.ofMillis(250), Duration.ofSeconds(1),
                Duration.ofSeconds(1), mode);
            Duration budget = config.ownershipBudget();
            assertTrue(budget.plus(config.stopTimeout()).compareTo(config.leaseTtl()) <= 0, mode.name());
            assertTrue(budget.compareTo(config.loopInterval().plus(config.requestTimeout())) > 0, mode.name());
        }
    }

    @Test void watchdogModeParsesItsPropertyValues() {
        assertEquals(PgWatchdogMode.AUTOMATIC, PgWatchdogMode.fromProperty("automatic"));
        assertEquals(PgWatchdogMode.OFF, PgWatchdogMode.fromProperty("off"));
        assertEquals(PgWatchdogMode.REQUIRED, PgWatchdogMode.fromProperty("required"));
        for (String invalid : new String[] {null, "", "AUTOMATIC", "on", "true"}) {
            assertThrows(IllegalArgumentException.class, () -> PgWatchdogMode.fromProperty(invalid),
                "Value: " + invalid);
        }
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
        var record = new PgControlRecord("name", 1, 2, "holder", intent);
        intent.put("phase", "SERVING");
        record.intent().put("phase", "SERVING");
        assertEquals("WITHDRAWN", record.intent().getString("phase"));
        assertEquals("name", record.controlName());
        assertEquals(1, record.generation());
        assertEquals(2, record.revision());
        assertEquals("holder", record.leaseHolder());
        assertThrows(IllegalArgumentException.class,
            () -> new PgControlRecord("name", 0, 2, "holder", intent));
    }

    @Test void unownedHistoryHasNoLeaseHolder() {
        var record = new PgControlRecord("name", 1, 2, null, new JsonObject());
        assertNull(record.leaseHolder());
        assertThrows(IllegalArgumentException.class,
            () -> new PgControlRecord("name", 1, 2, " ", new JsonObject()));
    }

    private static PgNodeConfig timing(Duration ttl, Duration loop, Duration request, Duration stop,
                                       PgWatchdogMode mode) {
        return new PgNodeConfig("cluster", "incarnation", "pg-node-1", MEMBERS, ttl, loop, request, stop, mode);
    }
}
