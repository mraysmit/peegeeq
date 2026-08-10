/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.mars.peegeeq.integration.smoke;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.client.PeeGeeQClient;
import dev.mars.peegeeq.client.PeeGeeQRestClient;
import dev.mars.peegeeq.client.config.ClientConfig;
import dev.mars.peegeeq.client.dto.ConsumerGroupInfo;
import dev.mars.peegeeq.client.dto.SubscriptionOptionsRequest;
import dev.mars.peegeeq.integration.SmokeTestBase;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the peegeeq-rest-client consumer-group methods against the REAL endpoint
 * contracts (consumer-groups group of the module audit, 2026-08-10).
 *
 * <p>All five method groups were written against an imagined contract:
 * {@code createConsumerGroup()} posted a Vert.x {@code JsonObject} body that the
 * client's plain Jackson mapper rendered as {@code {"map":{...},"empty":false}}
 * (the cross-cutting request-body defect, repaired at the helper level) and then
 * strict-parsed the {@code {message, groupName, setupId, queueName, groupId, ...}}
 * payload into the dto; {@code listConsumerGroups()} called
 * {@code bodyAsJsonArray()} on an endpoint that wraps its array in a
 * {@code groups} key with epoch-millis timestamps; {@code getConsumerGroup()}
 * strict-parsed the same object shape; {@code joinConsumerGroup()} expected the
 * dto's {@code memberId} field name when the server's member id key is
 * {@code consumerId}; and the subscription options pair serialized request keys
 * the server never reads (maxConcurrency/visibilityTimeoutMs/...) and
 * strict-parsed responses whose real options live in a nested
 * {@code subscriptionOptions} object. Every one of these calls failed against a
 * real server.
 */
@ExtendWith(VertxExtension.class)
@DisplayName("REST client consumer-groups contract smoke tests")
class RestClientConsumerGroupsContractSmokeTest extends SmokeTestBase {

    private static PeeGeeQClient client;

