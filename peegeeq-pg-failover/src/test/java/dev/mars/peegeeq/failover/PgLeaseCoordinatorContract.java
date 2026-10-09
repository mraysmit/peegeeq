package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Coordinator-neutral contract for {@link PgLeaseCoordinator} adapters. Most cases run through
 * {@link PgPrimaryElector}; cases named {@code coordinator...} call the port directly.
 * Each adapter binds this suite to its real service.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public abstract class PgLeaseCoordinatorContract {
    private static final Logger logger = LoggerFactory.getLogger(PgLeaseCoordinatorContract.class);
    protected static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");

    /** Faults a binding injects between the intercepted coordinator and its service. */
    protected enum Fault {
        NONE,
        /** An authoritative read returns a body that is not a control record. */
        MALFORMED_READ,
        /** A read receives no reply. */
        SILENT_READ,
        /** A read reports absence without proof that it is current. */
        NON_AUTHORITATIVE_ABSENCE,
        /** A conditional write is not applied and its reply is not a valid result. */
        MALFORMED_MUTATION,
        /** A conditional write is not applied and receives no reply. */
        SILENT_MUTATION,
        /** A conditional write is applied and its reply is discarded. */
        DROP_MUTATION_REPLY,
        /** A conditional write is applied and its reply is held until delivered. */
        DELAY_MUTATION_REPLY,
        /** A renewal is applied and its reply is held until delivered. */
        DELAY_RENEWAL_REPLY
    }

    protected Vertx vertx;
    protected PgNodeConfig config;
    protected PgPrimaryElector owner;
    private final List<PgPrimaryElector> electors = new ArrayList<>();

    /** Prepares the binding for one test. On completion the service accepts lease requests from every member. */
    protected abstract Future<Void> prepareBinding();

    /** Restores any suspended service members and frees binding resources. */
    protected abstract Future<Void> releaseBinding();

    /** Node configuration with timing short enough to observe lease expiry. */
    protected abstract PgNodeConfig nodeConfig(String incarnation, String nodeId);

    /** An authorised coordinator for one node of one incarnation. */
    protected abstract Future<PgLeaseCoordinator> coordinator(PgNodeConfig node);

    /** A coordinator whose credentials the service rejects. */
    protected abstract Future<PgLeaseCoordinator> unauthorisedCoordinator(PgNodeConfig node);

    /** A coordinator routed through the binding's fault injector. No fault is active at first. */
    protected abstract Future<PgLeaseCoordinator> interceptedCoordinator(PgNodeConfig node);

    /** Selects the fault applied to the intercepted coordinator's matching requests. */
    protected abstract void inject(Fault fault);

    /** Completes when a delay fault has captured a reply. */
    protected abstract Future<Void> replyCaptured();

    /** Delivers the reply captured by a delay fault. */
    protected abstract void deliverCapturedReply();

    /** Changes the record's intent without the owner's knowledge. Ownership is preserved. */
    protected abstract Future<Void> overwriteIntentExternally(PgControlRecord record, JsonObject intent);

    /** Removes the control record without the owner's knowledge. */
    protected abstract Future<Void> deleteRecordExternally();

    /** Suspends enough service members to remove quorum. {@link #releaseBinding()} restores them. */
    protected abstract Future<Void> suspendQuorumMajority();

    @BeforeEach
    public void setUpContract(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        prepareBinding()
            .compose(ignored -> {
                config = nodeConfig("inc-" + UUID.randomUUID(), "pg-node-1");
                return coordinator(config);
            })
            .onSuccess(coordinator -> context.verify(() -> {
                owner = elector(config, coordinator);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @AfterEach
    public void tearDownContract(VertxTestContext context) {
        releaseBinding().transform(restored -> Future.all(electors.stream()
                .map(PgPrimaryElector::close).toList())
            .compose(ignored -> restored.succeeded()
                ? Future.<Void>succeededFuture() : Future.failedFuture(restored.cause())))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- initial acquisition

    @Test public void initialAcquisitionGrantsFreshOwnership(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .onSuccess(record -> context.verify(() -> {
                assertTrue(owner.hasFreshOwnership());
                assertEquals(config.controlName(), record.controlName());
                assertEquals("WITHDRAWN", record.intent().getString("phase"));
                assertNotNull(record.leaseHolder());
                assertTrue(record.generation() >= 1);
                assertTrue(record.revision() >= 1);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void simultaneousInitialOwnersCannotBothAcquire(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        coordinator(otherConfig).compose(coordinator -> {
            var other = elector(otherConfig, coordinator);
            Future<Boolean> first = owner.createInitialIntent(initialIntent("pg-node-1"))
                .transform(result -> Future.succeededFuture(result.succeeded()));
            Future<Boolean> second = other.createInitialIntent(initialIntent("pg-node-2"))
                .transform(result -> Future.succeededFuture(result.succeeded()));
            return Future.all(first, second).map(results ->
                (results.<Boolean>resultAt(0) ? 1 : 0) + (results.<Boolean>resultAt(1) ? 1 : 0));
        }).onSuccess(winners -> context.verify(() -> {
            assertEquals(1, winners);
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void restartedElectorCannotReinitialiseExistingHistory(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(original -> owner.close()
            .compose(ignored -> coordinator(config))
            .compose(coordinator -> {
                var restarted = elector(config, coordinator);
                return restarted.createInitialIntent(initialIntent("pg-node-1")).transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertFalse(restarted.hasFreshOwnership());
                    return restarted.read();
                });
            }).map(current -> {
                assertEquals(original, current.orElseThrow());
                return current;
            })).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void fractionalPolicyRevisionCannotCreateOwnership(VertxTestContext context) {
        JsonObject intent = initialIntent("pg-node-1");
        intent.getJsonObject("pendingDurabilityPolicy").put("revision", 1.5);
        owner.createInitialIntent(intent).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgLeaseProtocolException.class, failure);
            assertFalse(owner.hasFreshOwnership());
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void initialPolicyNamingTheWriterCannotCreateOwnership(VertxTestContext context) {
        JsonObject intent = initialIntent("pg-node-1");
        intent.getJsonObject("pendingDurabilityPolicy")
            .put("requiredStandbyNodeIds", new JsonArray().add("pg-node-1").add("pg-node-2"));
        owner.createInitialIntent(intent).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgLeaseProtocolException.class, failure);
            assertFalse(owner.hasFreshOwnership());
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void accessDeniedCannotCreateAuthority(VertxTestContext context) {
        unauthorisedCoordinator(config).compose(coordinator -> {
            var denied = elector(config, coordinator);
            return denied.createInitialIntent(initialIntent("pg-node-1")).transform(result -> {
                assertTrue(result.failed());
                assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                assertFalse(denied.hasFreshOwnership());
                return Future.<Void>succeededFuture();
            });
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test public void namespaceIsolationPreservesIndependentOwnership(VertxTestContext context) {
        var otherConfig = nodeConfig("other-" + UUID.randomUUID(), "pg-node-1");
        coordinator(otherConfig).compose(coordinator -> {
            var other = elector(otherConfig, coordinator);
            return owner.createInitialIntent(initialIntent("pg-node-1"))
                .compose(first -> other.createInitialIntent(initialIntent("pg-node-1")))
                .map(second -> other);
        }).onSuccess(other -> context.verify(() -> {
            assertTrue(owner.hasFreshOwnership());
            assertTrue(other.hasFreshOwnership());
            context.completeNow();
        })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- update and renewal

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Lease operation refused",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void conflictingRevisionCannotOverwriteIntent(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(original -> {
            JsonObject changed = original.intent().put("phase", "FENCING");
            return owner.update(original, changed).compose(updated -> {
                assertTrue(updated.revision() > original.revision());
                assertEquals(original.generation(), updated.generation());
                return owner.update(original, original.intent()).transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    return owner.read();
                });
            });
        }).onSuccess(current -> context.verify(() -> {
            assertEquals("FENCING", current.orElseThrow().intent().getString("phase"));
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void externalRevisionChangeRollsBackUpdate(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(original ->
            overwriteIntentExternally(original, original.intent().put("phase", "FENCING"))
                .compose(ignored -> owner.update(original, original.intent().put("phase", "PROMOTING")))
                .transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertFalse(owner.hasFreshOwnership());
                    return owner.read();
                }).map(current -> {
                    assertEquals("FENCING", current.orElseThrow().intent().getString("phase"));
                    assertEquals(original.generation(), current.orElseThrow().generation());
                    return current;
                })).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test public void observedMissingRecordRetiresCachedOwnership(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(original -> deleteRecordExternally())
            .compose(ignored -> owner.read())
            .onSuccess(current -> context.verify(() -> {
                assertTrue(current.isEmpty());
                assertFalse(owner.hasFreshOwnership());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test public void renewalPreservesGenerationAndPolicy(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(original -> owner.renew()
            .map(renewed -> {
                assertEquals(original, renewed);
                return renewed;
            })).onSuccess(renewed -> context.verify(() -> {
                assertTrue(owner.hasFreshOwnership());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Lease operation refused",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void anotherNodeCannotRenewOwnersLease(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(record -> coordinator(otherConfig))
            .compose(coordinator -> elector(otherConfig, coordinator).renew())
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                assertTrue(owner.hasFreshOwnership());
                context.completeNow();
            })));
    }

    @Test public void coordinatorRejectsRenewalByAnotherNode(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(held -> coordinator(otherConfig)
            .compose(coordinator -> tracked(otherConfig, coordinator).renew(held))
            .transform(result -> {
                assertTrue(result.failed());
                assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                return owner.read();
            }).map(current -> {
                assertEquals(held, current.orElseThrow());
                return current;
            })).onSuccess(current -> context.verify(() -> {
                assertTrue(owner.hasFreshOwnership());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Lease operation refused",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void retiredOwnerCannotRegainAuthority(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(record -> {
            owner.retire();
            assertFalse(owner.hasFreshOwnership());
            return owner.renew();
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgLeaseProtocolException.class, failure);
            assertFalse(owner.hasFreshOwnership());
            context.completeNow();
        })));
    }

    // ---------------------------------------------------------------- close and expiry

    @Test public void closeKeepsControlHistoryAndLease(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(record -> owner.close()
            .compose(ignored -> coordinator(config))
            .compose(coordinator -> elector(config, coordinator).read())
            .map(current -> {
                assertEquals(record, current.orElseThrow());
                return current;
            })).onSuccess(current -> context.verify(() -> {
                assertFalse(owner.hasFreshOwnership());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test public void expiryRetainsPolicyHistoryAndWithdrawsOwnership(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(record ->
            waitForExpiry(owner, record, System.nanoTime() + TimeUnit.SECONDS.toNanos(40)))
            .onSuccess(record -> context.verify(() -> {
                assertNull(record.leaseHolder());
                assertEquals(1L, record.intent().getJsonObject("pendingDurabilityPolicy").getLong("revision"));
                assertFalse(owner.hasFreshOwnership());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- ownership budget

    @Test public void ownershipLapsesAtTheLeaseBudgetWithoutAWatchdog(VertxTestContext context) {
        assertOwnershipLapsesAtBudget(PgWatchdogMode.OFF, context);
    }

    @Test public void ownershipLapsesAtTheWatchdogBudgetWhenRequired(VertxTestContext context) {
        assertOwnershipLapsesAtBudget(PgWatchdogMode.REQUIRED, context);
    }

    /**
     * Without renewal, local ownership stays fresh for the whole ownership budget and no longer.
     * When it lapses the coordinator still shows this node's lease, and the stop budget fits
     * before the lease TTL.
     */
    private void assertOwnershipLapsesAtBudget(PgWatchdogMode mode, VertxTestContext context) {
        PgNodeConfig base = nodeConfig(config.incarnation(), "pg-node-1");
        PgNodeConfig node = new PgNodeConfig(base.clusterId(), base.incarnation(), base.nodeId(),
            base.memberNodeIds(), base.leaseTtl(), base.loopInterval(), base.requestTimeout(),
            base.stopTimeout(), mode);
        long budget = node.ownershipBudget().toNanos();
        coordinator(node).compose(coordinator -> {
            var subject = elector(node, coordinator);
            long requested = System.nanoTime();
            return subject.createInitialIntent(initialIntent("pg-node-1")).compose(held -> {
                long acquired = System.nanoTime();
                return observeLapse(subject, requested, acquired, budget).compose(ignored -> owner.read())
                    .map(current -> {
                        assertEquals(held, current.orElseThrow(),
                            "The lease is still held when local ownership lapses");
                        assertTrue(node.ownershipBudget().plus(node.stopTimeout())
                            .compareTo(node.leaseTtl()) <= 0);
                        return current;
                    });
            });
        }).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    private Future<Void> observeLapse(PgPrimaryElector subject, long requested, long acquired, long budget) {
        long before = System.nanoTime();
        boolean fresh = subject.hasFreshOwnership();
        long after = System.nanoTime();
        if (fresh) {
            // The deadline starts no later than the moment acquisition completed.
            assertTrue(before - acquired < budget, "Ownership stayed fresh beyond its budget");
            return vertx.timer(50).compose(ignored -> observeLapse(subject, requested, acquired, budget));
        }
        // The deadline starts no earlier than the moment acquisition was requested.
        assertTrue(after - requested >= budget, "Ownership lapsed before its budget");
        return Future.succeededFuture();
    }

    // ---------------------------------------------------------------- service failure

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class,
        minOccurrences = 2,
        maxOccurrences = 2)
    public void minorityCannotRenewOrReturnAuthoritativeRead(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(record -> suspendQuorumMajority())
            .compose(ignored -> owner.renew().transform(result -> {
                assertTrue(result.failed());
                assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                assertFalse(owner.hasFreshOwnership());
                return owner.read();
            })).onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void nonAuthoritativeAbsenceCannotAuthoriseBootstrap(VertxTestContext context) {
        interceptedCoordinator(config).compose(coordinator -> {
            inject(Fault.NON_AUTHORITATIVE_ABSENCE);
            return elector(config, coordinator).read();
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgLeaseProtocolException.class, failure);
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void malformedReadCannotBecomeAuthority(VertxTestContext context) {
        interceptedCoordinator(config).compose(coordinator -> {
            inject(Fault.MALFORMED_READ);
            return elector(config, coordinator).read();
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgLeaseProtocolException.class, failure);
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void silentReadHasBoundedFailure(VertxTestContext context) {
        long started = System.nanoTime();
        interceptedCoordinator(config).compose(coordinator -> {
            inject(Fault.SILENT_READ);
            return elector(config, coordinator).read();
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgLeaseProtocolException.class, failure);
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void failedAuthoritativeReadRetiresCachedOwnership(VertxTestContext context) {
        interceptedCoordinator(config).compose(coordinator -> {
            var faulty = elector(config, coordinator);
            return faulty.createInitialIntent(initialIntent("pg-node-1")).compose(record -> {
                inject(Fault.MALFORMED_READ);
                return faulty.read().transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertFalse(faulty.hasFreshOwnership());
                    return Future.<Void>succeededFuture();
                });
            });
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void lostAcquisitionReplyRequiresObservation(VertxTestContext context) {
        interceptedCoordinator(config).compose(coordinator -> {
            var faulty = elector(config, coordinator);
            inject(Fault.DROP_MUTATION_REPLY);
            return faulty.createInitialIntent(initialIntent("pg-node-1")).transform(result -> {
                assertTrue(result.failed());
                assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                assertFalse(faulty.hasFreshOwnership());
                return owner.read();
            });
        }).onSuccess(record -> context.verify(() -> {
            assertTrue(record.isPresent(), "The service applied the acquisition despite the lost reply");
            assertNotNull(record.orElseThrow().leaseHolder());
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Late coordinator reply rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void lateRenewalCannotUndoRetirement(VertxTestContext context) {
        interceptedCoordinator(config).compose(coordinator -> {
            var delayed = elector(config, coordinator);
            return delayed.createInitialIntent(initialIntent("pg-node-1")).compose(record -> {
                inject(Fault.DELAY_RENEWAL_REPLY);
                Future<PgControlRecord> renewal = delayed.renew()
                    .onFailure(failure -> logger.debug("Expected retired renewal", failure));
                return replyCaptured().compose(ignored -> {
                    delayed.retire();
                    deliverCapturedReply();
                    return renewal.transform(result -> {
                        assertTrue(result.failed());
                        assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                        assertFalse(delayed.hasFreshOwnership());
                        return Future.<Void>succeededFuture();
                    });
                });
            });
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void timedOutRenewalCannotRegainAuthorityFromLateReply(VertxTestContext context) {
        interceptedCoordinator(config).compose(coordinator -> {
            var delayed = elector(config, coordinator);
            return delayed.createInitialIntent(initialIntent("pg-node-1")).compose(original -> {
                inject(Fault.DELAY_RENEWAL_REPLY);
                Future<PgControlRecord> renewal = delayed.renew()
                    .onFailure(failure -> logger.debug("Expected timed-out renewal", failure));
                return replyCaptured().compose(ignored -> renewal.transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertFalse(delayed.hasFreshOwnership());
                    deliverCapturedReply();
                    inject(Fault.NONE);
                    return delayed.read();
                })).map(current -> {
                    assertEquals(original, current.orElseThrow());
                    assertFalse(delayed.hasFreshOwnership());
                    return current;
                });
            });
        }).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- guarded release

    @Test public void guardedReleaseRetainsHistoryAndEndsOwnership(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(held -> owner.release(held)
            .compose(released -> {
                assertNull(released.leaseHolder());
                assertEquals(held.intent(), released.intent());
                assertEquals(held.generation(), released.generation());
                assertFalse(owner.hasFreshOwnership());
                return coordinator(config);
            }).compose(coordinator -> elector(config, coordinator).read())
            .map(current -> {
                assertNull(current.orElseThrow().leaseHolder());
                assertEquals(held.intent(), current.orElseThrow().intent());
                return current;
            })).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Lease operation refused",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void releaseWithStaleRevisionChangesNothing(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(original ->
            owner.update(original, original.intent().put("phase", "FENCING")).compose(updated ->
                owner.release(original).transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    return owner.read();
                }).map(current -> {
                    assertEquals(updated, current.orElseThrow());
                    return current;
                }))).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test public void coordinatorRejectsReleaseAtStaleRevision(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(original ->
            owner.update(original, original.intent().put("phase", "FENCING")).compose(updated ->
                coordinator(config).compose(coordinator -> tracked(config, coordinator).release(original))
                    .transform(result -> {
                        assertTrue(result.failed());
                        assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                        return owner.read();
                    }).map(current -> {
                        assertEquals(updated, current.orElseThrow());
                        return current;
                    }))).onSuccess(current -> context.verify(() -> {
                        assertTrue(owner.hasFreshOwnership());
                        context.completeNow();
                    })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Lease operation refused",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class,
        minOccurrences = 2,
        maxOccurrences = 2)
    public void releasedOwnerCannotRenewOrUpdate(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(held -> owner.release(held)
            .compose(released -> owner.renew().transform(renewal -> {
                assertTrue(renewal.failed());
                assertInstanceOf(PgLeaseProtocolException.class, renewal.cause());
                return owner.update(held, held.intent());
            })))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                assertFalse(owner.hasFreshOwnership());
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void releaseWithoutQuorumFailsAndRetires(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> suspendQuorumMajority().compose(ignored -> owner.release(held)))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                assertFalse(owner.hasFreshOwnership());
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void malformedReleaseReplyRetiresWithoutClaimingRelease(VertxTestContext context) {
        interceptedCoordinator(config).compose(coordinator -> {
            var faulty = elector(config, coordinator);
            return faulty.createInitialIntent(initialIntent("pg-node-1")).compose(held -> {
                inject(Fault.MALFORMED_MUTATION);
                return faulty.release(held).transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertFalse(faulty.hasFreshOwnership());
                    return owner.read();
                }).map(current -> {
                    assertEquals(held, current.orElseThrow());
                    return current;
                });
            });
        }).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void silentReleaseHasBoundedFailure(VertxTestContext context) {
        interceptedCoordinator(config).compose(coordinator -> {
            var faulty = elector(config, coordinator);
            return faulty.createInitialIntent(initialIntent("pg-node-1")).compose(held -> {
                inject(Fault.SILENT_MUTATION);
                long started = System.nanoTime();
                return faulty.release(held).transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
                    assertFalse(faulty.hasFreshOwnership());
                    return owner.read();
                }).map(current -> {
                    assertEquals(held, current.orElseThrow());
                    return current;
                });
            });
        }).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void lostReleaseReplyRequiresObservation(VertxTestContext context) {
        interceptedCoordinator(config).compose(coordinator -> {
            var faulty = elector(config, coordinator);
            return faulty.createInitialIntent(initialIntent("pg-node-1")).compose(held -> {
                inject(Fault.DROP_MUTATION_REPLY);
                return faulty.release(held).transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertFalse(faulty.hasFreshOwnership());
                    return owner.read();
                });
            });
        }).onSuccess(record -> context.verify(() -> {
            assertNull(record.orElseThrow().leaseHolder(), "The service applied the release despite the lost reply");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Late coordinator reply rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void lateReleaseReplyAfterRetirementIsRejected(VertxTestContext context) {
        interceptedCoordinator(config).compose(coordinator -> {
            var delayed = elector(config, coordinator);
            return delayed.createInitialIntent(initialIntent("pg-node-1")).compose(held -> {
                inject(Fault.DELAY_MUTATION_REPLY);
                Future<PgControlRecord> release = delayed.release(held)
                    .onFailure(failure -> logger.debug("Expected retired release", failure));
                return replyCaptured().compose(ignored -> {
                    delayed.retire();
                    deliverCapturedReply();
                    return release.transform(result -> {
                        assertTrue(result.failed());
                        assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                        assertFalse(delayed.hasFreshOwnership());
                        return Future.<Void>succeededFuture();
                    });
                });
            });
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- acquisition after release

    @Test public void acquisitionAfterReleaseAdvancesGenerationAndPreservesHistory(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> owner.release(held))
            .compose(released -> coordinator(otherConfig).compose(coordinator -> {
                var successor = elector(otherConfig, coordinator);
                return successor.acquireAfterRelease(released, takeoverIntent(released, "pg-node-2"))
                    .map(acquired -> {
                        assertTrue(successor.hasFreshOwnership());
                        assertNotNull(acquired.leaseHolder());
                        assertTrue(acquired.generation() > released.generation());
                        assertTrue(acquired.revision() > released.revision());
                        assertEquals("pg-node-2", acquired.intent().getString("writerNodeId"));
                        assertEquals("pg-node-1", acquired.intent().getString("previousWriterNodeId"));
                        assertEquals("WITHDRAWN", acquired.intent().getString("phase"));
                        assertEquals(released.intent().getJsonObject("pendingDurabilityPolicy"),
                            acquired.intent().getJsonObject("pendingDurabilityPolicy"));
                        return acquired;
                    });
            })).onSuccess(acquired -> context.completeNow()).onFailure(context::failNow);
    }

    @Test public void acquisitionAfterExpiryNeedsNoReplyFromFormerOwner(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> coordinator(otherConfig).compose(coordinator -> {
                var successor = elector(otherConfig, coordinator);
                return waitForExpiry(successor, held, System.nanoTime() + TimeUnit.SECONDS.toNanos(40))
                    .compose(expired -> successor.acquireAfterRelease(expired, takeoverIntent(expired, "pg-node-2"))
                        .map(acquired -> {
                            assertTrue(successor.hasFreshOwnership());
                            assertTrue(acquired.generation() > held.generation());
                            assertFalse(owner.hasFreshOwnership());
                            return acquired;
                        }));
            })).onSuccess(acquired -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void twoSuccessorsCannotBothAcquireReleasedRecord(VertxTestContext context) {
        var secondConfig = nodeConfig(config.incarnation(), "pg-node-2");
        var thirdConfig = nodeConfig(config.incarnation(), "pg-node-3");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> owner.release(held))
            .compose(released -> Future.all(coordinator(secondConfig), coordinator(thirdConfig)).compose(both -> {
                var second = elector(secondConfig, both.<PgLeaseCoordinator>resultAt(0));
                var third = elector(thirdConfig, both.<PgLeaseCoordinator>resultAt(1));
                Future<Boolean> first = second.acquireAfterRelease(released, takeoverIntent(released, "pg-node-2"))
                    .transform(result -> Future.succeededFuture(result.succeeded()));
                Future<Boolean> other = third.acquireAfterRelease(released, takeoverIntent(released, "pg-node-3"))
                    .transform(result -> Future.succeededFuture(result.succeeded()));
                return Future.all(first, other).map(results ->
                    (results.<Boolean>resultAt(0) ? 1 : 0) + (results.<Boolean>resultAt(1) ? 1 : 0));
            })).onSuccess(winners -> context.verify(() -> {
                assertEquals(1, winners);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void acquisitionWhileOwnedIsRejected(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> coordinator(otherConfig).compose(coordinator -> {
                var contender = elector(otherConfig, coordinator);
                return contender.acquireAfterRelease(held, takeoverIntent(held, "pg-node-2")).transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertFalse(contender.hasFreshOwnership());
                    return owner.read();
                }).map(current -> {
                    assertEquals(held, current.orElseThrow());
                    assertTrue(owner.hasFreshOwnership());
                    return current;
                });
            })).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test public void coordinatorRejectsAcquisitionOfHeldRecord(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> coordinator(otherConfig).compose(coordinator -> {
                // The caller claims the record is unowned. The service still holds the lease.
                var claimedUnowned = new PgControlRecord(held.controlName(), held.generation(),
                    held.revision(), null, held.intent());
                return tracked(otherConfig, coordinator)
                    .acquireAfterRelease(claimedUnowned, takeoverIntent(held, "pg-node-2"));
            }).transform(result -> {
                assertTrue(result.failed());
                assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                return owner.read();
            }).map(current -> {
                assertEquals(held, current.orElseThrow());
                return current;
            })).onSuccess(current -> context.verify(() -> {
                assertTrue(owner.hasFreshOwnership());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void acquisitionAtStaleRevisionIsRejected(VertxTestContext context) {
        var secondConfig = nodeConfig(config.incarnation(), "pg-node-2");
        var thirdConfig = nodeConfig(config.incarnation(), "pg-node-3");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> owner.release(held))
            .compose(released -> coordinator(secondConfig).compose(coordinator ->
                    elector(secondConfig, coordinator)
                        .acquireAfterRelease(released, takeoverIntent(released, "pg-node-2")))
                .compose(winner -> coordinator(thirdConfig).compose(coordinator -> {
                    var late = elector(thirdConfig, coordinator);
                    return late.acquireAfterRelease(released, takeoverIntent(released, "pg-node-3"))
                        .transform(result -> {
                            assertTrue(result.failed());
                            assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                            assertFalse(late.hasFreshOwnership());
                            return late.read();
                        }).map(current -> {
                            assertEquals(winner, current.orElseThrow());
                            return current;
                        });
                }))).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void acquisitionMustPreservePolicyHistory(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> owner.release(held))
            .compose(released -> coordinator(otherConfig).compose(coordinator -> {
                var successor = elector(otherConfig, coordinator);
                JsonObject rewritten = takeoverIntent(released, "pg-node-2");
                rewritten.getJsonObject("pendingDurabilityPolicy").put("revision", 7L);
                return successor.acquireAfterRelease(released, rewritten).transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertFalse(successor.hasFreshOwnership());
                    return owner.read();
                }).map(current -> {
                    assertEquals(released, current.orElseThrow());
                    return current;
                });
            })).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void acquisitionMustNameTheReleasedWriterAsPrevious(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> owner.release(held))
            .compose(released -> coordinator(otherConfig).compose(coordinator -> {
                var successor = elector(otherConfig, coordinator);
                JsonObject rewritten = takeoverIntent(released, "pg-node-2").put("previousWriterNodeId", "pg-node-3");
                return successor.acquireAfterRelease(released, rewritten).transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertFalse(successor.hasFreshOwnership());
                    return owner.read();
                }).map(current -> {
                    assertEquals(released, current.orElseThrow());
                    return current;
                });
            })).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void acquisitionWithInvalidOperationIdentityIsRejected(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> owner.release(held))
            .compose(released -> coordinator(otherConfig).compose(coordinator -> {
                var successor = elector(otherConfig, coordinator);
                JsonObject invalid = takeoverIntent(released, "pg-node-2").put("operationId", "not-a-uuid");
                return successor.acquireAfterRelease(released, invalid).transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertFalse(successor.hasFreshOwnership());
                    return owner.read();
                }).map(current -> {
                    assertEquals(released, current.orElseThrow());
                    return current;
                });
            })).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void accessDeniedCannotAcquireReleasedRecord(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> owner.release(held))
            .compose(released -> unauthorisedCoordinator(otherConfig).compose(coordinator -> {
                var denied = elector(otherConfig, coordinator);
                return denied.acquireAfterRelease(released, takeoverIntent(released, "pg-node-2"))
                    .transform(result -> {
                        assertTrue(result.failed());
                        assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                        assertFalse(denied.hasFreshOwnership());
                        return owner.read();
                    }).map(current -> {
                        assertEquals(released, current.orElseThrow());
                        return current;
                    });
            })).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void malformedTakeoverReplyCannotGrantOwnership(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> owner.release(held))
            .compose(released -> interceptedCoordinator(otherConfig).compose(coordinator -> {
                var faulty = elector(otherConfig, coordinator);
                inject(Fault.MALFORMED_MUTATION);
                return faulty.acquireAfterRelease(released, takeoverIntent(released, "pg-node-2"))
                    .transform(result -> {
                        assertTrue(result.failed());
                        assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                        assertFalse(faulty.hasFreshOwnership());
                        return owner.read();
                    }).map(current -> {
                        assertEquals(released, current.orElseThrow());
                        return current;
                    });
            })).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void silentTakeoverHasBoundedFailure(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> owner.release(held))
            .compose(released -> interceptedCoordinator(otherConfig).compose(coordinator -> {
                var faulty = elector(otherConfig, coordinator);
                inject(Fault.SILENT_MUTATION);
                long started = System.nanoTime();
                return faulty.acquireAfterRelease(released, takeoverIntent(released, "pg-node-2"))
                    .transform(result -> {
                        assertTrue(result.failed());
                        assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
                        assertFalse(faulty.hasFreshOwnership());
                        return owner.read();
                    }).map(current -> {
                        assertEquals(released, current.orElseThrow());
                        return current;
                    });
            })).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void lostTakeoverReplyRequiresObservation(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> owner.release(held))
            .compose(released -> interceptedCoordinator(otherConfig).compose(coordinator -> {
                var faulty = elector(otherConfig, coordinator);
                inject(Fault.DROP_MUTATION_REPLY);
                return faulty.acquireAfterRelease(released, takeoverIntent(released, "pg-node-2"))
                    .transform(result -> {
                        assertTrue(result.failed());
                        assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                        assertFalse(faulty.hasFreshOwnership());
                        return owner.read();
                    });
            })).onSuccess(record -> context.verify(() -> {
                assertNotNull(record.orElseThrow().leaseHolder(),
                    "The service applied the acquisition despite the lost reply");
                assertEquals("pg-node-2", record.orElseThrow().intent().getString("writerNodeId"));
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Late coordinator reply rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    public void lateTakeoverReplyAfterRetirementCannotGrantOwnership(VertxTestContext context) {
        var otherConfig = nodeConfig(config.incarnation(), "pg-node-2");
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(held -> owner.release(held))
            .compose(released -> interceptedCoordinator(otherConfig).compose(coordinator -> {
                var delayed = elector(otherConfig, coordinator);
                inject(Fault.DELAY_MUTATION_REPLY);
                Future<PgControlRecord> takeover = delayed
                    .acquireAfterRelease(released, takeoverIntent(released, "pg-node-2"))
                    .onFailure(failure -> logger.debug("Expected retired acquisition", failure));
                return replyCaptured().compose(ignored -> {
                    delayed.retire();
                    deliverCapturedReply();
                    return takeover.transform(result -> {
                        assertTrue(result.failed());
                        assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                        assertFalse(delayed.hasFreshOwnership());
                        return Future.<Void>succeededFuture();
                    });
                });
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- helpers

    protected PgPrimaryElector elector(PgNodeConfig node, PgLeaseCoordinator coordinator) {
        var elector = new PgPrimaryElector(node, coordinator);
        electors.add(elector);
        return elector;
    }

    /** Registers a coordinator used directly through the port so that teardown closes it. */
    protected PgLeaseCoordinator tracked(PgNodeConfig node, PgLeaseCoordinator coordinator) {
        elector(node, coordinator);
        return coordinator;
    }

    protected static JsonObject initialIntent(String writer) {
        return new JsonObject().put("writerNodeId", writer).put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("pendingDurabilityPolicy", new JsonObject().put("revision", 1L)
                .put("requiredStandbyNodeIds", new JsonArray(MEMBERS.stream().filter(id -> !id.equals(writer)).toList())));
    }

    /** Withdrawn intent for a successor. Policy history is carried over unchanged. */
    protected static JsonObject takeoverIntent(PgControlRecord released, String writer) {
        JsonObject previous = released.intent();
        JsonObject intent = new JsonObject().put("writerNodeId", writer).put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("previousWriterNodeId", previous.getString("writerNodeId"));
        if (previous.containsKey("durabilityPolicy")) {
            intent.put("durabilityPolicy", previous.getJsonObject("durabilityPolicy"));
        }
        if (previous.containsKey("pendingDurabilityPolicy")) {
            intent.put("pendingDurabilityPolicy", previous.getJsonObject("pendingDurabilityPolicy"));
        }
        return intent;
    }

    /** Polls the authoritative record until its lease has ended, and fails on the deadline. */
    protected Future<PgControlRecord> waitForExpiry(PgPrimaryElector observer, PgControlRecord original, long deadline) {
        return observer.read().compose(current -> {
            PgControlRecord record = current.orElseThrow();
            if (record.leaseHolder() == null) return Future.succeededFuture(record);
            assertEquals(original.leaseHolder(), record.leaseHolder());
            if (System.nanoTime() >= deadline) return Future.failedFuture(new AssertionError("The lease did not expire"));
            return vertx.timer(100).compose(ignored -> waitForExpiry(observer, original, deadline));
        });
    }
}
