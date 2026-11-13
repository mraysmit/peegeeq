package dev.mars.peegeeq.failover;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Coordinator-neutral contract for {@link PgMemberPositions} adapters (plan §9.28, P7a and P7b). Each case states one
 * obligation of the port. Each adapter binds this suite to its real service. Every failure of the port is a
 * {@link PgMemberPositionException}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public abstract class PgMemberPositionsContract {
    protected static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    private static final PgLsn LSN = PgLsn.parse("0/5000000");
    protected Vertx vertx;
    protected String incarnation;
    private final List<PgMemberPositions> opened = new ArrayList<>();

    /** Prepares the binding for one test. On completion the service accepts requests from every member. */
    protected abstract Future<Void> prepareBinding();

    /** Frees binding resources. */
    protected abstract Future<Void> releaseBinding();

    /** Node configuration of this incarnation. Its lease TTL is the session TTL of a published record. */
    protected abstract PgNodeConfig nodeConfig(String nodeId);

    /** An authorised adapter for one member. */
    protected abstract Future<PgMemberPositions> positions(PgNodeConfig node);

    /** An adapter whose writes the service rejects. Its reads are permitted. */
    protected abstract Future<PgMemberPositions> writeRejectedPositions(PgNodeConfig node);

    /** An adapter whose reads the service filters: it may read only its own member's record. */
    protected abstract Future<PgMemberPositions> filteredPositions(PgNodeConfig node);

    /** An adapter whose requests are accepted and never answered. */
    protected abstract Future<PgMemberPositions> silentPositions(PgNodeConfig node);

    /** An adapter whose service cannot be reached. */
    protected abstract PgMemberPositions unreachablePositions(PgNodeConfig node);

    /**
     * Stores a raw record for a member key without the port. When {@code held}, a live session of the service holds
     * it, as a publication's record is held; otherwise no session holds it.
     */
    protected abstract Future<Void> storeExternally(String member, String value, boolean held);

    @BeforeEach
    public void setUpContract(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        incarnation = "inc-" + UUID.randomUUID();
        prepareBinding().onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @AfterEach
    public void tearDownContract(VertxTestContext context) {
        Future.join(opened.stream().map(PgMemberPositions::close).toList())
            .transform(closed -> releaseBinding().compose(ignored -> closed.succeeded()
                ? Future.<Void>succeededFuture() : Future.<Void>failedFuture(closed.cause())))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- publication and read

    @Test public void everyPublishedPositionIsReadBackByEveryMember(VertxTestContext context) {
        var first = new PgMemberPosition("pg-node-1", 1, PgLsn.parse("0/A000028"));
        var second = new PgMemberPosition("pg-node-2", 1, PgLsn.parse("0/9FFF000"));
        member("pg-node-1").compose(one -> one.publish(first))
            .compose(ignored -> member("pg-node-2")).compose(two -> two.publish(second))
            .compose(ignored -> member("pg-node-3")).compose(PgMemberPositions::read)
            .onSuccess(read -> context.verify(() -> {
                assertEquals(Map.of("pg-node-1", first, "pg-node-2", second), read);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test public void aLaterPublicationReplacesTheEarlierOne(VertxTestContext context) {
        var earlier = new PgMemberPosition("pg-node-2", 1, PgLsn.parse("0/5000000"));
        var later = new PgMemberPosition("pg-node-2", 2, PgLsn.parse("0/6000000"));
        member("pg-node-2").compose(two -> two.publish(earlier).compose(ignored -> two.publish(later))
            .compose(ignored -> two.read()))
            .onSuccess(read -> context.verify(() -> {
                assertEquals(Map.of("pg-node-2", later), read);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test public void anEmptyNamespaceReadsAsNoPositions(VertxTestContext context) {
        member("pg-node-1").compose(PgMemberPositions::read)
            .onSuccess(read -> context.verify(() -> {
                assertEquals(Map.of(), read, "A member that never published must be absent");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // A refusal of another node's position before sending is logged by the adapter under its own logger, so each binding
    // states that case with its adapter's expected log line.

    // ---------------------------------------------------------------- session-bound records (P7b)

    @Test
    // The record must outlive the session TTL, and the session can take up to twice the TTL to end.
    @io.vertx.junit5.Timeout(value = 90, timeUnit = TimeUnit.SECONDS)
    public void aRecordDisappearsWhenItsMemberStopsPublishing(VertxTestContext context) {
        var position = new PgMemberPosition("pg-node-2", 1, LSN);
        long ttl = nodeConfig("pg-node-2").leaseTtl().toNanos();
        member("pg-node-2").compose(two -> two.publish(position).compose(ignored -> two.read()).compose(read -> {
            assertEquals(Map.of("pg-node-2", position), read, "The precondition failed: the position was not stored");
            return awaitAbsent(two, "pg-node-2", System.nanoTime() + 3 * ttl);
        })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    // Publications every two seconds for one and a half TTLs.
    @io.vertx.junit5.Timeout(value = 90, timeUnit = TimeUnit.SECONDS)
    public void aMemberThatKeepsPublishingKeepsItsRecordBeyondTheTtl(VertxTestContext context) {
        var position = new PgMemberPosition("pg-node-2", 1, LSN);
        long until = System.nanoTime() + nodeConfig("pg-node-2").leaseTtl().toNanos() * 3 / 2;
        member("pg-node-2").compose(two -> keepPublishing(two, position, until).compose(ignored -> two.read()))
            .onSuccess(read -> context.verify(() -> {
                assertEquals(Map.of("pg-node-2", position), read, "A member that kept publishing lost its record");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @io.vertx.junit5.Timeout(value = 90, timeUnit = TimeUnit.SECONDS)
    public void aMemberWhoseRecordExpiredPublishesAgain(VertxTestContext context) {
        var first = new PgMemberPosition("pg-node-2", 1, LSN);
        var again = new PgMemberPosition("pg-node-2", 1, PgLsn.parse("0/6000000"));
        long ttl = nodeConfig("pg-node-2").leaseTtl().toNanos();
        member("pg-node-2").compose(two -> two.publish(first)
                .compose(ignored -> awaitAbsent(two, "pg-node-2", System.nanoTime() + 3 * ttl))
                .compose(ignored -> two.publish(again)).compose(ignored -> two.read()))
            .onSuccess(read -> context.verify(() -> {
                assertEquals(Map.of("pg-node-2", again), read, "A member could not publish after its record expired");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test public void aRecordThatNoSessionHoldsFailsTheRead(VertxTestContext context) {
        storeExternally("pg-node-2", value(), false)
            .compose(ignored -> member("pg-node-1")).compose(PgMemberPositions::read)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgMemberPositionException.class, failure);
                context.completeNow();
            })));
    }

    // ---------------------------------------------------------------- failures

    @Test public void aPublicationThatTheServiceRejectsFails(VertxTestContext context) {
        writeRejectedPositions(nodeConfig("pg-node-1")).map(this::track)
            .compose(one -> one.publish(new PgMemberPosition("pg-node-1", 1, LSN)))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgMemberPositionException.class, failure);
                context.completeNow();
            })));
    }

    @Test public void aMalformedRecordFailsTheRead(VertxTestContext context) {
        storeExternally("pg-node-2", "not-json", true)
            .compose(ignored -> member("pg-node-1")).compose(PgMemberPositions::read)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgMemberPositionException.class, failure);
                context.completeNow();
            })));
    }

    @Test public void aRecordOfANodeOutsideTheMembershipFailsTheRead(VertxTestContext context) {
        storeExternally("pg-node-9", value(), true)
            .compose(ignored -> member("pg-node-1")).compose(PgMemberPositions::read)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgMemberPositionException.class, failure);
                context.completeNow();
            })));
    }

    @Test public void aReadThatTheServiceFilteredFails(VertxTestContext context) {
        storeExternally("pg-node-1", value(), true).compose(ignored -> storeExternally("pg-node-2", value(), true))
            .compose(ignored -> filteredPositions(nodeConfig("pg-node-1"))).map(this::track)
            .compose(PgMemberPositions::read)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgMemberPositionException.class, failure);
                context.completeNow();
            })));
    }

    @Test public void anUnansweredServiceFailsBothOperationsWithinTheRequestDeadline(VertxTestContext context) {
        long started = System.nanoTime();
        silentPositions(nodeConfig("pg-node-1")).map(this::track).compose(silent ->
            silent.publish(new PgMemberPosition("pg-node-1", 1, LSN))
                .transform(publish -> refused(publish, "An unanswered publication was reported as stored"))
                .compose(ignored -> silent.read().transform(read -> refused(read, "An unanswered read returned positions"))))
            .onSuccess(ignored -> context.verify(() -> {
                // Each request is bounded by the request deadline; a publication may need two requests.
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10), "The request deadline was not honoured");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test public void anUnreachableServiceFailsBothOperations(VertxTestContext context) {
        var unreachable = track(unreachablePositions(nodeConfig("pg-node-1")));
        unreachable.publish(new PgMemberPosition("pg-node-1", 1, LSN))
            .transform(publish -> refused(publish, "A publication to an unreachable service was reported as stored"))
            .compose(ignored -> unreachable.read().transform(read -> refused(read, "An unreachable service returned positions")))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- helpers

    protected Future<PgMemberPositions> member(String nodeId) {
        return positions(nodeConfig(nodeId)).map(this::track);
    }

    protected PgMemberPositions track(PgMemberPositions positions) {
        opened.add(positions);
        return positions;
    }

    private static String value() {
        return new JsonObject().put("timeline", 1).put("lsn", "0/5000000").encode();
    }

    /** The outcome failed with the port's exception. The next step follows through {@code compose}. */
    private static Future<Void> refused(AsyncResult<?> outcome, String message) {
        assertTrue(outcome.failed(), message);
        assertInstanceOf(PgMemberPositionException.class, outcome.cause());
        return Future.succeededFuture();
    }

    private Future<Void> awaitAbsent(PgMemberPositions reader, String member, long deadline) {
        return reader.read().compose(read -> {
            if (!read.containsKey(member)) return Future.<Void>succeededFuture();
            if (System.nanoTime() >= deadline) {
                return Future.<Void>failedFuture(new AssertionError("The record of " + member + " outlived its session"));
            }
            return vertx.timer(500).compose(ignored -> awaitAbsent(reader, member, deadline));
        });
    }

    private Future<Void> keepPublishing(PgMemberPositions publisher, PgMemberPosition position, long until) {
        return publisher.publish(position).compose(ignored -> System.nanoTime() >= until
            ? Future.<Void>succeededFuture()
            : vertx.timer(2000).compose(waited -> keepPublishing(publisher, position, until)));
    }
}
