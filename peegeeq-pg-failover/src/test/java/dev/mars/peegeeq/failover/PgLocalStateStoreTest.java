package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.file.FileSystemException;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Node-local grants, quarantine, and action receipts against a real directory. */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PgLocalStateStoreTest {
    private static final String NODE = "pg-node-1";
    private static final String OPERATION = UUID.randomUUID().toString();
    @TempDir Path directory;

    // ---------------------------------------------------------------- opening

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local storage failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = FileSystemException.class)
    void openRejectsMissingDirectory(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory.resolve("absent"), NODE)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLocalStateException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void openRejectsPathThatIsNotADirectory(Vertx vertx, VertxTestContext context) throws IOException {
        Path file = Files.writeString(directory.resolve("file"), "x");
        PgLocalStateStore.open(vertx, file, NODE)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLocalStateException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local storage failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = DecodeException.class)
    void openRejectsCorruptGrant(Vertx vertx, VertxTestContext context) throws IOException {
        Files.writeString(directory.resolve("grant.json"), "not-json");
        PgLocalStateStore.open(vertx, directory, NODE)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLocalStateException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local storage failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = IllegalArgumentException.class,
        minOccurrences = 2,
        maxOccurrences = 2)
    void openRejectsGrantWithUnknownFieldOrState(Vertx vertx, VertxTestContext context) throws IOException {
        JsonObject grant = grantJson("OPEN").put("extra", true);
        Files.writeString(directory.resolve("grant.json"), grant.encode());
        PgLocalStateStore.open(vertx, directory, NODE).transform(unknownField -> {
            assertTrue(unknownField.failed());
            assertInstanceOf(PgLocalStateException.class, unknownField.cause());
            write(directory.resolve("grant.json"), grantJson("ACTIVE").encode());
            return PgLocalStateStore.open(vertx, directory, NODE);
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgLocalStateException.class, failure);
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void openRejectsGrantOfAnotherNode(Vertx vertx, VertxTestContext context) throws IOException {
        Files.writeString(directory.resolve("grant.json"), grantJson("CLOSED").put("nodeId", "pg-node-2").encode());
        PgLocalStateStore.open(vertx, directory, NODE)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLocalStateException.class, failure);
                context.completeNow();
            })));
    }

    @Test void emptyDirectoryHasNoGrantQuarantineOrReceipt(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE).compose(store -> store.grant()
            .compose(grant -> {
                assertTrue(grant.isEmpty());
                return store.quarantine();
            }).compose(quarantine -> {
                assertTrue(quarantine.isEmpty());
                return store.receipt(1, OPERATION, "promote", NODE);
            })).onSuccess(receipt -> context.verify(() -> {
                assertTrue(receipt.isEmpty());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- grants

    @Test void prepareThenActivateOpensTheMatchingGrant(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE).compose(store ->
            store.prepare(PgFailoverMode.MANUAL, 3, OPERATION, 2).compose(prepared -> {
                assertEquals(PgGrantState.PREPARED, prepared.state());
                assertEquals(NODE, prepared.nodeId());
                return store.activate(prepared);
            }).compose(open -> {
                assertEquals(PgGrantState.OPEN, open.state());
                return store.grant();
            })).onSuccess(grant -> context.verify(() -> {
                assertEquals(new PgWriterGrant(PgFailoverMode.MANUAL, 3, OPERATION, 2, NODE, PgGrantState.OPEN),
                    grant.orElseThrow());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void loadedOpenGrantStartsClosedDurably(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE)
            .compose(store -> store.prepare(PgFailoverMode.AUTOMATIC, 3, OPERATION, 2).compose(store::activate))
            .compose(open -> PgLocalStateStore.open(vertx, directory, NODE))
            .compose(PgLocalStateStore::grant)
            .onSuccess(grant -> context.verify(() -> {
                assertEquals(PgGrantState.CLOSED, grant.orElseThrow().state());
                assertEquals("CLOSED", new JsonObject(Files.readString(directory.resolve("grant.json")))
                    .getString("state"));
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void loadedPreparedGrantStartsClosed(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE)
            .compose(store -> store.prepare(PgFailoverMode.MANUAL, 3, OPERATION, 2))
            .compose(prepared -> PgLocalStateStore.open(vertx, directory, NODE)
                .compose(reopened -> reopened.activate(prepared).transform(activation -> {
                    assertTrue(activation.failed(), "A grant prepared before restart cannot be activated");
                    assertInstanceOf(PgLocalStateException.class, activation.cause());
                    return reopened.grant();
                })))
            .onSuccess(grant -> context.verify(() -> {
                assertEquals(PgGrantState.CLOSED, grant.orElseThrow().state());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void activateRejectsDifferentAuthorityAndKeepsPreparedGrant(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE).compose(store ->
            store.prepare(PgFailoverMode.MANUAL, 3, OPERATION, 2).compose(prepared -> {
                var other = new PgWriterGrant(PgFailoverMode.MANUAL, 4, OPERATION, 2, NODE, PgGrantState.PREPARED);
                return store.activate(other).transform(activation -> {
                    assertTrue(activation.failed());
                    assertInstanceOf(PgLocalStateException.class, activation.cause());
                    return store.grant();
                }).map(grant -> {
                    assertEquals(prepared, grant.orElseThrow());
                    return grant;
                });
            })).onSuccess(grant -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void activateWithoutPreparedGrantIsRejected(Vertx vertx, VertxTestContext context) {
        var grant = new PgWriterGrant(PgFailoverMode.MANUAL, 3, OPERATION, 2, NODE, PgGrantState.PREPARED);
        PgLocalStateStore.open(vertx, directory, NODE).compose(store -> store.activate(grant))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLocalStateException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class,
        minOccurrences = 2,
        maxOccurrences = 2)
    void prepareIsRejectedWhileAGrantIsPreparedOrOpen(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE).compose(store ->
            store.prepare(PgFailoverMode.MANUAL, 3, OPERATION, 2).compose(prepared ->
                store.prepare(PgFailoverMode.MANUAL, 4, OPERATION, 2).transform(second -> {
                    assertTrue(second.failed());
                    assertInstanceOf(PgLocalStateException.class, second.cause());
                    return store.activate(prepared);
                })).compose(open -> store.prepare(PgFailoverMode.MANUAL, 4, OPERATION, 2).transform(third -> {
                    assertTrue(third.failed());
                    assertInstanceOf(PgLocalStateException.class, third.cause());
                    return store.grant();
                }).map(grant -> {
                    assertEquals(open, grant.orElseThrow());
                    return grant;
                }))).onSuccess(grant -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void closeGrantIsIdempotentAndAllowsANewPreparation(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE).compose(store -> store.closeGrant()
            .compose(ignored -> store.prepare(PgFailoverMode.MANUAL, 3, OPERATION, 2))
            .compose(store::activate)
            .compose(open -> store.closeGrant())
            .compose(ignored -> store.closeGrant())
            .compose(ignored -> store.grant())
            .compose(grant -> {
                assertEquals(PgGrantState.CLOSED, grant.orElseThrow().state());
                return store.prepare(PgFailoverMode.MANUAL, 4, OPERATION, 3);
            })).onSuccess(prepared -> context.verify(() -> {
                assertEquals(4, prepared.generation());
                assertEquals(PgGrantState.PREPARED, prepared.state());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void closedGrantCannotBeActivatedAgain(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE).compose(store ->
            store.prepare(PgFailoverMode.MANUAL, 3, OPERATION, 2).compose(prepared ->
                store.closeGrant().compose(ignored -> store.activate(prepared))))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLocalStateException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void concurrentPreparationsAdmitExactlyOne(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE).compose(store -> {
            Future<Boolean> first = store.prepare(PgFailoverMode.MANUAL, 3, OPERATION, 2)
                .transform(result -> Future.succeededFuture(result.succeeded()));
            Future<Boolean> second = store.prepare(PgFailoverMode.MANUAL, 4, OPERATION, 2)
                .transform(result -> Future.succeededFuture(result.succeeded()));
            return Future.all(first, second).compose(results -> {
                assertEquals(1, (results.<Boolean>resultAt(0) ? 1 : 0) + (results.<Boolean>resultAt(1) ? 1 : 0));
                return store.grant();
            });
        }).onSuccess(grant -> context.verify(() -> {
            assertEquals(3, grant.orElseThrow().generation(), "Mutations run in submission order");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- quarantine

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void quarantineClosesTheGrantAndBlocksAdmissionAcrossRestart(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE)
            .compose(store -> store.prepare(PgFailoverMode.MANUAL, 3, OPERATION, 2).compose(store::activate)
                .compose(open -> store.quarantine("former writer awaiting rewind")))
            .compose(ignored -> PgLocalStateStore.open(vertx, directory, NODE))
            .compose(store -> store.quarantine().compose(quarantine -> {
                assertEquals(new PgQuarantine(NODE, "former writer awaiting rewind"), quarantine.orElseThrow());
                return store.grant();
            }).compose(grant -> {
                assertEquals(PgGrantState.CLOSED, grant.orElseThrow().state());
                return store.prepare(PgFailoverMode.MANUAL, 4, OPERATION, 3);
            }).transform(preparation -> {
                assertTrue(preparation.failed(), "A quarantined node cannot prepare a writer grant");
                assertInstanceOf(PgLocalStateException.class, preparation.cause());
                return store.clearQuarantine();
            }).compose(ignored -> store.prepare(PgFailoverMode.MANUAL, 4, OPERATION, 3)))
            .onSuccess(prepared -> context.verify(() -> {
                assertEquals(PgGrantState.PREPARED, prepared.state());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void quarantineDefeatsActivationOfAPreparedGrant(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE).compose(store ->
            store.prepare(PgFailoverMode.MANUAL, 3, OPERATION, 2).compose(prepared ->
                store.quarantine("ownership lost").compose(ignored -> store.activate(prepared))))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLocalStateException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local storage failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = IllegalArgumentException.class)
    void openRejectsCorruptQuarantine(Vertx vertx, VertxTestContext context) throws IOException {
        Files.writeString(directory.resolve("quarantine.json"), "{\"nodeId\":\"pg-node-1\"}");
        PgLocalStateStore.open(vertx, directory, NODE)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLocalStateException.class, failure);
                context.completeNow();
            })));
    }

    // ---------------------------------------------------------------- action receipts

    @Test void beginningAnActionTwiceWithTheSameParametersReturnsOneReceipt(Vertx vertx, VertxTestContext context) {
        JsonObject parameters = new JsonObject().put("timeline", 4);
        PgLocalStateStore.open(vertx, directory, NODE).compose(store ->
            store.begin(3, OPERATION, "promote", NODE, parameters).compose(first -> {
                assertEquals(PgActionResult.PENDING, first.result());
                return store.begin(3, OPERATION, "promote", NODE, parameters.copy()).map(second -> {
                    assertEquals(first, second);
                    return second;
                });
            })).onSuccess(receipt -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void reusingAReceiptWithChangedParametersIsRejected(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE).compose(store ->
            store.begin(3, OPERATION, "promote", NODE, new JsonObject().put("timeline", 4)).compose(first ->
                store.begin(3, OPERATION, "promote", NODE, new JsonObject().put("timeline", 5))
                    .transform(second -> {
                        assertTrue(second.failed());
                        assertInstanceOf(PgLocalStateException.class, second.cause());
                        return store.receipt(3, OPERATION, "promote", NODE);
                    }).map(stored -> {
                        assertEquals(first, stored.orElseThrow());
                        return stored;
                    }))).onSuccess(stored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void receiptResultMovesFromPendingThroughUnknownToAFinalResult(Vertx vertx, VertxTestContext context) {
        JsonObject effect = new JsonObject().put("role", "primary");
        PgLocalStateStore.open(vertx, directory, NODE).compose(store ->
            store.begin(3, OPERATION, "promote", NODE, new JsonObject())
                .compose(pending -> store.record(3, OPERATION, "promote", NODE, PgActionResult.UNKNOWN, new JsonObject()))
                .compose(unknown -> {
                    assertEquals(PgActionResult.UNKNOWN, unknown.result());
                    return store.record(3, OPERATION, "promote", NODE, PgActionResult.COMPLETED, effect);
                }).compose(completed -> {
                    assertEquals(PgActionResult.COMPLETED, completed.result());
                    assertEquals(effect, completed.effect());
                    return store.record(3, OPERATION, "promote", NODE, PgActionResult.COMPLETED, effect);
                }).compose(repeated -> store.record(3, OPERATION, "promote", NODE, PgActionResult.REJECTED, effect)
                    .transform(reversal -> {
                        assertTrue(reversal.failed(), "A final result is never replaced");
                        assertInstanceOf(PgLocalStateException.class, reversal.cause());
                        return store.receipt(3, OPERATION, "promote", NODE);
                    })))
            .onSuccess(stored -> context.verify(() -> {
                assertEquals(PgActionResult.COMPLETED, stored.orElseThrow().result());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void recordingAResultWithoutAReceiptIsRejected(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE).compose(store ->
            store.record(3, OPERATION, "promote", NODE, PgActionResult.COMPLETED, new JsonObject()))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLocalStateException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void pendingResultCannotBeRecordedAsAnOutcome(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE).compose(store ->
            store.begin(3, OPERATION, "promote", NODE, new JsonObject()).compose(pending ->
                store.record(3, OPERATION, "promote", NODE, PgActionResult.PENDING, new JsonObject())))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLocalStateException.class, failure);
                context.completeNow();
            })));
    }

    @Test void pendingReceiptSurvivesRestartAndStaysPending(Vertx vertx, VertxTestContext context) {
        JsonObject parameters = new JsonObject().put("mode", "fast");
        PgLocalStateStore.open(vertx, directory, NODE)
            .compose(store -> store.begin(3, OPERATION, "stop-writer", NODE, parameters))
            .compose(pending -> PgLocalStateStore.open(vertx, directory, NODE)
                .compose(store -> store.receipt(3, OPERATION, "stop-writer", NODE))
                .map(stored -> {
                    assertEquals(pending, stored.orElseThrow());
                    assertEquals(PgActionResult.PENDING, stored.orElseThrow().result());
                    return stored;
                })).onSuccess(stored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void receiptsWithDifferentIdentityAreIndependent(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE).compose(store ->
            store.begin(3, OPERATION, "promote", NODE, new JsonObject().put("a", 1))
                .compose(first -> store.begin(4, OPERATION, "promote", NODE, new JsonObject().put("a", 2)))
                .compose(second -> store.begin(3, OPERATION, "stop-writer", NODE, new JsonObject().put("a", 3)))
                .compose(third -> store.receipt(3, OPERATION, "promote", NODE)))
            .onSuccess(stored -> context.verify(() -> {
                assertEquals(1, stored.orElseThrow().parameters().getInteger("a"));
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = IllegalArgumentException.class,
        minOccurrences = 3,
        maxOccurrences = 3)
    void receiptIdentityRejectsUnsafeNames(Vertx vertx, VertxTestContext context) {
        PgLocalStateStore.open(vertx, directory, NODE).compose(store ->
            store.begin(3, OPERATION, "../grant", NODE, new JsonObject()).transform(traversal -> {
                assertTrue(traversal.failed());
                assertInstanceOf(PgLocalStateException.class, traversal.cause());
                return store.begin(3, "not-a-uuid", "promote", NODE, new JsonObject());
            }).transform(operation -> {
                assertTrue(operation.failed());
                assertInstanceOf(PgLocalStateException.class, operation.cause());
                return store.begin(0, OPERATION, "promote", NODE, new JsonObject());
            })).onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLocalStateException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local storage failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = DecodeException.class)
    void corruptReceiptFailsItsRead(Vertx vertx, VertxTestContext context) throws IOException {
        Files.createDirectories(directory.resolve("receipts"));
        Files.writeString(directory.resolve("receipts").resolve("3+" + OPERATION + "+promote+" + NODE + ".json"), "{");
        PgLocalStateStore.open(vertx, directory, NODE)
            .compose(store -> store.receipt(3, OPERATION, "promote", NODE))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLocalStateException.class, failure);
                context.completeNow();
            })));
    }

    // ---------------------------------------------------------------- storage failure

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local storage failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = FileSystemException.class)
    void failedWriteIsReportedAndLeavesThePriorState(Vertx vertx, VertxTestContext context) throws IOException {
        // A directory at the temporary file's path makes the durable write fail.
        Files.createDirectory(directory.resolve("grant.json.tmp"));
        PgLocalStateStore.open(vertx, directory, NODE).compose(store ->
            store.prepare(PgFailoverMode.MANUAL, 3, OPERATION, 2).transform(preparation -> {
                assertTrue(preparation.failed());
                assertInstanceOf(PgLocalStateException.class, preparation.cause());
                return store.grant();
            })).onSuccess(grant -> context.verify(() -> {
                assertTrue(grant.isEmpty());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void aFailedMutationDoesNotBlockTheNextOne(Vertx vertx, VertxTestContext context) {
        var absent = new PgWriterGrant(PgFailoverMode.MANUAL, 3, OPERATION, 2, NODE, PgGrantState.PREPARED);
        PgLocalStateStore.open(vertx, directory, NODE).compose(store ->
            store.activate(absent).transform(activation -> {
                assertTrue(activation.failed());
                return store.prepare(PgFailoverMode.MANUAL, 3, OPERATION, 2);
            })).onSuccess(prepared -> context.verify(() -> {
                assertEquals(PgGrantState.PREPARED, prepared.state());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static JsonObject grantJson(String state) {
        return new JsonObject().put("mode", "MANUAL").put("generation", 3).put("operationId", OPERATION)
            .put("policyRevision", 2).put("nodeId", NODE).put("state", state);
    }
}
