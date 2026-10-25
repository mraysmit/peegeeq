package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static dev.mars.peegeeq.failover.PgReplicationFixture.STANDBYS;
import static dev.mars.peegeeq.failover.PgReplicationFixture.WRITER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract of the test fixtures: a server that an earlier test left running, because its teardown was
 * interrupted after a failure, must not break the next provisioning. vertx-junit5 does not await an
 * asynchronous {@code @AfterEach} after a failed test (register finding 13), so the fixtures stop the
 * previous cluster themselves before they provision a new one.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class PgFixtureLeftoverServerContractIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(PgFixtureLeftoverServerContractIntegrationTest.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final PgReplicationFixture NODES = new PgReplicationFixture();
    private static final PgSupervisedContainer SINGLE = new PgSupervisedContainer();
    private PgCommandRunner runner;
    private String singleDirectory;
    private String earlierDirectory;

    @BeforeAll static void startContainers() {
        NODES.start();
        SINGLE.start();
    }

    @AfterAll static void stopContainers() {
        SINGLE.close();
        NODES.close();
    }

    @BeforeEach void setUp(Vertx vertx) {
        runner = new LocalCommandRunner(vertx);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        NODES.forceStopAll(runner)
            .compose(ignored -> singleDirectory == null ? Future.<Void>succeededFuture()
                : SINGLE.forceStop(runner, singleDirectory))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // Provisioning three nodes and starting them twice can exceed the 30-second Vert.x test limit.
    @io.vertx.junit5.Timeout(value = 200, timeUnit = TimeUnit.SECONDS)
    @Test void provisioningAgainStopsTheClusterAnEarlierTestLeftRunning(VertxTestContext context) {
        NODES.provision(runner).compose(ignored -> NODES.control(runner, WRITER).startPrimary())
            .compose(ignored -> NODES.control(runner, STANDBYS.get(0)).startInRecovery())
            .compose(ignored -> {
                // No teardown: the writer and a standby keep running, as after an interrupted teardown.
                earlierDirectory = NODES.dataDirectory();
                return NODES.provision(runner);
            }).compose(ignored -> NODES.control(runner, WRITER).startPrimary())
            .compose(ignored -> NODES.control(runner, STANDBYS.get(0)).startInRecovery())
            .compose(ignored -> NODES.node(WRITER).exec(runner, PgSupervisedContainer.PG_CTL, "status", "-D",
                earlierDirectory))
            .compose(oldWriter -> {
                assertEquals(3, oldWriter.exitCode(), "The earlier writer is still running: " + oldWriter.output());
                return NODES.node(STANDBYS.get(0)).exec(runner, PgSupervisedContainer.PG_CTL, "status", "-D",
                    earlierDirectory);
            }).onSuccess(oldStandby -> context.verify(() -> {
                assertEquals(3, oldStandby.exitCode(), "The earlier standby is still running: " + oldStandby.output());
                assertNotEquals(earlierDirectory, NODES.dataDirectory());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @io.vertx.junit5.Timeout(value = 200, timeUnit = TimeUnit.SECONDS)
    @Test void aNewDataDirectoryStopsTheServerAnEarlierTestLeftRunning(VertxTestContext context) {
        SINGLE.newDataDirectory(runner).compose(created -> {
            earlierDirectory = created;
            singleDirectory = created;
            return control(created).startPrimary();
        }).compose(ignored -> SINGLE.newDataDirectory(runner)).compose(created -> {
            singleDirectory = created;
            return control(created).startPrimary();
        }).compose(ignored -> SINGLE.exec(runner, PgSupervisedContainer.PG_CTL, "status", "-D", earlierDirectory))
            .onSuccess(old -> context.verify(() -> {
                assertEquals(3, old.exitCode(), "The earlier server is still running: " + old.output());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // Provisioning is part of every @BeforeEach, which has a 120-second limit. A host on which it needs more
    // than its budget fails here, with the measured time, before a setup timeout hides the reason.
    @io.vertx.junit5.Timeout(value = 200, timeUnit = TimeUnit.SECONDS)
    @Test void provisioningThreeNodesStaysWithinItsBudget(VertxTestContext context) {
        NODES.provision(runner).onSuccess(ignored -> context.verify(() -> {
            Duration measured = NODES.lastProvisioningDuration();
            logger.info("Provisioned three nodes in {} ms (budget {} ms)", measured.toMillis(),
                PgReplicationFixture.PROVISIONING_BUDGET.toMillis());
            assertTrue(measured.compareTo(PgReplicationFixture.PROVISIONING_BUDGET) < 0,
                "Provisioning took " + measured.toSeconds() + " s; the budget is "
                    + PgReplicationFixture.PROVISIONING_BUDGET.toSeconds() + " s");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void aNewDataDirectoryStaysWithinItsBudget(VertxTestContext context) {
        SINGLE.newDataDirectory(runner).onSuccess(created -> context.verify(() -> {
            singleDirectory = created;
            Duration measured = SINGLE.lastProvisioningDuration();
            logger.info("Created a data directory in {} ms (budget {} ms)", measured.toMillis(),
                PgSupervisedContainer.PROVISIONING_BUDGET.toMillis());
            assertTrue(measured.compareTo(PgSupervisedContainer.PROVISIONING_BUDGET) < 0,
                "Creating a data directory took " + measured.toSeconds() + " s; the budget is "
                    + PgSupervisedContainer.PROVISIONING_BUDGET.toSeconds() + " s");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    private PgProcessControl control(String directory) {
        return new PgCtlProcessControl(runner, SINGLE.prefix(), PgSupervisedContainer.PG_CTL, directory,
            SINGLE.logFile(directory), COMMAND_TIMEOUT);
    }
}
