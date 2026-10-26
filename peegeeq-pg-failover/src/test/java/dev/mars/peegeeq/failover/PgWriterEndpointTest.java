package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The writer endpoint a standby follows, and the connection string built from it. */
@Tag(TestCategories.CORE)
class PgWriterEndpointTest {
    private static final String PASSFILE = "/var/lib/postgresql/node-3.pgpass";

    @Test void theConnectionStringHasTheEndpointTheNodeIdentityAndThePasswordFileAndNoPassword() {
        var endpoint = new PgWriterEndpoint("pg-node-2", 5432, PASSFILE);
        assertEquals("host=pg-node-2 port=5432 user=pg-node-3 application_name=pg-node-3 passfile=" + PASSFILE,
            endpoint.connectionInfo("pg-node-3"));
        assertFalse(endpoint.connectionInfo("pg-node-3").contains("password="));
    }

    @Test void anIpAddressIsAValidHost() {
        assertEquals("10.0.0.7", new PgWriterEndpoint("10.0.0.7", 5433, PASSFILE).host());
    }

    @Test void theRoleAndTheApplicationNameAreTheNodeIdentityAndNothingElse() {
        var endpoint = new PgWriterEndpoint("pg-node-2", 5432, PASSFILE);
        assertThrows(IllegalArgumentException.class, () -> endpoint.connectionInfo("pg node"));
        assertThrows(IllegalArgumentException.class, () -> endpoint.connectionInfo("node'x"));
        assertThrows(IllegalArgumentException.class, () -> endpoint.connectionInfo(null));
    }

    @Test void aHostThatCouldChangeTheConnectionStringIsRejected() {
        for (String host : new String[] {null, "", " ", "pg node", "pg-node-2 host=evil", "pg'node", "pg=node",
                "pg\"node", "-leading", "a/b"}) {
            assertThrows(IllegalArgumentException.class, () -> new PgWriterEndpoint(host, 5432, PASSFILE),
                "Accepted host: " + host);
        }
    }

    @Test void aPortOutsideTheValidRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PgWriterEndpoint("pg-node-2", 0, PASSFILE));
        assertThrows(IllegalArgumentException.class, () -> new PgWriterEndpoint("pg-node-2", -1, PASSFILE));
        assertThrows(IllegalArgumentException.class, () -> new PgWriterEndpoint("pg-node-2", 65536, PASSFILE));
        assertEquals(65535, new PgWriterEndpoint("pg-node-2", 65535, PASSFILE).port());
    }

    @Test void aPasswordFilePathThatCouldChangeTheConnectionStringIsRejected() {
        for (String passfile : new String[] {null, "", "relative.pgpass", "/path with space", "/path'quote",
                "/path\"quote", "/a=b", "/a\\b"}) {
            assertThrows(IllegalArgumentException.class, () -> new PgWriterEndpoint("pg-node-2", 5432, passfile),
                "Accepted password file: " + passfile);
        }
    }
}
