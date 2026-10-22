package dev.mars.peegeeq.failover;

import java.util.Locale;

/** A write-ahead log position. It is observed live and never stored as authority. */
public record PgLsn(long value) implements Comparable<PgLsn> {

    /** Parses PostgreSQL's text form: two hexadecimal words separated by a slash. */
    public static PgLsn parse(String text) {
        if (text == null || !text.matches("[0-9A-Fa-f]{1,8}/[0-9A-Fa-f]{1,8}")) {
            throw new IllegalArgumentException("Invalid WAL position: " + text);
        }
        int slash = text.indexOf('/');
        long high = Long.parseLong(text.substring(0, slash), 16);
        long low = Long.parseLong(text.substring(slash + 1), 16);
        return new PgLsn(high << 32 | low);
    }

    /** Whether this position is at or after the boundary. */
    public boolean reached(PgLsn boundary) {
        return compareTo(boundary) >= 0;
    }

    @Override
    public int compareTo(PgLsn other) {
        return Long.compareUnsigned(value, other.value);
    }

    @Override
    public String toString() {
        return (Long.toHexString(value >>> 32) + "/" + Long.toHexString(value & 0xFFFFFFFFL)).toUpperCase(Locale.ROOT);
    }
}
