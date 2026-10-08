package dev.mars.peegeeq.failover;

/** Who initiates takeover: an authenticated operator, or the surviving supervisors. */
public enum PgFailoverMode { MANUAL, AUTOMATIC }