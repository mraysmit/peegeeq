package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finding 16: only the consumer's own close makes a failure an expected shutdown failure.
 *
 * <p>Before the correction a {@code RejectedExecutionException} or {@code ClosedChannelException}
 * anywhere in the cause chain counted as "expected during shutdown" while the consumer was open.
 * It was logged at DEBUG and, in the claim path, set the consumer closed, so an open consumer could
 * stop for good with no line above DEBUG. The register records that no run reached the synchronous
 * throw; this test pins the decision rule itself.</p>
 */
@Tag(TestCategories.CORE)
class OutboxConsumerShutdownFailureRuleCoreTest {

    @Test
    void anOpenConsumerDoesNotTreatARejectedExecutionAsShutdown() {
        assertFalse(OutboxConsumer.isExpectedShutdownFailure(false,
                new IllegalStateException("claim failed", new RejectedExecutionException("event loop gone"))));
    }

    @Test
    void anOpenConsumerDoesNotTreatAClosedChannelAsShutdown() {
        assertFalse(OutboxConsumer.isExpectedShutdownFailure(false, new ClosedChannelException()));
    }

    @Test
    void aClosedConsumerTreatsAnyFailureAsShutdown() {
        assertTrue(OutboxConsumer.isExpectedShutdownFailure(true, new IllegalStateException("pool closed")));
    }
}
