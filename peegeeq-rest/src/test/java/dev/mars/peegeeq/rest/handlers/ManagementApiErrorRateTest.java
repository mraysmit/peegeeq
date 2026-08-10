package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.messaging.QueueStats;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the queue listing's {@code errorRate} derivation (metrics-stack review backlog,
 * 2026-08-09). The previous value was a hardcoded {@code 0.0} — a permanent zero presented as
 * a per-queue statistic, rendered as "0.00%" by two management-ui views regardless of how many
 * messages had terminally failed.
 *
 * <p>The real quantity is the DEAD-LETTER fraction: messages that terminally failed, out of
 * everything seen. Deliberately NOT {@code 1 - successRatePercent}: that formula counts
 * PENDING messages against success, so a healthy queue with a backlog would read as erroring.
 *
 * <p>Classification: CORE — pure derivation over a constructed {@link QueueStats}.
 */
@Tag(TestCategories.CORE)
class ManagementApiErrorRateTest {

    private QueueStats stats(long total, long processed, long deadLettered) {
        return new QueueStats("q", total, total - processed - deadLettered, processed,
            0, deadLettered, 0.0, 0.0, Instant.now(), Instant.now());
    }

    @Test
    void errorRateIsTheDeadLetterFraction() {
        // 3 of 100 messages terminally failed: 0.03, regardless of the 20-message backlog.
        assertEquals(0.03, ManagementApiHandler.errorRateFrom(stats(100, 77, 3)), 1e-9);
    }

    @Test
    void aBackloggedButHealthyQueueIsNotErroring() {
        // 80 pending, 0 dead-lettered: the 1-minus-success formula would claim 0.8 here.
        assertEquals(0.0, ManagementApiHandler.errorRateFrom(stats(100, 20, 0)), 1e-9);
    }

    @Test
    void anEmptyQueueHasNoErrors() {
        assertEquals(0.0, ManagementApiHandler.errorRateFrom(stats(0, 0, 0)), 1e-9);
    }
}
