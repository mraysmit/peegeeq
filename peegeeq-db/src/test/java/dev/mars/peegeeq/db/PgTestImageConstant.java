package dev.mars.peegeeq.db;

/**
 * Local test constant for the PostgreSQL Docker image used in peegeeq-db tests.
 * This mirrors PostgreSQLTestConstants.POSTGRES_IMAGE from peegeeq-test-support,
 * which cannot be depended on directly due to the circular dependency
 * (peegeeq-test-support → peegeeq-db).
 */
public final class PgTestImageConstant {

    /** The PostgreSQL Docker image used across all peegeeq-db tests. */
    public static final String POSTGRES_IMAGE = "postgres:15.13-alpine3.20";

    private PgTestImageConstant() {}
}
