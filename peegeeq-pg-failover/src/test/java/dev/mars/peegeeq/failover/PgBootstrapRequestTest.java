package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Vertx;
import io.vertx.core.file.FileSystemException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** The first-start bootstrap request file, against a real directory. */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PgBootstrapRequestTest {
    private static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    private static final PgNodeConfig CONFIG = new PgNodeConfig("cluster-a", "inc-1", "pg-node-1", MEMBERS,
        Duration.ofSeconds(20), Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8),
        PgWatchdogMode.OFF);
    private static final String OPERATION = UUID.randomUUID().toString();
    @TempDir Path directory;

    @Test void aValidRequestGivesWithdrawnIntentWithPendingRevisionOne(Vertx vertx, VertxTestContext context)
            throws IOException {
        PgBootstrapRequest.load(vertx, write(valid()), CONFIG).onComplete(context.succeeding(request ->
            context.verify(() -> {
                assertEquals(OPERATION, request.operationId());
                assertEquals(valid(), request.toJson());
                assertEquals(new JsonObject().put("writerNodeId", "pg-node-1").put("phase", "WITHDRAWN")
                    .put("operationId", OPERATION)
                    .put("pendingDurabilityPolicy", new JsonObject().put("revision", 1L)
                        .put("requiredStandbyNodeIds", new JsonArray().add("pg-node-2").add("pg-node-3"))),
                    request.initialIntent());
                context.completeNow();
            })));
    }

    @Test void membershipOrderDoesNotMatter(Vertx vertx, VertxTestContext context) throws IOException {
        var reordered = valid().put("memberNodeIds", new JsonArray().add("pg-node-3").add("pg-node-1").add("pg-node-2"));
        PgBootstrapRequest.load(vertx, write(reordered), CONFIG).onComplete(context.succeedingThenComplete());
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrapRequest",
        message = "Bootstrap request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = FileSystemException.class)
    void aMissingRequestFileIsRejected(Vertx vertx, VertxTestContext context) {
        rejected(vertx, context, directory.resolve("bootstrap-request.json"));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrapRequest",
        message = "Bootstrap request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void aRequestThatIsNotJsonIsRejected(Vertx vertx, VertxTestContext context) throws IOException {
        rejected(vertx, context, Files.writeString(directory.resolve("bootstrap-request.json"), "not json"));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrapRequest",
        message = "Bootstrap request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void anUnknownFieldIsRejected(Vertx vertx, VertxTestContext context) throws IOException {
        rejected(vertx, context, write(valid().put("durabilityPolicy", new JsonObject())));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrapRequest",
        message = "Bootstrap request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void aMissingFieldIsRejected(Vertx vertx, VertxTestContext context) throws IOException {
        var request = valid();
        request.remove("operationId");
        rejected(vertx, context, write(request));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrapRequest",
        message = "Bootstrap request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void aRequestForAnotherClusterIsRejected(Vertx vertx, VertxTestContext context) throws IOException {
        rejected(vertx, context, write(valid().put("clusterId", "cluster-b")));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrapRequest",
        message = "Bootstrap request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void aRequestForAnotherIncarnationIsRejected(Vertx vertx, VertxTestContext context) throws IOException {
        rejected(vertx, context, write(valid().put("incarnation", "inc-2")));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrapRequest",
        message = "Bootstrap request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void aRequestWithDifferentMembershipIsRejected(Vertx vertx, VertxTestContext context) throws IOException {
        rejected(vertx, context, write(valid().put("memberNodeIds", new JsonArray().add("pg-node-1").add("pg-node-2"))));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrapRequest",
        message = "Bootstrap request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void aRequestNamingAnotherInitialWriterIsRejected(Vertx vertx, VertxTestContext context) throws IOException {
        rejected(vertx, context, write(valid().put("initialWriterNodeId", "pg-node-2")
            .put("requiredStandbyNodeIds", new JsonArray().add("pg-node-1").add("pg-node-3"))));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrapRequest",
        message = "Bootstrap request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void aStandbyListNamingTheWriterIsRejected(Vertx vertx, VertxTestContext context) throws IOException {
        rejected(vertx, context, write(valid().put("requiredStandbyNodeIds",
            new JsonArray().add("pg-node-1").add("pg-node-2"))));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrapRequest",
        message = "Bootstrap request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void aStandbyListNamingANonMemberIsRejected(Vertx vertx, VertxTestContext context) throws IOException {
        rejected(vertx, context, write(valid().put("requiredStandbyNodeIds",
            new JsonArray().add("pg-node-2").add("pg-node-9"))));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrapRequest",
        message = "Bootstrap request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void anEmptyOrRepeatingStandbyListIsRejected(Vertx vertx, VertxTestContext context) throws IOException {
        rejected(vertx, context, write(valid().put("requiredStandbyNodeIds",
            new JsonArray().add("pg-node-2").add("pg-node-2"))));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrapRequest",
        message = "Bootstrap request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void anOperationThatIsNotAUuidIsRejected(Vertx vertx, VertxTestContext context) throws IOException {
        rejected(vertx, context, write(valid().put("operationId", "op-1")));
    }

    private void rejected(Vertx vertx, VertxTestContext context, Path file) {
        PgBootstrapRequest.load(vertx, file, CONFIG).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgBootstrapException.class, failure);
            context.completeNow();
        })));
    }

    private Path write(JsonObject request) throws IOException {
        return Files.writeString(directory.resolve("bootstrap-request.json"), request.encodePrettily());
    }

    private static JsonObject valid() {
        return new JsonObject().put("clusterId", "cluster-a").put("incarnation", "inc-1")
            .put("memberNodeIds", new JsonArray().add("pg-node-1").add("pg-node-2").add("pg-node-3"))
            .put("initialWriterNodeId", "pg-node-1").put("operationId", OPERATION)
            .put("requiredStandbyNodeIds", new JsonArray().add("pg-node-2").add("pg-node-3"));
    }
}
