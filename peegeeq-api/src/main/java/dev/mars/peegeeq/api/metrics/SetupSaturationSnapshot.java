/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
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
package dev.mars.peegeeq.api.metrics;

/**
 * One point-in-time view of a setup's resource saturation (telemetry §4 gap G3).
 *
 * <p><b>Ownership.</b> Produced by the CORE — the setup's own {@code PeeGeeQManager} samples its
 * own resources unconditionally as a property of running. Collectors (the REST layer, or any
 * embedder) read this snapshot; none of them cause the measurement. This is the same typed
 * hand-off contract as {@code QueueStats}: the core measures, the snapshot carries, the collector
 * flattens and adds nothing.
 *
 * <p><b>Read-idempotence.</b> Reading a snapshot never mutates sampler state. Values are computed
 * over a rolling window, so two collectors reading concurrently see the same figures and a
 * collector restart loses nothing. (A read-and-reset accessor would make the first reader steal
 * the window from every other.)
 *
 * <p><b>Absence contract.</b> A component that has never been sampled is {@code null}, never a
 * zeroed value: a zero would claim a perfectly healthy resource on the evidence of nothing
 * (the {@code DurationPercentiles} precedent). The two components sample on different cadences,
 * so each is absent or present independently.
 *
 * @param eventLoopLag    event-loop responsiveness of the setup's own Vert.x — the loop that
 *                        carries this setup's queue work (LISTEN/NOTIFY dispatch, consumer
 *                        polling, timer callbacks). Measured by scheduling a periodic timer and
 *                        recording how late it actually fires: a saturated loop reports normal
 *                        memory, threads and pool counts while every async operation queues
 *                        behind it. {@code null} until the sampler has measured.
 * @param poolAcquireWait time a real connection acquisition against the setup's own pool takes,
 *                        sampled by a periodic canary acquisition. Under pool exhaustion the
 *                        canary queues behind real work, so it measures exactly the wait a
 *                        caller experiences. {@code null} until the first canary has completed.
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 */
public record SetupSaturationSnapshot(Window eventLoopLag, Window poolAcquireWait) {

    /**
     * A rolling-window summary of one sampled saturation signal.
     *
     * @param maxMs         worst value observed inside the rolling window — saturation is
     *                      spikes, and a mean averages them away at exactly the moment they
     *                      matter
     * @param latestMs      the most recent sample
     * @param sampleCount   samples currently inside the window (how much evidence backs maxMs)
     * @param windowSeconds the rolling window length
     */
    public record Window(double maxMs, double latestMs, long sampleCount, int windowSeconds) {}
}
