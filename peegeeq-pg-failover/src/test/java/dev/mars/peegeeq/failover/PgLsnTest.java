package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.CORE)
class PgLsnTest {

    @Test void parsesThePostgresTextForm() {
        assertEquals((0x16L << 32) | 0xB374D848L, PgLsn.parse("16/B374D848").value());
        assertEquals(0L, PgLsn.parse("0/0").value());
        assertEquals("16/B374D848", PgLsn.parse("16/b374d848").toString());
    }

    @Test void ordersByPositionAcrossTheHighWord() {
        var low = PgLsn.parse("0/FFFFFFFF");
        var high = PgLsn.parse("1/0");
        assertTrue(high.reached(low));
        assertFalse(low.reached(high));
        assertTrue(low.reached(PgLsn.parse("0/FFFFFFFF")));
        assertTrue(PgLsn.parse("FFFFFFFF/0").reached(high), "The highest word is compared unsigned");
    }

    @Test void rejectsTextThatIsNotAPosition() {
        for (String text : Arrays.asList(null, "", "1", "0/G", "/1", "1/", "123456789/0", "0/0/0", " 0/1")) {
            assertThrows(IllegalArgumentException.class, () -> PgLsn.parse(text), String.valueOf(text));
        }
    }
}
