package dev.mars.peegeeq.failover;

import io.vertx.core.Future;

/**
 * Local promotion of a standby and the checkpoint that follows it. Observations are derived and never
 * stored. Every operation fails with {@link PgPromotionException} when its result is not confirmed.
 */
public interface PgPromotionControl {
    /** Whether the local server is in recovery. */
    Future<Boolean> inRecovery();

    /**
     * Promotes the local standby and confirms that it left recovery. Refused when the server is not
     * in recovery. The command's exit code is not the evidence; the observed role is. A failure
     * leaves the role as the failure found it, so the caller must observe it again.
     */
    Future<Void> promote();

    /**
     * Takes a forced checkpoint on the promoted server and confirms that the latest checkpoint
     * advanced and is on the timeline of the current WAL position. Refused when the server is in
     * recovery.
     */
    Future<Void> checkpoint();

    /**
     * Points the local standby at another writer and confirms that its WAL receiver streams from it.
     * Refused when the server is not in recovery. The built connection string holds the node identity
     * as user and application name, and the path of a password file; it holds no password.
     */
    Future<Void> follow(String nodeId, PgWriterEndpoint writer);
}
