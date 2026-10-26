package dev.mars.peegeeq.failover;

/** Stub for the red run. It has no validation and builds no connection string. */
public record PgWriterEndpoint(String host, int port, String passfile) {
    public String connectionInfo(String nodeId) {
        return "";
    }
}
