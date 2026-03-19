package dev.mars.peegeeq.api.deadletter;

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

import java.util.List;
import java.util.Optional;
import io.vertx.core.Future;

/**
 * Service interface for dead letter queue operations.
 * 
 * This interface is part of the PeeGeeQ API layer, providing
 * abstraction over implementation-specific dead letter queue details.
 * The dead letter queue stores messages that have failed processing
 * after exhausting all retry attempts.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public interface DeadLetterService {
    
    /**
     * Retrieves dead letter messages by topic.
     * 
     * @param topic The topic to filter by
     * @param limit Maximum number of messages to return
     * @param offset Number of messages to skip
     * @return Future containing the list of dead letter messages
     */
    Future<List<DeadLetterMessageInfo>> getDeadLetterMessages(String topic, int limit, int offset);
    
    /**
     * Retrieves all dead letter messages.
     * 
     * @param limit Maximum number of messages to return
     * @param offset Number of messages to skip
     * @return Future containing the list of dead letter messages
     */
    Future<List<DeadLetterMessageInfo>> getAllDeadLetterMessages(int limit, int offset);
    
    /**
     * Gets a specific dead letter message by ID.
     * 
     * @param id The message ID
     * @return Future containing the optional message
     */
    Future<Optional<DeadLetterMessageInfo>> getDeadLetterMessage(long id);
    
    /**
     * Reprocesses a dead letter message.
     * 
     * @param id The message ID
     * @param reason The reason for reprocessing
     * @return Future containing the result
     */
    Future<Boolean> reprocessDeadLetterMessage(long id, String reason);
    
    /**
     * Deletes a dead letter message.
     * 
     * @param id The message ID
     * @param reason The reason for deletion
     * @return Future containing the result
     */
    Future<Boolean> deleteDeadLetterMessage(long id, String reason);
    
    /**
     * Gets dead letter queue statistics.
     * 
     * @return Future containing the statistics
     */
    Future<DeadLetterStatsInfo> getStatistics();
    
    /**
     * Cleans up old dead letter messages.
     * 
     * @param retentionDays Number of days to retain messages
     * @return Future containing the number of messages deleted
     */
    Future<Integer> cleanupOldMessages(int retentionDays);
}

