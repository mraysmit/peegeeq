package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** The cluster settings value and its stored form (plan §9.34, contract 1). */
@Tag(TestCategories.CORE)
class PgClusterSettingsTest {
    private static final Duration TTL = Duration.ofSeconds(30), LOOP = Duration.ofSeconds(5),
        RETRY = Duration.ofSeconds(3), STOP = Duration.ofSeconds(5);

    @Test void theStoredFormHoldsExactlyTheFourFieldsInMillisecondsAndReadsBackEqual() {
        var settings = new PgClusterSettings(TTL, LOOP, RETRY, STOP);
        JsonObject json = settings.toJson();
        assertEquals(Set.of("leaseTtlMillis", "loopIntervalMillis", "requestTimeoutMillis", "stopTimeoutMillis"),
            json.fieldNames());
        assertEquals(30_000L, json.getLong("leaseTtlMillis"));
        assertEquals(5_000L, json.getLong("loopIntervalMillis"));
        assertEquals(3_000L, json.getLong("requestTimeoutMillis"));
        assertEquals(5_000L, json.getLong("stopTimeoutMillis"));
        assertEquals(settings, PgClusterSettings.fromJson(json));
    }

    @Test void loopAndTwoRetriesMayEqualTheLeaseTtl() {
        // loop + 2 x retry = 30 s equals the TTL; loop + retry + stop = 29 s finishes before it.
        var settings = new PgClusterSettings(TTL, Duration.ofSeconds(24), RETRY, Duration.ofSeconds(2));
        assertEquals(Duration.ofSeconds(24), settings.loopInterval());
    }

    @Test void loopAndTwoRetriesLongerThanTheLeaseTtlAreRefused() {
        // loop + 2 x retry = 31 s.
        var failure = assertThrows(IllegalArgumentException.class,
            () -> new PgClusterSettings(TTL, Duration.ofSeconds(25), RETRY, Duration.ofSeconds(1)));
        assertEquals("Loop interval plus two retry budgets exceeds the lease TTL", failure.getMessage());
    }

    @Test void aStopBudgetThatCannotFinishBeforeTheLeaseTtlIsRefused() {
        // loop + retry + stop = 30 s leaves no time before the earliest expiry.
        var failure = assertThrows(IllegalArgumentException.class,
            () -> new PgClusterSettings(TTL, Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(15)));
        assertEquals("One loop-and-retry cycle plus the stop budget must finish before the lease TTL",
            failure.getMessage());
    }

    @Test void everyValueMustBeAPositiveWholeNumberOfMilliseconds() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(1_000_500_000L))) {
            assertThrows(IllegalArgumentException.class, () -> new PgClusterSettings(invalid, LOOP, RETRY, STOP),
                "lease TTL " + invalid);
            assertThrows(IllegalArgumentException.class, () -> new PgClusterSettings(TTL, invalid, RETRY, STOP),
                "loop interval " + invalid);
            assertThrows(IllegalArgumentException.class, () -> new PgClusterSettings(TTL, LOOP, invalid, STOP),
                "retry budget " + invalid);
            assertThrows(IllegalArgumentException.class, () -> new PgClusterSettings(TTL, LOOP, RETRY, invalid),
                "stop budget " + invalid);
        }
        assertThrows(NullPointerException.class, () -> new PgClusterSettings(null, LOOP, RETRY, STOP));
        assertThrows(IllegalArgumentException.class, () -> new PgClusterSettings(TTL, null, RETRY, STOP));
    }

    @Test void aStoredFormWithAnUnknownFieldIsRefused() {
        JsonObject json = stored().put("ttl", 30);
        assertThrows(IllegalArgumentException.class, () -> PgClusterSettings.fromJson(json));
    }

    @Test void aStoredFormWithAMissingFieldIsRefused() {
        for (String field : List.of("leaseTtlMillis", "loopIntervalMillis", "requestTimeoutMillis", "stopTimeoutMillis")) {
            JsonObject json = stored();
            json.remove(field);
            assertThrows(IllegalArgumentException.class, () -> PgClusterSettings.fromJson(json), field);
        }
    }

    @Test void aStoredFieldThatIsNotAnIntegerIsRefused() {
        for (Object value : new Object[] {"30000", 30000.5, null, true}) {
            JsonObject json = stored().put("leaseTtlMillis", value);
            assertThrows(IllegalArgumentException.class, () -> PgClusterSettings.fromJson(json), String.valueOf(value));
        }
    }

    @Test void aStoredFormThatBreaksTheRuleIsRefusedAndNotAdjusted() {
        // loop + 2 x retry = 31 s: Patroni would rewrite the loop interval; this design refuses (OD-66).
        JsonObject json = stored().put("loopIntervalMillis", 25_000L).put("stopTimeoutMillis", 1_000L);
        assertThrows(IllegalArgumentException.class, () -> PgClusterSettings.fromJson(json));
        assertThrows(NullPointerException.class, () -> PgClusterSettings.fromJson(null));
    }

    private static JsonObject stored() {
        return new JsonObject().put("leaseTtlMillis", 30_000L).put("loopIntervalMillis", 5_000L)
            .put("requestTimeoutMillis", 3_000L).put("stopTimeoutMillis", 5_000L);
    }
}
