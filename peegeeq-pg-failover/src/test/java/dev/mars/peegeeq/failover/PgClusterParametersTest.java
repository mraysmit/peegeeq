package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PgClusterParameters}, the PostgreSQL settings shared by every server in the cluster. They check that
 * a list which could break replication or failover, or which PostgreSQL would read differently from what was written,
 * is refused; that the form stored in the coordinator reads back equal; and that the file text PostgreSQL loads holds
 * each value exactly.
 */
@Tag(TestCategories.CORE)
class PgClusterParametersTest {
    /** The expected first line of {@code peegeeq-cluster.conf}, written out so that a changed header fails a test. */
    private static final String HEADER =
        "# PeeGeeQ cluster-wide PostgreSQL settings, written by the PeeGeeQ supervisor. Do not edit: the supervisor"
            + " replaces this file.\n";
    /**
     * The settings that may not be in the shared list, written out here so that a name dropped from the production list
     * fails a test. The reason for each is in {@code PgClusterParameters}.
     */
    private static final List<String> DENIED = List.of(
        "synchronous_standby_names", "primary_conninfo", "primary_slot_name", "synchronous_commit", "fsync",
        "wal_level", "wal_log_hints", "full_page_writes", "hot_standby", "max_slot_wal_keep_size",
        "listen_addresses", "port", "unix_socket_directories", "data_directory", "config_file", "hba_file",
        "ident_file", "external_pid_file", "include", "include_if_exists", "include_dir",
        "recovery_min_apply_delay", "recovery_target", "recovery_target_action", "recovery_target_inclusive",
        "recovery_target_lsn", "recovery_target_name", "recovery_target_time", "recovery_target_timeline",
        "recovery_target_xid", "promote_trigger_file");

    @Test void theStoredFormHoldsExactlyTheParametersFieldInNameOrderAndReadsBackEqual() {
        var parameters = new PgClusterParameters(Map.of("work_mem", "8MB", "max_connections", "150",
            "peegeeq.probe", "x"));
        JsonObject json = parameters.toJson();
        assertEquals(Set.of("parameters"), json.fieldNames());
        JsonObject stored = json.getJsonObject("parameters");
        assertEquals(List.of("max_connections", "peegeeq.probe", "work_mem"), List.copyOf(stored.fieldNames()));
        assertEquals("150", stored.getString("max_connections"));
        assertEquals("8MB", stored.getString("work_mem"));
        assertEquals(parameters, PgClusterParameters.fromJson(json));
        assertEquals(parameters, PgClusterParameters.fromJson(new JsonObject(json.encode())));
    }

