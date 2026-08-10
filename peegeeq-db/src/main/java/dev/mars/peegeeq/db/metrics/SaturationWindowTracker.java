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
package dev.mars.peegeeq.db.metrics;

import dev.mars.peegeeq.api.metrics.SetupSaturationSnapshot;

import java.util.ArrayDeque;

/**
 * Rolling-window record of one sampled saturation signal (telemetry §4 gap G3) — event-loop
 * lag, pool acquire-wait, or any future per-setup sample series.
 *
 * <p>The manager's periodic sampler calls {@link #record}; any collector calls
 * {@link #snapshot()}. The window makes reads IDEMPOTENT: a snapshot never mutates state, so
 * two collectors reading concurrently see the same figures and neither steals the window from
 * the other. (The alternative — read-and-reset — makes the first reader consume the maximum
 * every other reader was entitled to see.)
 *
 * <p>The reported maximum is the worst lag inside the window, never a mean: saturation shows
 * up as spikes, and a mean averages them away at exactly the moment it matters.
 *
 * <p>Thread contract: {@code record} runs on the manager's event loop; {@code snapshot} runs
 * on any collector thread. Both synchronize on the deque — at one sample per
 * {@code sampleIntervalMs} the window holds at most a few hundred entries, so the critical
 * section is trivially short.
 */
public class SaturationWindowTracker {

    /** One sample: when it was taken (nanos) and the measured value (nanos). */
    private record Sample(long atNanos, long valueNanos) {}

    private final long windowNanos;
    private final int windowSeconds;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    public SaturationWindowTracker(int windowSeconds) {
        this.windowSeconds = windowSeconds;
        this.windowNanos = windowSeconds * 1_000_000_000L;
    }

    /**
     * Records one sample and prunes everything older than the window.
     *
     * @param atNanos  the sample's {@code System.nanoTime()}
     * @param valueNanos the measured value; negative values are clamped to zero — a sampler
     *                   cannot report a resource as MORE available than idle
     */
    public void record(long atNanos, long valueNanos) {
        long value = Math.max(0L, valueNanos);
        synchronized (samples) {
            samples.addLast(new Sample(atNanos, value));
            long cutoff = atNanos - windowNanos;
            while (!samples.isEmpty() && samples.peekFirst().atNanos() < cutoff) {
                samples.removeFirst();
            }
        }
    }

    /**
     * The window's statistics, or {@code null} when nothing has been sampled yet.
     *
     * <p>Null, never a zeroed value: a signal that has never been measured is unknown, and a
     * zero would claim a perfectly healthy resource on the evidence of nothing.
     */
    public SetupSaturationSnapshot.Window snapshot() {
        synchronized (samples) {
            if (samples.isEmpty()) {
                return null;
            }
            long maxNanos = 0L;
            for (Sample sample : samples) {
                if (sample.valueNanos() > maxNanos) {
                    maxNanos = sample.valueNanos();
                }
            }
            long latestNanos = samples.peekLast().valueNanos();
            return new SetupSaturationSnapshot.Window(
                    maxNanos / 1_000_000.0,
                    latestNanos / 1_000_000.0,
                    samples.size(),
                    windowSeconds);
        }
    }
}
