package dev.mars.peegeeq.api.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class DatabaseSetupRequestTest {

    @Test
    void testConstructorAndGetters() {
        DatabaseConfig dbConfig = new DatabaseConfig("host", 5432, "db", "user", "pass", "schema", false, null, null, null);
        List<QueueConfig> queues = Collections.emptyList();
        List<EventStoreConfig> eventStores = Collections.emptyList();
        Map<String, Object> props = Collections.singletonMap("key", "value");

        DatabaseSetupRequest request = new DatabaseSetupRequest(
            "setup-1",
            dbConfig,
            queues,
            eventStores,
            props
        );

        assertEquals("setup-1", request.getSetupId());
        assertEquals(dbConfig, request.getDatabaseConfig());
        assertEquals(queues, request.getQueues());
        assertEquals(eventStores, request.getEventStores());
        assertEquals(props, request.getAdditionalProperties());
    }

    @Test
    void testDefensiveCopiesAndImmutability() {
        DatabaseConfig dbConfig = new DatabaseConfig("host", 5432, "db", "user", "pass", "schema", false, null, null, null);
        List<QueueConfig> queues = new ArrayList<>();
        List<EventStoreConfig> eventStores = new ArrayList<>();
        Map<String, Object> props = new HashMap<>();
        props.put("key", "value");

        DatabaseSetupRequest request = new DatabaseSetupRequest(
            "setup-immutability",
            dbConfig,
            queues,
            eventStores,
            props
        );

        // Mutate source collections after construction and verify request state is unchanged.
        props.put("after", "mutation");
        assertEquals(1, request.getAdditionalProperties().size());
        assertEquals("value", request.getAdditionalProperties().get("key"));

        assertThrows(UnsupportedOperationException.class, () -> request.getQueues().add(null));
        assertThrows(UnsupportedOperationException.class, () -> request.getEventStores().add(null));
        assertThrows(UnsupportedOperationException.class, () -> request.getAdditionalProperties().put("x", "y"));
    }

    @Test
    void testNullCollectionsDefaultToEmpty() {
        DatabaseConfig dbConfig = new DatabaseConfig("host", 5432, "db", "user", "pass", "schema", false, null, null, null);

        DatabaseSetupRequest request = new DatabaseSetupRequest(
            "setup-empty",
            dbConfig,
            null,
            null,
            null
        );

        assertNotNull(request.getQueues());
        assertTrue(request.getQueues().isEmpty());
        assertNotNull(request.getEventStores());
        assertTrue(request.getEventStores().isEmpty());
        assertNotNull(request.getAdditionalProperties());
        assertTrue(request.getAdditionalProperties().isEmpty());
    }

    @Test
    void testPersistBindingDefaultsToFalse() {
        DatabaseConfig dbConfig = new DatabaseConfig("host", 5432, "db", "user", "pass", "schema", false, null, null, null);

        DatabaseSetupRequest request = new DatabaseSetupRequest(
            "setup-no-persist", dbConfig, List.of(), List.of(), Map.of());

        assertFalse(request.isPersistBinding(),
            "persistBinding must default to false — remembering a binding is opt-in per request");
    }

    @Test
    void testPersistBindingExplicitlyEnabled() {
        DatabaseConfig dbConfig = new DatabaseConfig("host", 5432, "db", "user", "pass", "schema", false, null, null, null);

        DatabaseSetupRequest request = new DatabaseSetupRequest(
            "setup-persist", dbConfig, List.of(), List.of(), Map.of(), true);

        assertTrue(request.isPersistBinding());
    }

    @Test
    void testCredentialRefDefaultsToNull() {
        DatabaseConfig dbConfig = new DatabaseConfig("host", 5432, "db", "user", "pass", "schema", false, null, null, null);

        DatabaseSetupRequest request = new DatabaseSetupRequest(
            "setup-no-ref", dbConfig, List.of(), List.of(), Map.of(), true);

        assertNull(request.getCredentialRef(),
            "credentialRef must default to null — supplied-at-connect mode has no reference");
    }

    @Test
    void testCredentialRefCarriedVerbatim() {
        DatabaseConfig dbConfig = new DatabaseConfig("host", 5432, "db", "user", "pass", "schema", false, null, null, null);

        DatabaseSetupRequest request = new DatabaseSetupRequest(
            "setup-ref", dbConfig, List.of(), List.of(), Map.of(), true, "vault://team/setup-db");

        assertEquals("vault://team/setup-db", request.getCredentialRef(),
            "the credential reference is opaque and must be carried verbatim");
    }

    @Test
    void testJsonWithCredentialRefDeserialises() throws Exception {
        String json = """
            {
              "setupId": "json-ref-setup",
              "databaseConfig": {
                "host": "localhost", "port": 5432, "databaseName": "db",
                "username": "user", "password": "pass", "schema": "s"
              },
              "persistBinding": true,
              "credentialRef": "vault://team/json-db"
            }
            """;

        DatabaseSetupRequest request = new ObjectMapper().readValue(json, DatabaseSetupRequest.class);

        assertTrue(request.isPersistBinding());
        assertEquals("vault://team/json-db", request.getCredentialRef());
    }

    @Test
    void testJsonWithoutPersistBindingDeserialisesToFalse() throws Exception {
        String json = """
            {
              "setupId": "json-setup",
              "databaseConfig": {
                "host": "localhost", "port": 5432, "databaseName": "db",
                "username": "user", "password": "pass", "schema": "s"
              }
            }
            """;

        DatabaseSetupRequest request = new ObjectMapper().readValue(json, DatabaseSetupRequest.class);

        assertEquals("json-setup", request.getSetupId());
        assertFalse(request.isPersistBinding(),
            "existing clients that do not send persistBinding must keep today's behaviour");
    }

    @Test
    void testJsonWithPersistBindingTrueDeserialises() throws Exception {
        String json = """
            {
              "setupId": "json-persist-setup",
              "databaseConfig": {
                "host": "localhost", "port": 5432, "databaseName": "db",
                "username": "user", "password": "pass", "schema": "s"
              },
              "persistBinding": true
            }
            """;

        DatabaseSetupRequest request = new ObjectMapper().readValue(json, DatabaseSetupRequest.class);

        assertTrue(request.isPersistBinding());
    }

    @Test
    void testConstructorRejectsNullRequiredFields() {
        DatabaseConfig dbConfig = new DatabaseConfig("host", 5432, "db", "user", "pass", "schema", false, null, null, null);

        assertThrows(NullPointerException.class,
            () -> new DatabaseSetupRequest(null, dbConfig, List.of(), List.of(), Map.of()));

        assertThrows(NullPointerException.class,
            () -> new DatabaseSetupRequest("setup-id", null, List.of(), List.of(), Map.of()));
    }
}
