package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.CORE)
class PgMemberPositionTest {
    private static final PgLsn LSN = PgLsn.parse("0/5025A88");

    @Test void aPositionNamesAMemberATimelineAndAWalPosition() {
        var position = new PgMemberPosition("pg-node-2", 2, LSN);
        assertEquals("pg-node-2", position.nodeId());
        assertEquals(2, position.timeline());
        assertEquals(LSN, position.lsn());
    }

    @Test void rejectsAnInvalidIdentityATimelineBelowOneAndAMissingWalPosition() {
        assertThrows(IllegalArgumentException.class, () -> new PgMemberPosition("../other", 1, LSN));
        assertThrows(IllegalArgumentException.class, () -> new PgMemberPosition("pg-node-2", 0, LSN));
        assertThrows(NullPointerException.class, () -> new PgMemberPosition("pg-node-2", 1, null));
    }
}
