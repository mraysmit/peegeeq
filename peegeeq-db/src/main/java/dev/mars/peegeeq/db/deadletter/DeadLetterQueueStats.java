package dev.mars.peegeeq.db.deadletter;

import java.time.Instant;
import java.util.Objects;

/**
 * Statistics for the dead letter queue.
 */
public class DeadLetterQueueStats {
    private final long totalMessages;
    private final int uniqueTopics;
    private final int uniqueTables;
    private final Instant oldestFailure;
    private final Instant newestFailure;
    private final double averageRetryCount;
    
    public DeadLetterQueueStats(long totalMessages, int uniqueTopics, int uniqueTables,
                              Instant oldestFailure, Instant newestFailure, double averageRetryCount) {
        this.totalMessages = totalMessages;
        this.uniqueTopics = uniqueTopics;
        this.uniqueTables = uniqueTables;
        this.oldestFailure = oldestFailure;
        this.newestFailure = newestFailure;
        this.averageRetryCount = averageRetryCount;
    }
    
    public long getTotalMessages() {
        return totalMessages;
    }
    
    public int getUniqueTopics() {
        return uniqueTopics;
    }
    
    public int getUniqueTables() {
        return uniqueTables;
    }
    
    public Instant getOldestFailure() {
        return oldestFailure;
    }
    
    public Instant getNewestFailure() {
        return newestFailure;
    }
    
    public double getAverageRetryCount() {
        return averageRetryCount;
    }
    
    public boolean isEmpty() {
        return totalMessages == 0;
    }
    
    @Override
    public String toString() {
        return "DeadLetterQueueStats{" +
                "totalMessages=" + totalMessages +
                ", uniqueTopics=" + uniqueTopics +
                ", uniqueTables=" + uniqueTables +
                ", oldestFailure=" + oldestFailure +
                ", newestFailure=" + newestFailure +
                ", averageRetryCount=" + String.format("%.2f", averageRetryCount) +
                '}';
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeadLetterQueueStats that = (DeadLetterQueueStats) o;
        return totalMessages == that.totalMessages &&
               uniqueTopics == that.uniqueTopics &&
               uniqueTables == that.uniqueTables &&
               Double.compare(that.averageRetryCount, averageRetryCount) == 0 &&
               Objects.equals(oldestFailure, that.oldestFailure) &&
               Objects.equals(newestFailure, that.newestFailure);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(totalMessages, uniqueTopics, uniqueTables, oldestFailure, newestFailure, averageRetryCount);
    }
}
