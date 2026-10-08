package dev.mars.peegeeq.failover;

/** Observed state of the local PostgreSQL process. It is derived and never stored. */
public enum PgProcessState { RUNNING, STOPPED }