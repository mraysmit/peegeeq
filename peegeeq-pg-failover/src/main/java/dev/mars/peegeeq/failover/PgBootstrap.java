package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.Objects;

/**
 * First-start bootstrap of the initial writer, up to a guarded primary start with application
 * admission closed. The request file is the provisioning evidence. The order is: verify the
 * request; refuse a node with a stored grant or quarantine; refuse when a control record exists;
 * create the withdrawn initial intent through the conditional initial acquisition; start the
 * renewal loop; record the bootstrap receipt with the request as its immutable parameters; start
 * PostgreSQL through the monitor.
 *
 * <p>Policy installation, policy confirmation, and writer activation are not performed here.
 * Bootstrap completion is derived from those later records; the bootstrap receipt has no result
 * until then. A rerun after an interruption is refused: resuming the same operation needs the
 * takeover path. Other members are not inspected.
 */
public final class PgBootstrap {
    public static final String BOOTSTRAP = "bootstrap";
    private static final Logger logger = LoggerFactory.getLogger(PgBootstrap.class);
    private final Vertx vertx;
    private final PgNodeConfig config;
    private final PgPrimaryElector elector;
    private final PgLocalStateStore store;
    private final PgFailoverMonitor monitor;
    private final PgRenewalLoop loop;

    public PgBootstrap(Vertx vertx, PgNodeConfig config, PgPrimaryElector elector, PgLocalStateStore store,
                       PgFailoverMonitor monitor, PgRenewalLoop loop) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.config = Objects.requireNonNull(config, "config");
        this.elector = Objects.requireNonNull(elector, "elector");
        this.store = Objects.requireNonNull(store, "store");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.loop = Objects.requireNonNull(loop, "loop");
    }

    /**
     * Bootstraps this node as the initial writer and returns the held control record. A refusal
     * changes nothing. A failure after the initial intent exists stops the renewal loop; the
     * record remains as history, so a later rerun is refused.
     */
    public Future<PgControlRecord> start(Path requestFile) {
        return PgBootstrapRequest.load(vertx, requestFile, config)
            .compose(request -> requireNoLocalHistory().map(request))
            .compose(request -> requireNoControlRecord(request).map(request))
            .compose(request -> elector.createInitialIntent(request.initialIntent())
                .compose(held -> startUnderOwnership(request, held)))
            .onFailure(failure -> {
                if (failure instanceof PgBootstrapException) {
                    logger.error("Bootstrap refused on node {}", config.nodeId(), failure);
                } else {
                    logger.error("Bootstrap failed on node {}", config.nodeId(), failure);
                }
            });
    }

    private Future<PgControlRecord> startUnderOwnership(PgBootstrapRequest request, PgControlRecord held) {
        // Renewal starts first, so the start effect cannot delay it.
        return loop.start()
            .compose(ignored -> store.begin(held.generation(), request.operationId(), BOOTSTRAP, config.nodeId(),
                request.toJson()))
            .compose(receipt -> monitor.startPrimary(held))
            .transform(started -> {
                if (started.succeeded()) return Future.succeededFuture(held);
                Throwable cause = started.cause();
                return loop.stop().transform(stopped -> {
                    if (stopped.failed()) cause.addSuppressed(stopped.cause());
                    return Future.<PgControlRecord>failedFuture(cause);
                });
            });
    }

    private Future<Void> requireNoLocalHistory() {
        return store.quarantine().compose(quarantine -> {
            if (quarantine.isPresent()) {
                throw new PgBootstrapException("The node is quarantined: " + quarantine.orElseThrow().reason());
            }
            return store.grant();
        }).map(grant -> {
            if (grant.isPresent()) {
                throw new PgBootstrapException("The node has a stored writer grant, so it has admitted a writer before");
            }
            return null;
        });
    }

    private Future<Void> requireNoControlRecord(PgBootstrapRequest request) {
        return elector.read().map(found -> {
            if (found.isEmpty()) return null;
            PgControlRecord existing = found.orElseThrow();
            if (request.operationId().equals(existing.intent().getString("operationId"))) {
                throw new PgBootstrapException("Bootstrap operation " + request.operationId()
                    + " was interrupted after its initial intent was created; reconcile it before resuming");
            }
            throw new PgBootstrapException("A control record already exists for this cluster incarnation");
        });
    }
}
