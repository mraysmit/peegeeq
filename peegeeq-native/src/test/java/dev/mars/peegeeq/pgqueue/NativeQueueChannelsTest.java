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

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for native LISTEN/NOTIFY channel naming.
 *
 * <p>PostgreSQL channel names are limited to 63 bytes. Naive concatenation
 * ({@code schema + "_queue_" + topic}) silently breaks past the limit: the producer's
 * {@code pg_notify} errors (and is logged-and-swallowed) while the consumer LISTENs on a
 * different (truncated) identifier — messages then arrive only via the polling fallback,
 * or never in LISTEN_NOTIFY_ONLY mode. Producer and consumer must derive the channel from
 * this single function so both sides always agree, including the truncated form
 * (same strategy as bitemporal's createSafeChannelName: truncate + 8-char MD5 suffix).</p>
 */
@Tag(TestCategories.CORE)
class NativeQueueChannelsTest {

    @Test
    void testShortChannelIsPlainConcatenation() {
        assertEquals("peegeeq_test_queue_orders",
                NativeQueueChannels.channelFor("peegeeq_test", "orders"));
    }

    @Test
    void testLongChannelIsTruncatedWithinPostgresLimit() {
        String topic = "loadbalancing-roundrobin-queue-with-a-very-long-name-1781234567890";
        String channel = NativeQueueChannels.channelFor("peegeeq_test", topic);

        assertTrue(channel.length() <= 63,
                "Channel must fit PostgreSQL's 63-byte identifier limit, got "
                        + channel.length() + ": " + channel);
        assertTrue(channel.startsWith("peegeeq_test_queue_"),
                "The schema prefix must be preserved for isolation, got: " + channel);
    }

    @Test
    void testTruncationIsDeterministic() {
        String topic = "a-topic-name-long-enough-to-need-truncation-when-prefixed-0123456789";
        assertEquals(NativeQueueChannels.channelFor("peegeeq_test", topic),
                NativeQueueChannels.channelFor("peegeeq_test", topic),
                "Producer and consumer must derive the identical channel name");
    }

    @Test
    void testDistinctLongTopicsGetDistinctChannels() {
        String base = "a-topic-name-long-enough-to-need-truncation-when-prefixed-";
        String c1 = NativeQueueChannels.channelFor("peegeeq_test", base + "alpha");
        String c2 = NativeQueueChannels.channelFor("peegeeq_test", base + "beta");

        assertNotEquals(c1, c2,
                "Distinct topics must not collide after truncation (hash suffix required)");
    }

    @Test
    void testExactly63CharsIsNotTruncated() {
        // prefix "peegeeq_test_queue_" is 19 chars; a 44-char topic lands exactly on 63
        String topic = "t".repeat(44);
        String channel = NativeQueueChannels.channelFor("peegeeq_test", topic);

        assertEquals(63, channel.length());
        assertEquals("peegeeq_test_queue_" + topic, channel,
                "At exactly the limit no truncation is needed");
    }
}
