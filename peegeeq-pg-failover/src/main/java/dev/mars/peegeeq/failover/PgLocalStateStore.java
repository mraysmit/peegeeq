package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.CopyOptions;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Durable node-local supervisor storage: the writer grant, node quarantine, and action receipts.
 * These records are authoritative. Lease freshness, PostgreSQL role, process state, watchdog
 * health, and eligibility are derived elsewhere and are never stored here.
 *
 * <p>The store is one directory on the node's persistent storage. Each record is one JSON file,
 * replaced by a synced temporary-file write and an atomic move. The files are the source of
 * truth; nothing is cached. Operations run one at a time in submission order. One supervisor
 * process writes a directory. A grant loaded at open is closed before the store is returned.
 */
public final class PgLocalStateStore {
    @FunctionalInterface
    private interface Operation<T> { Future<T> execute(); }
    private static final Logger logger = LoggerFactory.getLogger(PgLocalStateStore.class);
    private static final String GRANT = "grant.json";
    private static final String QUARANTINE = "quarantine.json";
    private static final String RECEIPTS = "receipts";
    private final FileSystem files;
    private final Path directory;
    private final String nodeId;
    private Future<?> tail = Future.succeededFuture();

    private PgLocalStateStore(FileSystem files, Path directory, String nodeId) {
        this.files = files;
        this.directory = directory;
        this.nodeId = nodeId;
    }

    /**
     * Opens an existing directory. Fails when the directory is absent, a record is corrupt, or a
     * record belongs to another node. A prepared or open grant is closed durably first.
     */
    public static Future<PgLocalStateStore> open(Vertx vertx, Path directory, String nodeId) {
        Objects.requireNonNull(directory, "directory");
        PgNodeConfig.requireIdentity(nodeId);
        var store = new PgLocalStateStore(Objects.requireNonNull(vertx, "vertx").fileSystem(), directory, nodeId);
        return store.serialized(() -> store.files.props(directory.toString()).compose(props -> {
            if (!props.isDirectory()) throw state("Node-local storage path is not a directory");
            return store.readGrant();
        }).compose(grant -> grant.filter(loaded -> loaded.state() != PgGrantState.CLOSED).isPresent()
                ? store.write(GRANT, grant.orElseThrow().withState(PgGrantState.CLOSED).toJson())
                : Future.<Void>succeededFuture())
            .compose(ignored -> store.readQuarantine())
            .map(quarantine -> store));
    }

    public Future<Optional<PgWriterGrant>> grant() {
        return serialized(this::readGrant);
    }

    /** Creates a prepared grant for this node. Fails under quarantine or while a grant is not closed. */
    public Future<PgWriterGrant> prepare(PgFailoverMode mode, long generation, String operationId,
                                         long policyRevision) {
        return serialized(() -> {
            var prepared = new PgWriterGrant(mode, generation, operationId, policyRevision, nodeId,
                PgGrantState.PREPARED);
            return requireNoQuarantine().compose(ignored -> readGrant()).compose(current -> {
                if (current.filter(grant -> grant.state() != PgGrantState.CLOSED).isPresent()) {
                    throw state("A writer grant is already prepared or open; close it first");
                }
                return write(GRANT, prepared.toJson()).map(prepared);
            });
        });
    }

    /** Opens exactly the stored prepared grant. Any difference, closure, or quarantine rejects it. */
    public Future<PgWriterGrant> activate(PgWriterGrant prepared) {
        return serialized(() -> {
            Objects.requireNonNull(prepared, "prepared");
            return requireNoQuarantine().compose(ignored -> readGrant()).compose(current -> {
                if (prepared.state() != PgGrantState.PREPARED || !current.filter(prepared::equals).isPresent()) {
                    throw state("Activation requires the matching prepared writer grant");
                }
                PgWriterGrant open = prepared.withState(PgGrantState.OPEN);
                return write(GRANT, open.toJson()).map(open);
            });
        });
    }

