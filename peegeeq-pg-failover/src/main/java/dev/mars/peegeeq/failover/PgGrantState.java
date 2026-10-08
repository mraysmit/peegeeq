package dev.mars.peegeeq.failover;

/** State of the local writer grant. Only {@link #OPEN} admits application writes. */
public enum PgGrantState { CLOSED, PREPARED, OPEN }