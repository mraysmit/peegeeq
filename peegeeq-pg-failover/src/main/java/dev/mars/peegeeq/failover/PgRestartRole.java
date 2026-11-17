package dev.mars.peegeeq.failover;

/** The role a conditional restart requires of the node (plan §9.33, contract 3): the writer, or a standby. */
public enum PgRestartRole { WRITER, STANDBY }