    /** Closes the grant durably. Always permitted. */
    public Future<Void> closeGrant() {
        return serialized(this::closeStoredGrant);
    }

    public Future<Optional<PgQuarantine>> quarantine() {
        return serialized(this::readQuarantine);
    }

    /** Records quarantine first, then closes the grant. Quarantine survives restart. */
    public Future<Void> quarantine(String reason) {
        return serialized(() -> write(QUARANTINE, new PgQuarantine(nodeId, reason).toJson())
            .compose(ignored -> closeStoredGrant()));
    }

    public Future<Void> clearQuarantine() {
        return serialized(() -> files.exists(path(QUARANTINE)).compose(exists ->
            exists ? files.delete(path(QUARANTINE)) : Future.<Void>succeededFuture()));
    }

    public Future<Optional<PgActionReceipt>> receipt(long generation, String operationId, String action,
                                                    String targetNodeId) {
        return serialized(() -> readReceipt(identity(generation, operationId, action, targetNodeId)));
    }

    /**
     * Records the intent to perform an action before its effect. Returns the existing receipt
     * when the parameters are equal. Rejects reuse with changed parameters.
     */
    public Future<PgActionReceipt> begin(long generation, String operationId, String action,
                                         String targetNodeId, JsonObject parameters) {
        return serialized(() -> {
            var pending = new PgActionReceipt(generation, operationId, action, targetNodeId, parameters,
                PgActionResult.PENDING, new JsonObject());
            return readReceipt(pending).compose(existing -> {
                if (existing.isPresent()) {
                    if (!existing.orElseThrow().parameters().equals(pending.parameters())) {
                        throw state("Action receipt exists with different parameters");
                    }
                    return Future.succeededFuture(existing.orElseThrow());
                }
                return files.mkdirs(path(RECEIPTS))
                    .compose(ignored -> write(receiptName(pending), pending.toJson())).map(pending);
            });
        });
    }

    /**
     * Records an observed result. A final result is never replaced; recording the same final
     * result and effect again returns the stored receipt.
     */
    public Future<PgActionReceipt> record(long generation, String operationId, String action,
                                          String targetNodeId, PgActionResult result, JsonObject effect) {
        return serialized(() -> {
            if (result == null || result == PgActionResult.PENDING) {
                throw state("A recorded result must be unknown, completed, or rejected");
            }
            var identity = identity(generation, operationId, action, targetNodeId);
            return readReceipt(identity).compose(existing -> {
                PgActionReceipt stored = existing.orElseThrow(() -> state("No action receipt was begun"));
                PgActionReceipt next = stored.withResult(result, effect);
                if (stored.result().isFinal()) {
                    if (!stored.equals(next)) throw state("A final action result cannot be replaced");
                    return Future.succeededFuture(stored);
                }
                return write(receiptName(next), next.toJson()).map(next);
            });
        });
    }

    private Future<Void> closeStoredGrant() {
        return readGrant().compose(current -> current.filter(grant -> grant.state() != PgGrantState.CLOSED)
            .map(grant -> write(GRANT, grant.withState(PgGrantState.CLOSED).toJson()))
            .orElseGet(Future::succeededFuture));
    }

    private Future<Void> requireNoQuarantine() {
        return readQuarantine().map(quarantine -> {
            if (quarantine.isPresent()) throw state("Node is quarantined: " + quarantine.orElseThrow().reason());
            return null;
        });
    }

    private Future<Optional<PgWriterGrant>> readGrant() {
        return read(GRANT, PgWriterGrant::fromJson).map(grant -> {
            if (grant.filter(loaded -> !nodeId.equals(loaded.nodeId())).isPresent()) {
                throw state("Stored writer grant belongs to another node");
            }
            return grant;
        });
    }

