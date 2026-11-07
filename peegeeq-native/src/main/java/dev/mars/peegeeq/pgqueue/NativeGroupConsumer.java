package dev.mars.peegeeq.pgqueue;

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

import dev.mars.peegeeq.api.messaging.DeliveryOutcome;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import io.vertx.core.Future;

import java.util.function.Function;

/**
 * The consumer that a native consumer group reads its topic through.
 *
 * <p>The group subscribes a dispatcher that returns the {@link DeliveryOutcome} of each
 * delivery. The consumer applies the durable effect of that outcome. A failed dispatch
 * {@code Future} is a processing failure.</p>
 *
 * @param <T> the payload type
 */
interface NativeGroupConsumer<T> extends MessageConsumer<T> {

    /**
     * Starts consumption for one consumer group.
     *
     * @param groupName  the group name; the claim skips every message this group refused
     * @param dispatcher returns the outcome of one delivery
     * @return a Future that completes when consumption has started
     */
    Future<Void> subscribeGroup(String groupName, Function<Message<T>, Future<DeliveryOutcome>> dispatcher);
}
