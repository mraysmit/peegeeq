package dev.mars.peegeeq.sidecar;

/**
 * PostgreSQL Docker image used in peegeeq-pg-sidecar tests.
 * Must be kept in sync with the version used across the project.
 */
final class PgSidecarTestImageConstant {

    static final String POSTGRES_IMAGE = "postgres:15.13-alpine3.20";

    private PgSidecarTestImageConstant() {}
}