    private Future<Optional<PgQuarantine>> readQuarantine() {
        return read(QUARANTINE, PgQuarantine::fromJson).map(quarantine -> {
            if (quarantine.filter(loaded -> !nodeId.equals(loaded.nodeId())).isPresent()) {
                throw state("Stored quarantine belongs to another node");
            }
            return quarantine;
        });
    }

    private Future<Optional<PgActionReceipt>> readReceipt(PgActionReceipt identity) {
        return read(receiptName(identity), PgActionReceipt::fromJson).map(receipt -> {
            if (receipt.filter(loaded -> loaded.generation() != identity.generation()
                    || !loaded.operationId().equals(identity.operationId())
                    || !loaded.action().equals(identity.action())
                    || !loaded.targetNodeId().equals(identity.targetNodeId())).isPresent()) {
                throw state("Stored action receipt does not match its identity");
            }
            return receipt;
        });
    }

    private static PgActionReceipt identity(long generation, String operationId, String action,
                                            String targetNodeId) {
        return new PgActionReceipt(generation, operationId, action, targetNodeId, new JsonObject(),
            PgActionResult.PENDING, new JsonObject());
    }

    /** The separator cannot occur in any identity part, so distinct identities give distinct names. */
    private static String receiptName(PgActionReceipt receipt) {
        return RECEIPTS + "/" + receipt.generation() + "+" + receipt.operationId() + "+" + receipt.action()
            + "+" + receipt.targetNodeId() + ".json";
    }

    private <T> Future<Optional<T>> read(String name, Function<JsonObject, T> parser) {
        return files.exists(path(name)).compose(exists -> exists
            ? files.readFile(path(name)).map(content -> Optional.of(parser.apply(new JsonObject(content))))
            : Future.succeededFuture(Optional.<T>empty()));
    }

    /** Synced write to a temporary file, then an atomic replace. A reader sees the old or the new record. */
    private Future<Void> write(String name, JsonObject content) {
        String target = path(name);
        String temporary = target + ".tmp";
        return files.open(temporary, new OpenOptions().setCreate(true).setTruncateExisting(true)
                .setWrite(true).setRead(false).setSync(true))
            .compose(file -> file.write(Buffer.buffer(content.encodePrettily())).compose(ignored -> file.flush())
                .transform(written -> file.close().transform(closed -> {
                    if (written.failed()) return Future.<Void>failedFuture(written.cause());
                    return closed.failed() ? Future.<Void>failedFuture(closed.cause()) : Future.<Void>succeededFuture();
                })))
            .compose(ignored -> files.move(temporary, target,
                new CopyOptions().setAtomicMove(true).setReplaceExisting(true)));
    }

    private String path(String name) {
        return directory.resolve(name).toString();
    }

    /** Runs after every earlier operation has finished, whatever its outcome. */
    private synchronized <T> Future<T> serialized(Operation<T> operation) {
        Future<T> next = tail.transform(previous -> guarded(operation));
        tail = next;
        return next;
    }

    private <T> Future<T> guarded(Operation<T> operation) {
        Future<T> result;
        try {
            result = operation.execute();
        } catch (RuntimeException failure) {
            PgLocalStateException rejection = wrap(failure);
            logger.error("Node-local state operation rejected", rejection);
            return Future.failedFuture(rejection);
        }
        return result.transform(outcome -> {
            if (outcome.failed()) {
                PgLocalStateException failure = wrap(outcome.cause());
                // A rule of this store refused the operation, or the storage could not be read or
                // written. The two are reported apart.
                if (outcome.cause() instanceof PgLocalStateException) {
                    logger.error("Node-local state operation failed", failure);
                } else {
                    logger.error("Node-local storage failed", failure);
                }
                return Future.failedFuture(failure);
            }
            return Future.succeededFuture(outcome.result());
        });
    }

    private static PgLocalStateException wrap(Throwable failure) {
        return failure instanceof PgLocalStateException known
            ? known : new PgLocalStateException("Node-local state operation failed", failure);
    }

    private static PgLocalStateException state(String message) { return new PgLocalStateException(message); }
}
