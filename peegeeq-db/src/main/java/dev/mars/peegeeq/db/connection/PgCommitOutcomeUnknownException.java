package dev.mars.peegeeq.db.connection;

/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
 */

/**
 * Commit acknowledgement did not establish the required durability.
 * The transaction may have committed. Callers must reconcile its outcome and must not retry blindly.
 */
public final class PgCommitOutcomeUnknownException extends RuntimeException {

    public PgCommitOutcomeUnknownException(Throwable cause) {
        super("PostgreSQL commit outcome is unknown; reconcile before retrying", cause);
    }
}
