package dev.mars.peegeeq.api.setup;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class SetupReloadReportTest {

    @Test
    void testConstructorAndGetters() {
        SetupReloadReport report = new SetupReloadReport(
                List.of("orders-prod", "events-eu"),
                Map.of("stale-setup", "connection refused"));

        assertEquals(List.of("orders-prod", "events-eu"), report.getReconnectedSetupIds());
        assertEquals(Map.of("stale-setup", "connection refused"), report.getSkippedSetups());
    }

    @Test
    void testNullCollectionsDefaultToEmpty() {
        SetupReloadReport report = new SetupReloadReport(null, null);

        assertNotNull(report.getReconnectedSetupIds());
        assertTrue(report.getReconnectedSetupIds().isEmpty());
        assertNotNull(report.getSkippedSetups());
        assertTrue(report.getSkippedSetups().isEmpty());
    }

    @Test
    void testDefensiveCopiesAndImmutability() {
        List<String> reconnected = new ArrayList<>();
        reconnected.add("setup-a");
        Map<String, String> skipped = new HashMap<>();
        skipped.put("setup-b", "schema absent");

        SetupReloadReport report = new SetupReloadReport(reconnected, skipped);

        // Mutate source collections after construction and verify report state is unchanged.
        reconnected.add("late-addition");
        skipped.put("late-key", "late-value");
        assertEquals(1, report.getReconnectedSetupIds().size());
        assertEquals(1, report.getSkippedSetups().size());

        assertThrows(UnsupportedOperationException.class, () -> report.getReconnectedSetupIds().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> report.getSkippedSetups().put("x", "y"));
    }
}
