package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.failover.consul.ConsulLeaseCoordinator;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Ownership operations of one elector run one at a time in arrival order, against a real
 * coordinator. An operation that arrives while another is in flight waits. It is not refused.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PgPrimaryElectorQueueingIntegrationTest {
    private static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    private static final String TOKEN = "unused-without-acls";
    private static final GenericContainer<?> CONSUL = new GenericContainer<>("hashicorp/consul:1.22.1")
        .withExposedPorts(8500)
        .withCommand("agent", "-dev", "-client=0.0.0.0", "-node=consul-1", "-log-level=warn")
        .waitingFor(Wait.forHttp("/v1/status/leader").forPort(8500).withStartupTimeout(Duration.ofSeconds(60)));
    private Vertx vertx;
    private WebClient admin;
    private PgNodeConfig config;
    private ObservedLeaseCoordinator coordinator;
    private PgPrimaryElector elector;

    @BeforeAll static void startConsul() {
        CONSUL.start();
    }

    @AfterAll static void stopConsul() {
        CONSUL.stop();
    }

    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        admin = WebClient.create(vertx);
        config = new PgNodeConfig("elector-queue-tests", "inc-" + UUID.randomUUID(), "pg-node-1", MEMBERS,
            Duration.ofSeconds(20), Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8),
            PgWatchdogMode.OFF);
        coordinator = new ObservedLeaseCoordinator(new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        elector = new PgPrimaryElector(config, coordinator);
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        admin.close();
        elector.close().onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void updateDuringAnInFlightRenewalWaitsAndSucceeds(VertxTestContext context) {
        Promise<Void> entered = Promise.promise();
        Promise<Void> release = Promise.promise();
        elector.createInitialIntent(initialIntent()).compose(held -> {
            coordinator.onRenew(asked -> {
                entered.tryComplete();
                return release.future().compose(ignored -> coordinator.realRenew(asked));
            });
            Future<PgControlRecord> renewal = elector.renew();
            return entered.future().compose(ignored -> {
                Future<PgControlRecord> update = elector.update(held, servingIntent(held));
                assertFalse(update.isComplete(), "An update that arrived during a renewal was refused");
                release.complete();
                return renewal.compose(renewed -> update);
            });
        }).onSuccess(updated -> context.verify(() -> {
            assertEquals("SERVING", updated.intent().getString("phase"));
            assertTrue(elector.holds(updated));
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void renewalDuringAnInFlightUpdateWaitsThenRenewsTheUpdatedRecord(VertxTestContext context) {
        Promise<Void> entered = Promise.promise();
        Promise<Void> release = Promise.promise();
        elector.createInitialIntent(initialIntent()).compose(held -> {
            coordinator.onUpdate((asked, intent) -> {
                entered.tryComplete();
                return release.future().compose(ignored -> coordinator.realUpdate(asked, intent));
            });
            Future<PgControlRecord> update = elector.update(held, servingIntent(held));
            return entered.future().compose(ignored -> {
                Future<PgControlRecord> renewal = elector.renew();
                assertFalse(renewal.isComplete(), "A renewal that arrived during an update was refused");
                assertTrue(coordinator.renewStarts().isEmpty(), "The renewal reached the coordinator during the update");
                release.complete();
                return update.compose(updated -> renewal.map(renewed -> {
                    assertEquals(updated, renewed);
                    return renewed;
                }));
            });
        }).onSuccess(renewed -> context.verify(() -> {
            assertEquals(1, coordinator.renewStarts().size());
            assertTrue(elector.holds(renewed));
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void releaseDuringAnInFlightRenewalWaitsAndSucceeds(VertxTestContext context) {
        Promise<Void> entered = Promise.promise();
        Promise<Void> release = Promise.promise();
        elector.createInitialIntent(initialIntent()).compose(held -> {
            coordinator.onRenew(asked -> {
                entered.tryComplete();
                return release.future().compose(ignored -> coordinator.realRenew(asked));
            });
            Future<PgControlRecord> renewal = elector.renew();
            return entered.future().compose(ignored -> {
                Future<PgControlRecord> released = elector.release(held);
                assertFalse(released.isComplete(), "A release that arrived during a renewal was refused");
                release.complete();
                return renewal.compose(renewed -> released);
            });
        }).onSuccess(released -> context.verify(() -> {
            assertNull(released.leaseHolder());
            assertFalse(elector.hasFreshOwnership());
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Lease operation refused",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void anOperationQueuedBehindAFailedOperationIsRefused(VertxTestContext context) {
        Promise<Void> entered = Promise.promise();
        Promise<Void> release = Promise.promise();
        elector.createInitialIntent(initialIntent()).compose(held -> {
            coordinator.onRenew(asked -> {
                entered.tryComplete();
                return release.future().compose(ignored ->
                    Future.<PgControlRecord>failedFuture(new IllegalStateException("coordinator failure")));
            });
            Future<PgControlRecord> failing = elector.renew();
            return entered.future().compose(ignored -> {
                Future<PgControlRecord> queued = elector.renew();
                release.complete();
                return failing.transform(first -> {
                    assertTrue(first.failed());
                    return queued;
                });
            });
        }).transform(second -> {
            assertTrue(second.failed(), "An operation queued behind a failed one ran on retired ownership");
            assertInstanceOf(PgLeaseProtocolException.class, second.cause());
            return Future.<Void>succeededFuture();
        }).onSuccess(ignored -> context.verify(() -> {
            assertFalse(elector.hasFreshOwnership());
            assertEquals(1, coordinator.renewStarts().size(), "The queued renewal reached the coordinator");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    /** Serving intent: the pending policy becomes the confirmed policy. */
    private static JsonObject servingIntent(PgControlRecord held) {
        JsonObject intent = held.intent();
        JsonObject policy = (JsonObject) intent.remove("pendingDurabilityPolicy");
        return intent.put("phase", "SERVING").put("durabilityPolicy", policy);
    }

    private static JsonObject initialIntent() {
        return new JsonObject().put("writerNodeId", "pg-node-1").put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("pendingDurabilityPolicy", new JsonObject().put("revision", 1L)
                .put("requiredStandbyNodeIds", new JsonArray().add("pg-node-2").add("pg-node-3")));
    }

    private static URI endpoint() {
        return URI.create("http://" + CONSUL.getHost() + ":" + CONSUL.getMappedPort(8500));
    }

    private Future<Void> waitForLeader(long deadline) {
        return admin.getAbs(endpoint() + "/v1/status/leader").timeout(1000).send().compose(response -> {
            if (response.statusCode() == 200 && response.bodyAsString().length() > 2) return Future.succeededFuture();
            if (System.nanoTime() >= deadline) return Future.failedFuture(new AssertionError("Consul has no leader"));
            return vertx.timer(100).compose(ignored -> waitForLeader(deadline));
        });
    }
}
