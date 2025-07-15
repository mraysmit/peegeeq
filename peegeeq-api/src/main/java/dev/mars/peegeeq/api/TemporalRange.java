package dev.mars.peegeeq.api;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a temporal range for querying bi-temporal events.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities with
 * bi-temporal event sourcing support.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
public class TemporalRange {
    
    private final Instant start;
    private final Instant end;
    private final boolean startInclusive;
    private final boolean endInclusive;
    
    /**
     * Creates a new temporal range.
     *
     * @param start The start time (can be null for unbounded start)
     * @param end The end time (can be null for unbounded end)
     * @param startInclusive Whether the start time is inclusive
     * @param endInclusive Whether the end time is inclusive
     */
    public TemporalRange(Instant start, Instant end, boolean startInclusive, boolean endInclusive) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("Start time cannot be after end time");
        }
        this.start = start;
        this.end = end;
        this.startInclusive = startInclusive;
        this.endInclusive = endInclusive;
    }
    
    /**
     * Creates a new inclusive temporal range.
     *
     * @param start The start time (can be null for unbounded start)
     * @param end The end time (can be null for unbounded end)
     */
    public TemporalRange(Instant start, Instant end) {
        this(start, end, true, true);
    }
    
    /**
     * Creates an unbounded temporal range (all time).
     */
    public TemporalRange() {
        this(null, null, true, true);
    }
    
    /**
     * Creates a temporal range from a specific point in time onwards.
     *
     * @param start The start time
     * @return A new temporal range
     */
    public static TemporalRange from(Instant start) {
        return new TemporalRange(start, null, true, true);
    }
    
    /**
     * Creates a temporal range up to a specific point in time.
     *
     * @param end The end time
     * @return A new temporal range
     */
    public static TemporalRange until(Instant end) {
        return new TemporalRange(null, end, true, true);
    }
    
    /**
     * Creates a temporal range for a specific point in time.
     *
     * @param pointInTime The specific time
     * @return A new temporal range
     */
    public static TemporalRange at(Instant pointInTime) {
        return new TemporalRange(pointInTime, pointInTime, true, true);
    }
    
    /**
     * Creates an unbounded temporal range (all time).
     *
     * @return A new temporal range
     */
    public static TemporalRange all() {
        return new TemporalRange();
    }
    
    /**
     * Gets the start time of the range.
     *
     * @return The start time, or null if unbounded
     */
    public Instant getStart() {
        return start;
    }
    
    /**
     * Gets the end time of the range.
     *
     * @return The end time, or null if unbounded
     */
    public Instant getEnd() {
        return end;
    }
    
    /**
     * Checks if the start time is inclusive.
     *
     * @return true if start is inclusive
     */
    public boolean isStartInclusive() {
        return startInclusive;
    }
    
    /**
     * Checks if the end time is inclusive.
     *
     * @return true if end is inclusive
     */
    public boolean isEndInclusive() {
        return endInclusive;
    }
    
    /**
     * Checks if this range is unbounded (covers all time).
     *
     * @return true if unbounded
     */
    public boolean isUnbounded() {
        return start == null && end == null;
    }
    
    /**
     * Checks if a given time falls within this range.
     *
     * @param time The time to check
     * @return true if the time is within the range
     */
    public boolean contains(Instant time) {
        if (time == null) {
            return false;
        }
        
        if (start != null) {
            if (startInclusive ? time.isBefore(start) : !time.isAfter(start)) {
                return false;
            }
        }
        
        if (end != null) {
            if (endInclusive ? time.isAfter(end) : !time.isBefore(end)) {
                return false;
            }
        }
        
        return true;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TemporalRange that = (TemporalRange) o;
        return startInclusive == that.startInclusive &&
               endInclusive == that.endInclusive &&
               Objects.equals(start, that.start) &&
               Objects.equals(end, that.end);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(start, end, startInclusive, endInclusive);
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(startInclusive ? "[" : "(");
        sb.append(start != null ? start.toString() : "-∞");
        sb.append(", ");
        sb.append(end != null ? end.toString() : "+∞");
        sb.append(endInclusive ? "]" : ")");
        return sb.toString();
    }
}
