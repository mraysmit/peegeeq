package dev.mars.peegeeq.api.setup;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class DatabaseSetupStatusTest {

    @Test
    void testEnumValues() {
        assertEquals(5, DatabaseSetupStatus.values().length);
        assertEquals(DatabaseSetupStatus.CREATING, DatabaseSetupStatus.valueOf("CREATING"));
        assertEquals(DatabaseSetupStatus.ACTIVE, DatabaseSetupStatus.valueOf("ACTIVE"));
        assertEquals(DatabaseSetupStatus.DESTROYING, DatabaseSetupStatus.valueOf("DESTROYING"));
        assertEquals(DatabaseSetupStatus.DESTROYED, DatabaseSetupStatus.valueOf("DESTROYED"));
        assertEquals(DatabaseSetupStatus.FAILED, DatabaseSetupStatus.valueOf("FAILED"));
    }
}