    @Test void theParametersAreACopyInNameOrderThatCannotBeChanged() {
        var source = new HashMap<String, String>();
        source.put("work_mem", "8MB");
        source.put("max_connections", "150");
        var parameters = new PgClusterParameters(source);
        source.put("shared_buffers", "1GB");
        assertEquals(List.of("max_connections", "work_mem"), List.copyOf(parameters.parameters().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> parameters.parameters().put("temp_buffers", "8MB"));
    }

    @Test void everyDeniedNameIsRefusedWhenBuiltAndWhenRead() {
        for (String name : DENIED) {
            var failure = assertThrows(IllegalArgumentException.class,
                () -> new PgClusterParameters(Map.of(name, "1")), name);
            assertEquals("Parameter " + name + " cannot be in the cluster-wide settings: the PeeGeeQ supervisor or each"
                + " node's own configuration sets it", failure.getMessage());
            assertThrows(IllegalArgumentException.class,
                () -> PgClusterParameters.fromJson(stored(new JsonObject().put(name, "1"))), name);
        }
    }

    @Test void parametersNextToTheDeniedNamesAreAllowed() {
        // The denial is by exact name: the other recovery, standby, and resource parameters are allowed.
        var parameters = new PgClusterParameters(Map.of("recovery_prefetch", "try", "recovery_init_sync_method",
            "fsync", "hot_standby_feedback", "on", "max_replication_slots", "10", "max_wal_senders", "10",
            "synchronous_standby_names.x", "y"));
        assertEquals(6, parameters.parameters().size());
    }

    @Test void aNameThatIsNotALowerCasePostgresqlNameIsRefused() {
        for (String name : List.of("", "Work_mem", "WORK_MEM", "work-mem", "1work_mem", "work mem", "work_mem.",
                ".work_mem", "peegeeq..probe", "peegeeq.Probe", "work_mem=1", "work_mem\n")) {
            var failure = assertThrows(IllegalArgumentException.class,
                () -> new PgClusterParameters(Map.of(name, "1")), name);
            assertEquals("Parameter name is not a lower-case PostgreSQL name: " + name, failure.getMessage());
        }
        // PostgreSQL ignores case in names, so an upper-case PORT would set the refused port.
        assertThrows(IllegalArgumentException.class, () -> new PgClusterParameters(Map.of("PORT", "5433")));
    }

    @Test void aNameWithManyPartsIsCheckedWithoutAStackOverflow() {
        // Anyone with write access to the coordinator can store any name. Its check must end in a refusal, not a crash.
        String valid = "a" + ".a".repeat(100_000);
        assertEquals(1, new PgClusterParameters(Map.of(valid, "1")).parameters().size());
        String invalid = valid + ".A";
        assertThrows(IllegalArgumentException.class, () -> new PgClusterParameters(Map.of(invalid, "1")));
    }

    @Test void aCustomNameWithADotIsAllowed() {
        var parameters = new PgClusterParameters(Map.of("peegeeq.probe", "x", "auto_explain.log_min_duration",
            "250ms", "_a1.b_2.c3", "z"));
        assertEquals("x", parameters.parameters().get("peegeeq.probe"));
        assertEquals(3, parameters.parameters().size());
    }

    @Test void aValueWithALineFeedACarriageReturnOrANulIsRefused() {
        for (String value : List.of("8MB\n", "8\rMB", "\u00008MB", "a\nb = 'c'")) {
            var failure = assertThrows(IllegalArgumentException.class,
                () -> new PgClusterParameters(Map.of("work_mem", value)), value);
            assertEquals("Parameter work_mem has a carriage return, line feed, or NUL in its value",
                failure.getMessage());
            assertThrows(IllegalArgumentException.class,
                () -> PgClusterParameters.fromJson(stored(new JsonObject().put("work_mem", value))), value);
        }
    }

    @Test void anEmptyValueAndOtherCharactersAreAllowed() {
        var parameters = new PgClusterParameters(Map.of("search_path", "", "log_line_prefix", "%m\t[%p] é"));
        assertEquals("", parameters.parameters().get("search_path"));
        assertEquals("%m\t[%p] é", parameters.parameters().get("log_line_prefix"));
    }

    @Test void aNullMapNameOrValueIsRefused() {
        assertThrows(NullPointerException.class, () -> new PgClusterParameters(null));
        var nullName = new HashMap<String, String>();
        nullName.put(null, "1");
        assertThrows(NullPointerException.class, () -> new PgClusterParameters(nullName));
        var nullValue = new HashMap<String, String>();
        nullValue.put("work_mem", null);
        assertThrows(NullPointerException.class, () -> new PgClusterParameters(nullValue));
        assertThrows(NullPointerException.class, () -> PgClusterParameters.fromJson(null));
    }

    @Test void aStoredValueThatIsNotAStringIsRefused() {
        for (Object value : new Object[] {150, 1.5, true, null, new JsonObject(), new io.vertx.core.json.JsonArray()}) {
            JsonObject json = stored(new JsonObject().put("max_connections", value));
            var failure = assertThrows(IllegalArgumentException.class, () -> PgClusterParameters.fromJson(json),
                String.valueOf(value));
            assertEquals("Parameter max_connections is not a string: " + value, failure.getMessage());
        }
    }

    @Test void aStoredFormWithAnUnknownOrMissingFieldIsRefused() {
        for (JsonObject json : List.of(new JsonObject(), stored(new JsonObject()).put("revision", 1),
                new JsonObject().put("postgresql", new JsonObject()))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> PgClusterParameters.fromJson(json),
                json.encode());
            assertEquals("Cluster parameters must have exactly the field parameters: " + json.fieldNames(),
                failure.getMessage());
        }
    }

    @Test void aStoredParametersFieldThatIsNotAnObjectIsRefused() {
        for (Object value : new Object[] {"work_mem=8MB", 1, null, new io.vertx.core.json.JsonArray()}) {
            JsonObject json = new JsonObject().put("parameters", value);
            var failure = assertThrows(IllegalArgumentException.class, () -> PgClusterParameters.fromJson(json),
                String.valueOf(value));
            assertEquals("Cluster parameters field parameters is not an object: " + value, failure.getMessage());
        }
    }

    @Test void theRenderingIsTheHeaderThenOneQuotedLinePerParameterInNameOrder() {
        var parameters = new PgClusterParameters(Map.of("work_mem", "8MB", "max_connections", "150"));
        assertEquals(HEADER
            + "max_connections = '150'\n"
            + "work_mem = '8MB'\n", parameters.render());
    }

    @Test void theRenderingDoublesEachSingleQuoteAndEachBackslash() {
        // PostgreSQL 15.13 read this exact line back as the 13 characters  it's a\b "q"  followed by a space. That run
        // showed the rule: two quotes are one quote, two backslashes are one backslash, a double quote is plain text.
        var parameters = new PgClusterParameters(Map.of("log_line_prefix", "it's a\\b \"q\" "));
        assertEquals(HEADER
            + "log_line_prefix = 'it''s a\\\\b \"q\" '\n", parameters.render());
        var twice = new PgClusterParameters(Map.of("application_name", "''\\\\"));
        assertTrue(twice.render().endsWith("application_name = '''''\\\\\\\\'\n"), twice.render());
    }

    @Test void aRecordWithNoParametersRendersOnlyTheHeader() {
        var parameters = new PgClusterParameters(Map.of());
        assertEquals(HEADER, parameters.render());
        assertEquals(new JsonObject().put("parameters", new JsonObject()), parameters.toJson());
        assertEquals(parameters, PgClusterParameters.fromJson(parameters.toJson()));
    }

    private static JsonObject stored(JsonObject parameters) {
        return new JsonObject().put("parameters", parameters);
    }
}
