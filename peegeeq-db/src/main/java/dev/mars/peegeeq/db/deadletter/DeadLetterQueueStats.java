package dev.mars.peegeeq.db.deadletter;

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
 * Statistics for the dead letter queue.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class
DeadLetterQueueStats {
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
