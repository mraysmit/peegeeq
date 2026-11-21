package dev.mars.peegeeq.failover;

import io.vertx.core.json.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The PostgreSQL settings that apply to every server in a PeeGeeQ-managed PostgreSQL cluster, for example
 * {@code work_mem = '64MB'} or {@code max_connections = '200'}.
 *
 * <p><b>Why this exists.</b> A cluster has one writer and one or more standbys, and the failover supervisor can make any
 * standby the writer. When each server keeps its settings in its own {@code postgresql.conf}, a change must be made on
 * every server. A server that was missed runs with different settings, and after a failover that server may be the
 * writer. This feature keeps one list of settings for the whole cluster, as Patroni does with its
 * {@code postgresql.parameters}, so that every server runs the same settings.
 *
 * <p><b>How the feature works, in the design.</b> An operator writes the list once to the coordinator (Consul). Each
 * node's supervisor reads the list, writes it to the file {@code peegeeq-cluster.conf} in the data directory, reloads
 * PostgreSQL, and checks that the values took effect. Each node's {@code postgresql.conf} includes that file. This
 * class is the list itself, and both sides use it:
 * <ul>
 *   <li>{@link #toJson()} and {@link #fromJson(JsonObject)}: the form stored in the coordinator.</li>
 *   <li>{@link #render()}: the content of {@code peegeeq-cluster.conf}.</li>
 *   <li>The constructor: the checks. They run when a list is written and again when it is read, so a bad list never
 *       reaches a server.</li>
 * </ul>
 * This class stores nothing and writes no file. Storing the list, writing the file, and applying it belong to other
 * classes.
 *
 * <p><b>What it refuses, and why.</b> One edit of this list reaches every server, so one bad entry can stop the whole
 * cluster. The constructor refuses:
 * <ul>
 *   <li>Settings that the supervisor sets itself or depends on, and settings that differ from node to node. The list
 *       and the reason for each are in {@code DENIED}. If the shared list could set them, one edit could break
 *       replication or failover on every server.</li>
 *   <li>Names that are not lower case. PostgreSQL ignores case in names, so {@code PORT} would set the refused
 *       {@code port}.</li>
 *   <li>Values with a line break or NUL. A line break would end the line in the file, and the rest of the value would
 *       be read as a setting nobody wrote.</li>
 * </ul>
 * It never corrects an entry. A list with a bad entry is refused as a whole.
 *
 * <p>Design: section 9.35, "PostgreSQL Parameters in the Coordinator", of
 * {@code docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md}.
 */
public record PgClusterParameters(Map<String, String> parameters) {
    /** The one field of the JSON form. */
    private static final String FIELD = "parameters";
    /** The first line of {@code peegeeq-cluster.conf}, for a person who opens the file on a server. */
    private static final String HEADER =
        "# PeeGeeQ cluster-wide PostgreSQL settings, written by the PeeGeeQ supervisor. Do not edit: the supervisor"
            + " replaces this file.";
    /**
     * One part of a setting name. A name is one part, such as {@code work_mem}, or several parts joined by dots, such as
     * {@code auto_explain.log_min_duration}, which is how PostgreSQL names the settings of an extension. The parts are
     * checked one at a time on purpose: a single regular expression that repeats a group recurses once per part in
     * Java, and a name with many parts then crashes with {@code StackOverflowError} instead of being refused.
     */
    private static final Pattern NAME_PART = Pattern.compile("[a-z_][a-z0-9_]*");
    /** Settings that may not be in the cluster-wide list. Each group says why. */
    private static final Set<String> DENIED = Set.of(
        // The supervisor sets these itself: which standbys must confirm a commit before it succeeds, and which writer
        // a standby copies from and through which replication slot. A shared value would overwrite its choice.
        "synchronous_standby_names", "primary_conninfo", "primary_slot_name",
        // The supervisor requires fixed values. Commits must wait for the standbys and reach the disk. Replication
        // needs WAL at replica level or higher. pg_rewind, which brings a former writer back as a standby, needs
        // hint-bit logging and full-page writes.
        "synchronous_commit", "fsync", "wal_level", "wal_log_hints", "full_page_writes",
        // The supervisor runs SQL queries on standbys, which needs hot_standby on. Each node's deployment sets how much
        // WAL a replication slot may keep; the supervisor reads that value and refuses an unlimited one.
        "hot_standby", "max_slot_wal_keep_size",
        // Each node's own network address and port, and the local socket the supervisor connects through. One value
        // for the whole cluster would give every node the same address.
        "listen_addresses", "port", "unix_socket_directories",
        // File locations belong to each node's deployment. pg_hba.conf, which decides which clients may connect, is
        // rewritten by the supervisor. An include directive would let the list load any other file.
        "data_directory", "config_file", "hba_file", "ident_file", "external_pid_file",
        "include", "include_if_exists", "include_dir",
        // These change how a standby replays the writer's WAL and when it stops being a standby. A delayed standby
        // never catches up, so bringing a former writer back would fall back to a full rebuild, which deletes its data
        // directory. A recovery target stops replay or promotes the standby. A promote trigger file promotes a standby
        // as soon as the file exists, without the lease that decides which server is the writer.
        "recovery_min_apply_delay", "recovery_target", "recovery_target_action", "recovery_target_inclusive",
        "recovery_target_lsn", "recovery_target_name", "recovery_target_time", "recovery_target_timeline",
        "recovery_target_xid", "promote_trigger_file");

    public PgClusterParameters {
        Objects.requireNonNull(parameters, "parameters");
        var checked = new TreeMap<String, String>();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "parameter name");
            String value = Objects.requireNonNull(entry.getValue(), "parameter value");
            if (!isName(name)) {
                throw new IllegalArgumentException("Parameter name is not a lower-case PostgreSQL name: " + name);
            }
            if (DENIED.contains(name)) {
                throw new IllegalArgumentException("Parameter " + name
                    + " cannot be in the cluster-wide settings: the PeeGeeQ supervisor or each node's own configuration"
                    + " sets it");
            }
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Parameter " + name
                    + " has a carriage return, line feed, or NUL in its value");
            }
            checked.put(name, value);
        }
        parameters = Collections.unmodifiableSortedMap(checked);
    }

    private static boolean isName(String name) {
        for (String part : name.split("\\.", -1)) {
            if (!NAME_PART.matcher(part).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The form stored in the coordinator: {@code {"parameters": {"max_connections": "200", "work_mem": "64MB"}}}, with
     * the names in order and every value a string.
     */
    public JsonObject toJson() {
        return new JsonObject().put(FIELD, new JsonObject(new LinkedHashMap<>(parameters)));
    }

    /**
     * Reads the form stored in the coordinator. Anyone with write access to the coordinator can change it, so it is
     * checked as strictly as a new list: a missing or extra field, a value that is not a string, or an entry the
     * constructor refuses fails the read.
     */
    public static PgClusterParameters fromJson(JsonObject json) {
        Objects.requireNonNull(json, "json");
        if (!Set.of(FIELD).equals(json.fieldNames())) {
            throw new IllegalArgumentException("Cluster parameters must have exactly the field parameters: "
                + json.fieldNames());
        }
        Object stored = json.getValue(FIELD);
        if (!(stored instanceof JsonObject object)) {
            throw new IllegalArgumentException("Cluster parameters field parameters is not an object: " + stored);
        }
        var parameters = new TreeMap<String, String>();
        for (String name : object.fieldNames()) {
            Object value = object.getValue(name);
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("Parameter " + name + " is not a string: " + value);
            }
            parameters.put(name, text);
        }
        return new PgClusterParameters(parameters);
    }

    /**
     * The content of {@code peegeeq-cluster.conf}: the header line, then one line {@code name = 'value'} per setting in
     * name order. In a quoted value PostgreSQL reads two single quotes as one quote and two backslashes as one
     * backslash, and reads a single backslash as the start of an escape. Each quote and backslash in a value is
     * therefore doubled, so the server reads back exactly the value in the list. This was checked on PostgreSQL 15.13.
     */
    public String render() {
        var out = new StringBuilder(HEADER).append('\n');
        parameters.forEach((name, value) -> out.append(name).append(" = '")
            .append(value.replace("\\", "\\\\").replace("'", "''")).append("'\n"));
        return out.toString();
    }
}
