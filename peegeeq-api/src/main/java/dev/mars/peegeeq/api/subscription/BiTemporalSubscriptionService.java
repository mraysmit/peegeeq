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

package dev.mars.peegeeq.api.subscription;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import io.vertx.core.Future;

import java.util.List;

/**
 * Public lifecycle API for durable bitemporal subscriptions.
 *
 * <p>The durable identity is {@code (tableName, subscriptionName, consumerGroup)}.
 * Handler functions remain application-owned and must be registered again after a
 * process restart. Successful handler completion is the acknowledgement boundary.</p>
 */
public interface BiTemporalSubscriptionService {

    <T> Future<Void> subscribe(
        String tableName,
        String subscriptionName,
        String consumerGroup,
        String eventType,
        String aggregateId,
        MessageHandler<BiTemporalEvent<T>> handler,
        SubscriptionOptions options);

    Future<Void> pause(String tableName, String subscriptionName, String consumerGroup);

    Future<Void> resume(String tableName, String subscriptionName, String consumerGroup);

    Future<Void> cancel(String tableName, String subscriptionName, String consumerGroup);

    Future<Void> updateHeartbeat(
        String tableName, String subscriptionName, String consumerGroup);

    Future<Void> resetCursor(
        String tableName, String subscriptionName, String consumerGroup, long fromEventId);

    Future<BiTemporalSubscriptionInfo> getSubscription(
        String tableName, String subscriptionName, String consumerGroup);

    Future<List<BiTemporalSubscriptionInfo>> listSubscriptions(String tableName);

    Future<Void> close();
}
