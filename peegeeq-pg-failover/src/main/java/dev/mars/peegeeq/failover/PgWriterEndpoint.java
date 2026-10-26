package dev.mars.peegeeq.failover;

import java.util.regex.Pattern;

/**
 * Where and how a standby connects to the writer it follows: the writer's host and port, and the path of
 * the password file that the deployment provisions for the standby's replication role. The password itself
 * is never held here, so it cannot reach a command line, a log line, or a receipt.
 *
 * <p>The host and the path are limited to characters that need no quoting in a connection string, so a
 * value cannot add or change a connection option.
 */
public record PgWriterEndpoint(String host, int port, String passfile) {
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,252}");
    private static final Pattern PASSFILE = Pattern.compile("/[A-Za-z0-9._/-]{1,4095}");

    public PgWriterEndpoint {
        if (host == null || !HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("Invalid writer host");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid writer port");
        if (passfile == null || !PASSFILE.matcher(passfile).matches()) {
            throw new IllegalArgumentException("Invalid password file path");
        }
    }

    /**
     * The {@code primary_conninfo} of the node with the given identity. The replication role and the
     * application name are the node identity (OD-11); nothing else about the node is supplied.
     */
    public String connectionInfo(String nodeId) {
        PgNodeConfig.requireIdentity(nodeId);
        return "host=" + host + " port=" + port + " user=" + nodeId + " application_name=" + nodeId
            + " passfile=" + passfile;
    }
}
