package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the rule that decides whether adding a queue again is a refused change of its
 * implementation type.
 *
 * <p>The start bound of a subscription is a message id in the message table of the queue
 * implementation. A queue that has subscriptions keeps its implementation type.</p>
 */
@Tag(TestCategories.CORE)
class QueueImplementationChangeRuleCoreTest {

    @Test
    void anotherImplementationTypeIsRefusedWhenTheQueueHasASubscription() {
        assertTrue(PeeGeeQDatabaseSetupService.isRefusedImplementationChange("native", "outbox", 1));
        assertTrue(PeeGeeQDatabaseSetupService.isRefusedImplementationChange("outbox", "native", 4));
    }

    @Test
    void anotherImplementationTypeIsAcceptedWhenTheQueueHasNoSubscription() {
        assertFalse(PeeGeeQDatabaseSetupService.isRefusedImplementationChange("native", "outbox", 0));
    }

    @Test
    void theRegisteredImplementationTypeIsAcceptedWhenTheQueueHasASubscription() {
        assertFalse(PeeGeeQDatabaseSetupService.isRefusedImplementationChange("native", "native", 3));
    }
}