    @BeforeAll
    static void createClient() {
        client = PeeGeeQRestClient.create(vertx, ClientConfig.builder()
                .baseUrl("http://localhost:" + actualServerPort)
                .build());
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Test
    @DisplayName("consumer group create, list, get, join and subscription options map the real payloads")
    void consumerGroupsFlowMapsTheRealPayloads(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String queueName = "client-cgroups-queue";
        String groupName = "client-contract-group";
        String memberName = "client-contract-member";
        JsonObject requestJson = createDatabaseSetupRequest(setupId, queueName);
        JsonObject dbJson = requestJson.getJsonObject("databaseConfig");

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId,
                new DatabaseConfig.Builder()
                        .host(dbJson.getString("host"))
                        .port(dbJson.getInteger("port"))
                        .databaseName(dbJson.getString("databaseName"))
                        .username(dbJson.getString("username"))
                        .password(dbJson.getString("password"))
                        .schema(dbJson.getString("schema"))
                        .templateDatabase(dbJson.getString("templateDatabase"))
                        .encoding(dbJson.getString("encoding"))
                        .build(),
                List.of(new QueueConfig.Builder()
                        .queueName(queueName)
                        .maxRetries(3)
                        .visibilityTimeoutSeconds(30)
                        .build()),
                List.of(),
                null);

        client.createSetup(request)
            .compose(created -> {
                testContext.verify(() -> assertEquals(setupId, created.setupId(),
                    "createSetup must map the created setupId; got " + created));
                return client.createConsumerGroup(setupId, queueName, groupName);
            })
            .compose(createdGroup -> {
                testContext.verify(() -> {
                    assertEquals(groupName, createdGroup.groupName(),
                        "createConsumerGroup must map groupName from the real 201 payload; got " + createdGroup);
                    assertEquals(queueName, createdGroup.queueName(),
                        "createConsumerGroup must map queueName from the real 201 payload; got " + createdGroup);
                    assertNull(createdGroup.memberCount(),
                        "the create payload carries no member count - absence must map to null, not 0; got " + createdGroup);
                    assertNull(createdGroup.lastActivity(),
                        "the create payload carries no lastActivity - absence must map to null; got " + createdGroup);
                });
                return client.listConsumerGroups(setupId, queueName);
            })
            .compose(groups -> {
                testContext.verify(() -> {
                    Optional<ConsumerGroupInfo> match = groups.stream()
                        .filter(g -> groupName.equals(g.groupName()))
                        .findFirst();
                    assertTrue(match.isPresent(),
                        "listConsumerGroups must unwrap the groups array and contain the created group; got " + groups);
                    assertEquals(queueName, match.get().queueName(),
                        "each listed group must map queueName; got " + match.get());
                });
                return client.getConsumerGroup(setupId, queueName, groupName);
            })
            .compose(group -> {
                testContext.verify(() -> {
                    assertEquals(groupName, group.groupName(),
                        "getConsumerGroup must map groupName; got " + group);
                    assertEquals(0, (int) group.memberCount(),
                        "getConsumerGroup must map the real memberCount (0 before any join); got " + group);
                });
                return client.joinConsumerGroup(setupId, queueName, groupName, memberName);
            })
            .compose(member -> {
                testContext.verify(() -> {
                    assertNotNull(member.memberId(),
                        "joinConsumerGroup must map memberId from the payload's consumerId key; got " + member);
                    assertTrue(member.memberId().startsWith(memberName + "-"),
                        "the mapped memberId is the server's consumerId (memberName + counter); got " + member);
                    assertEquals(memberName, member.memberName(),
                        "joinConsumerGroup must map memberName; got " + member);
                    assertEquals(groupName, member.groupName(),
                        "joinConsumerGroup must map groupName; got " + member);
                    assertNotNull(member.joinedAt(),
                        "joinConsumerGroup must parse the ISO joinedAt string; got " + member);
                    // The payload's memberCount is the group's ACTIVE consumer count and
                    // the joined member of a not-yet-started group is inactive, so the
                    // server reports 0 here (verified against the real handler payload).
                    assertEquals(0, member.memberCount(),
                        "joinConsumerGroup must map the server's active-consumer memberCount; got " + member);
                    assertFalse(member.isActive(),
                        "a member joined to a not-started group is inactive on the real payload; got " + member);
                });
                return client.updateSubscriptionOptions(setupId, queueName, groupName,
                    new SubscriptionOptionsRequest()
                        .withStartPosition("FROM_NOW")
                        .withHeartbeatIntervalSeconds(15)
                        .withHeartbeatTimeoutSeconds(45));
            })
            .compose(updated -> {
                testContext.verify(() -> {
                    assertEquals(setupId, updated.setupId(),
                        "updateSubscriptionOptions must echo setupId; got " + updated);
                    assertEquals(queueName, updated.queueName(),
                        "updateSubscriptionOptions must echo queueName; got " + updated);
                    assertEquals(groupName, updated.groupName(),
                        "updateSubscriptionOptions must echo groupName; got " + updated);
                    assertEquals("FROM_NOW", updated.startPosition(),
                        "updateSubscriptionOptions must unwrap subscriptionOptions.startPosition; got " + updated);
                    assertEquals(15, updated.heartbeatIntervalSeconds(),
                        "updateSubscriptionOptions must map heartbeatIntervalSeconds; got " + updated);
                    assertEquals(45, updated.heartbeatTimeoutSeconds(),
                        "updateSubscriptionOptions must map heartbeatTimeoutSeconds; got " + updated);
                    assertNull(updated.status(),
                        "the update payload emits no status key - absence must map to null; got " + updated);
                });
                return client.getSubscriptionOptions(setupId, queueName, groupName);
            })
            .compose(options -> {
                testContext.verify(() -> {
                    // FROM_NOW does not round-trip by server design: subscribe resolves
                    // it to start_from_message_id = maxId + 1 (1 on this empty queue),
                    // and the get endpoint reconstructs id 1 as FROM_BEGINNING.
                    assertEquals("FROM_BEGINNING", options.startPosition(),
                        "getSubscriptionOptions must map the server's watermark reconstruction of FROM_NOW on an empty queue; got " + options);
                    assertEquals(15, options.heartbeatIntervalSeconds(),
                        "getSubscriptionOptions must round-trip heartbeatIntervalSeconds; got " + options);
                    assertEquals(45, options.heartbeatTimeoutSeconds(),
                        "getSubscriptionOptions must round-trip heartbeatTimeoutSeconds; got " + options);
                    assertNotNull(options.status(),
                        "the get payload emits a status for a configured subscription; got " + options);
                });
                return client.updateSubscriptionOptions(setupId, queueName, groupName,
                    new SubscriptionOptionsRequest()
                        .withStartPosition("FROM_MESSAGE_ID")
                        .withStartFromMessageId(42L)
                        .withHeartbeatIntervalSeconds(15)
                        .withHeartbeatTimeoutSeconds(45));
            })
            .compose(updated -> {
                testContext.verify(() -> assertEquals(42L, updated.startFromMessageId(),
                    "updateSubscriptionOptions must map the echoed startFromMessageId; got " + updated));
                return client.getSubscriptionOptions(setupId, queueName, groupName);
            })
            .compose(options -> {
                testContext.verify(() -> {
                    assertEquals("FROM_MESSAGE_ID", options.startPosition(),
                        "getSubscriptionOptions must round-trip the FROM_MESSAGE_ID start position; got " + options);
                    assertEquals(42L, options.startFromMessageId(),
                        "getSubscriptionOptions must round-trip startFromMessageId; got " + options);
                });
                return cleanupSetupStrict(setupId);
            })
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }
}
