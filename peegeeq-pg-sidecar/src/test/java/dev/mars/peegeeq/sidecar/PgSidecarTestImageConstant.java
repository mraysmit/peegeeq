package dev.mars.peegeeq.sidecar;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;

/**
 * PostgreSQL Docker image used in peegeeq-pg-sidecar tests.
 * Delegates to the project-wide constant — do not hardcode the tag here.
 */
final class PgSidecarTestImageConstant {

    static final String POSTGRES_IMAGE = PostgreSQLTestConstants.POSTGRES_IMAGE;

    private PgSidecarTestImageConstant() {}
}
