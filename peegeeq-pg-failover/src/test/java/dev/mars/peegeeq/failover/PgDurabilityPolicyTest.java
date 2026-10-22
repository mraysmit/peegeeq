package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** The durability policy: the generated setting, the JSON form, and the coverage rule. */
@Tag(TestCategories.CORE)
class PgDurabilityPolicyTest {
    private static final PgDurabilityPolicy POLICY = new PgDurabilityPolicy(1, List.of("pg-node-2", "pg-node-3"));
    private static final PgLsn BOUNDARY = PgLsn.parse("0/3000148");

    @Test void generatesEveryRequiredStandbyIndividuallyQuoted() {
        assertEquals("ANY 2 (\"pg-node-2\", \"pg-node-3\")", POLICY.synchronousStandbyNames());
        assertEquals("ANY 1 (\"pg-node-3\")",
            new PgDurabilityPolicy(2, List.of("pg-node-3")).synchronousStandbyNames());
    }

    @Test void rejectsAnEmptyRepeatedOrInvalidStandbySet() {
        assertThrows(IllegalArgumentException.class, () -> new PgDurabilityPolicy(1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PgDurabilityPolicy(1, List.of("pg-node-2", "pg-node-2")));
        assertThrows(IllegalArgumentException.class, () -> new PgDurabilityPolicy(1, List.of("pg-node-2", "PG-NODE-2")));
        assertThrows(IllegalArgumentException.class, () -> new PgDurabilityPolicy(1, List.of("pg\"node")));
        assertThrows(IllegalArgumentException.class, () -> new PgDurabilityPolicy(0, List.of("pg-node-2")));
    }

    @Test void readsAndWritesTheControlRecordForm() {
        var json = new JsonObject().put("revision", 1L)
            .put("requiredStandbyNodeIds", new JsonArray().add("pg-node-2").add("pg-node-3"));
        assertEquals(POLICY, PgDurabilityPolicy.fromJson(json));
        assertEquals(json, POLICY.toJson());
    }

    @Test void rejectsAControlRecordFormWithOtherFields() {
        var unknown = POLICY.toJson().put("count", 2);
        assertThrows(IllegalArgumentException.class, () -> PgDurabilityPolicy.fromJson(unknown));
        var missing = POLICY.toJson();
        missing.remove("revision");
        assertThrows(IllegalArgumentException.class, () -> PgDurabilityPolicy.fromJson(missing));
        var notText = POLICY.toJson().put("requiredStandbyNodeIds", new JsonArray().add(2));
        assertThrows(IllegalArgumentException.class, () -> PgDurabilityPolicy.fromJson(notText));
    }

    @Test void everyRequiredStandbyStreamingUnderItsOwnRoleAtTheBoundaryIsCovered() {
        var peers = new ArrayList<>(covered());
        // A replication client that names no required standby is not part of the rule.
        peers.add(new PgReplicationPeer("backup", "pg_basebackup", "backup", "async", null, null));
        assertDoesNotThrow(() -> POLICY.requireCoveredAt(BOUNDARY, peers));
    }

    @Test void aMissingRequiredStandbyIsNotCovered() {
        refused(List.of(peer("pg-node-2", "pg-node-2")), "pg-node-3 is not connected");
    }

    @Test void twoConnectionsNamingOneStandbyAreNotCovered() {
        var peers = new ArrayList<>(covered());
        peers.add(peer("pg-node-3", "pg-node-3"));
        refused(peers, "pg-node-3 has 2 replication connections");
    }

    @Test void aStandbyNameFromAnotherRoleIsNotNodeIdentity() {
        refused(List.of(peer("pg-node-2", "pg-node-2"), peer("replicator", "pg-node-3")),
            "does not authenticate as node pg-node-3");
    }

    @Test void aNameThatDiffersOnlyInCaseIsNotNodeIdentity() {
        refused(List.of(peer("pg-node-2", "pg-node-2"), peer("pg-node-3", "PG-NODE-3")),
            "does not authenticate as node pg-node-3");
    }

    @Test void aStandbyRoleUnderAnotherNameIsRejected() {
        var peers = new ArrayList<>(covered());
        peers.add(peer("pg-node-2", "copy"));
        refused(peers, "Role pg-node-2 replicates as copy");
    }

    @Test void aStandbyThatIsNotStreamingIsNotCovered() {
        refused(List.of(peer("pg-node-2", "pg-node-2"),
            new PgReplicationPeer("pg-node-3", "pg-node-3", "catchup", "quorum", BOUNDARY, BOUNDARY)),
            "pg-node-3 is not streaming");
    }

    @Test void aStandbyBehindTheBoundaryIsNotCovered() {
        var behind = PgLsn.parse("0/3000100");
        refused(List.of(peer("pg-node-2", "pg-node-2"),
            new PgReplicationPeer("pg-node-3", "pg-node-3", "streaming", "quorum", behind, behind)),
            "pg-node-3 has not flushed");
        refused(List.of(peer("pg-node-2", "pg-node-2"),
            new PgReplicationPeer("pg-node-3", "pg-node-3", "streaming", "quorum", BOUNDARY, behind)),
            "pg-node-3 has not replayed");
        refused(List.of(peer("pg-node-2", "pg-node-2"),
            new PgReplicationPeer("pg-node-3", "pg-node-3", "streaming", "quorum", null, null)),
            "pg-node-3 has not flushed");
    }

    private static void refused(List<PgReplicationPeer> peers, String reason) {
        var failure = assertThrows(PgDurabilityException.class, () -> POLICY.requireCoveredAt(BOUNDARY, peers));
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
    }

    private static List<PgReplicationPeer> covered() {
        return List.of(peer("pg-node-2", "pg-node-2"), peer("pg-node-3", "pg-node-3"));
    }

    private static PgReplicationPeer peer(String role, String applicationName) {
        var ahead = PgLsn.parse("0/3000200");
        return new PgReplicationPeer(role, applicationName, "streaming", "quorum", ahead, BOUNDARY);
    }
}
