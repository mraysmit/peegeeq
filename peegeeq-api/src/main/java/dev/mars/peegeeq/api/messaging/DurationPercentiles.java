package dev.mars.peegeeq.api.messaging;

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

/**
 * A duration distribution for one queue, measured app-side from a per-topic
 * Micrometer histogram. Two distributions use this shape: message
 * processing time, fed at ack (telemetry G1), and delivery latency,
 * enqueue → claim on the database clock (telemetry G2).
 *
 * Scope, stated plainly: the values cover messages seen by THIS backend
 * instance since it started — the histogram resets on restart and does not
 * see other instances' consumers. {@code sampleCount} carries how many
 * measurements the distribution is built from, so a caller can tell a
 * well-fed percentile from one resting on a handful of samples.
 *
 * A missing distribution is represented by the ABSENCE of this object (null),
 * never by zeroes — "no data" and "0 ms" are different facts.
 *
 * @param meanMs mean duration in milliseconds
 * @param p50Ms 50th percentile (median) duration in milliseconds
 * @param p95Ms 95th percentile duration in milliseconds
 * @param p99Ms 99th percentile duration in milliseconds
 * @param sampleCount how many measurements the distribution covers
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.0
 */
public record DurationPercentiles(
        double meanMs,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        long sampleCount) {
}
