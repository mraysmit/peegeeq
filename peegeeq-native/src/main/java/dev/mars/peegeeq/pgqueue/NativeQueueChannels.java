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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Single source of truth for native LISTEN/NOTIFY channel names.
 *
 * <p>PostgreSQL channel names are limited to 63 bytes. The native queue channel is
 * {@code {schema}_queue_{topic}}; when that exceeds the limit it is truncated and given
 * an 8-character MD5 suffix for uniqueness — the same strategy as the bitemporal
 * module's {@code createSafeChannelName}. Producer ({@code pg_notify}) and consumer
 * ({@code LISTEN}) MUST both derive the channel from this method: naive concatenation
 * past the limit makes {@code pg_notify} fail while the consumer listens on a different
 * (server-truncated) identifier, silently killing notification delivery.</p>
 */
final class NativeQueueChannels {

    private static final int MAX_CHANNEL_LENGTH = 63;

    private NativeQueueChannels() {}

    /**
     * Builds the LISTEN/NOTIFY channel name for a topic in a schema, truncated with a
     * deterministic hash suffix when the plain form exceeds PostgreSQL's 63-byte limit.
     */
    static String channelFor(String schema, String topic) {
        Objects.requireNonNull(schema, "schema cannot be null — PeeGeeQ has no default schema");
        Objects.requireNonNull(topic, "topic cannot be null");

        String channel = schema + "_queue_" + topic;
        if (channel.length() <= MAX_CHANNEL_LENGTH) {
            return channel;
        }

        String hashSuffix = "_" + md5Prefix8(channel);
        return channel.substring(0, MAX_CHANNEL_LENGTH - hashSuffix.length()) + hashSuffix;
    }

    private static String md5Prefix8(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            // MD5 is a mandatory JDK algorithm; if it is genuinely absent, fail loudly
            // rather than inventing an alternative channel-naming scheme on the fly
            throw new IllegalStateException("MD5 MessageDigest unavailable", e);
        }
    }
}
