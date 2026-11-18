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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Coordinator-neutral contract for {@link PgClusterConfiguration} adapters (plan §9.34, contract 3). Each case states one
 * obligation of the port. Each adapter binds this suite to its real service. Every failure of the port is a
 * {@link PgClusterConfigurationException}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public abstract class PgClusterConfigurationContract {
    protected static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    protected static final PgClusterSettings FIRST = new PgClusterSettings(Duration.ofSeconds(30), Duration.ofSeconds(5),
        Duration.ofSeconds(3), Duration.ofSeconds(5));
    protected static final PgClusterSettings SECOND = new PgClusterSettings(Duration.ofSeconds(20), Duration.ofSeconds(2),
        Duration.ofSeconds(4), Duration.ofSeconds(3));
    protected Vertx vertx;
    protected String incarnation;
    private final List<PgClusterConfiguration> opened = new ArrayList<>();

    /** Prepares the binding for one test. On completion the service accepts requests. */
    protected abstract Future<Void> prepareBinding();

    /** Frees binding resources. */
    protected abstract Future<Void> releaseBinding();

    /** Node configuration of this incarnation. Its request timeout bounds every request. */
    protected abstract PgNodeConfig nodeConfig(String nodeId);

    /** An adapter permitted to read and write the record. */
    protected abstract Future<PgClusterConfiguration> configuration(PgNodeConfig node);

    /** An adapter whose writes the service rejects. Its reads are permitted. */
    protected abstract Future<PgClusterConfiguration> writeRejectedConfiguration(PgNodeConfig node);

    /** An adapter whose requests are accepted and never answered. */
    protected abstract Future<PgClusterConfiguration> silentConfiguration(PgNodeConfig node);

    /** An adapter whose service cannot be reached. */
    protected abstract PgClusterConfiguration unreachableConfiguration(PgNodeConfig node);

    /** Stores a raw value as the record of this incarnation without the port. */
    protected abstract Future<Void> storeExternally(String value);

    @BeforeEach
    public void setUpContract(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        incarnation = "inc-" + UUID.randomUUID();
        prepareBinding().onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @AfterEach
    public void tearDownContract(VertxTestContext context) {
        Future.join(opened.stream().map(PgClusterConfiguration::close).toList())
            .transform(closed -> releaseBinding().compose(ignored -> closed.succeeded()
                ? Future.<Void>succeededFuture() : Future.<Void>failedFuture(closed.cause())))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- read, create, update

    @Test public void anAbsentRecordReadsAsEmpty(VertxTestContext context) {
        authorised().compose(PgClusterConfiguration::read)
            .onSuccess(read -> context.verify(() -> {
                assertEquals(Optional.empty(), read, "No record was written, so none may be read");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test public void aCreatedRecordIsReadBackWithItsRevision(VertxTestContext context) {
        authorised().compose(writer -> writer.create(FIRST).compose(created -> {
            assertEquals(FIRST, created.settings());
            assertTrue(created.revision() > 0, "A stored record has a positive revision");
            return authorised().compose(PgClusterConfiguration::read).map(read -> {
                assertEquals(Optional.of(created), read, "Another adapter must read the created record");
                return null;
            });
        })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test public void aSecondCreateIsRefusedAndChangesNothing(VertxTestContext context) {
        authorised().compose(writer -> writer.create(FIRST).compose(created -> writer.create(SECOND).transform(second -> {
            refused(second, "A second create replaced an existing record");
            return writer.read();
        }).map(read -> {
            assertEquals(Optional.of(created), read, "A refused create changed the record");
            return null;
        }))).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test public void aSecondCreateWithEqualSettingsIsRefused(VertxTestContext context) {
        // A create states that no record exists; equal settings do not make an existing record new.
        authorised().compose(writer -> writer.create(FIRST).compose(created -> writer.create(FIRST).transform(second -> {
            refused(second, "A second create of equal settings was reported as a new record");
            return writer.read();
        }).map(read -> {
            assertEquals(Optional.of(created), read, "A refused create changed the record");
            return null;
        }))).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test public void anUpdateAtTheReadRevisionReplacesTheSettings(VertxTestContext context) {
        authorised().compose(writer -> writer.create(FIRST).compose(created -> writer.update(created, SECOND)
            .compose(updated -> {
                assertEquals(SECOND, updated.settings());
                assertTrue(updated.revision() > created.revision(), "An update must advance the revision");
                return writer.read().map(read -> {
                    assertEquals(Optional.of(updated), read);
                    return null;
                });
            }))).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test public void anUpdateFromAnOlderReadIsRefusedAndChangesNothing(VertxTestContext context) {
        // Two editors read the same record. The first update wins; the second is refused.
        var third = new PgClusterSettings(Duration.ofSeconds(40), Duration.ofSeconds(5), Duration.ofSeconds(3),
            Duration.ofSeconds(5));
        authorised().compose(first -> first.create(FIRST).compose(created -> first.update(created, SECOND)
            .compose(updated -> authorised().compose(second -> second.update(created, third).transform(stale -> {
                refused(stale, "An update from an older read replaced a newer record");
                return second.read();
            })).map(read -> {
                assertEquals(Optional.of(updated), read, "A refused update changed the record");
                return null;
            })))).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test public void anUpdateOfARecordThatDoesNotExistIsRefused(VertxTestContext context) {
        authorised().compose(writer -> writer.update(new PgClusterSettingsRecord(1, FIRST), SECOND).transform(update -> {
            refused(update, "An update created a record that did not exist");
            return writer.read();
        })).onSuccess(read -> context.verify(() -> {
            assertEquals(Optional.empty(), read, "A refused update stored a record");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- stored values that are not cluster settings

    @Test public void aMalformedStoredValueFailsTheRead(VertxTestContext context) {
        failsTheRead("not-json", context);
    }

    @Test public void aStoredValueThatBreaksTheTimingRuleFailsTheRead(VertxTestContext context) {
        // loop + 2 x retry = 31 s exceeds the 30 s TTL. The value is refused, not adjusted (OD-66).
        failsTheRead(FIRST.toJson().put("loopIntervalMillis", 25_000L).put("stopTimeoutMillis", 1_000L).encode(), context);
    }

    @Test public void aStoredValueWithAnUnknownFieldFailsTheRead(VertxTestContext context) {
        failsTheRead(FIRST.toJson().put("watchdogMode", "required").encode(), context);
    }

    @Test public void aStoredValueWithAMissingFieldFailsTheRead(VertxTestContext context) {
        JsonObject value = FIRST.toJson();
        value.remove("stopTimeoutMillis");
        failsTheRead(value.encode(), context);
    }

    // ---------------------------------------------------------------- failures

    @Test public void aWriteThatTheServiceRejectsFailsAndStoresNothing(VertxTestContext context) {
        writeRejectedConfiguration(nodeConfig("pg-node-1")).map(this::track)
            .compose(rejected -> rejected.create(FIRST).transform(create -> {
                refused(create, "A rejected write was reported as stored");
                return rejected.read();
            })).onSuccess(read -> context.verify(() -> {
                assertEquals(Optional.empty(), read, "A rejected write stored a record");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test public void anUnansweredServiceFailsEveryOperationWithinTheRequestDeadline(VertxTestContext context) {
        long started = System.nanoTime();
        silentConfiguration(nodeConfig("pg-node-1")).map(this::track).compose(silent -> silent.read()
            .transform(read -> refused(read, "An unanswered read returned settings"))
            .compose(ignored -> silent.create(FIRST).transform(create -> refused(create,
                "An unanswered create was reported as stored")))
            .compose(ignored -> silent.update(new PgClusterSettingsRecord(1, FIRST), SECOND).transform(update -> refused(
                update, "An unanswered update was reported as stored"))))
            .onSuccess(ignored -> context.verify(() -> {
                // Each request is bounded by the request deadline; a write may need a write and a read back.
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(15), "The request deadline was not honoured");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test public void anUnreachableServiceFailsEveryOperation(VertxTestContext context) {
        var unreachable = track(unreachableConfiguration(nodeConfig("pg-node-1")));
        unreachable.read().transform(read -> refused(read, "An unreachable service returned settings"))
            .compose(ignored -> unreachable.create(FIRST).transform(create -> refused(create,
                "A create to an unreachable service was reported as stored")))
            .compose(ignored -> unreachable.update(new PgClusterSettingsRecord(1, FIRST), SECOND).transform(update ->
                refused(update, "An update to an unreachable service was reported as stored")))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- helpers

    protected Future<PgClusterConfiguration> authorised() {
        return configuration(nodeConfig("pg-node-1")).map(this::track);
    }

    protected PgClusterConfiguration track(PgClusterConfiguration configuration) {
        opened.add(configuration);
        return configuration;
    }

    private void failsTheRead(String value, VertxTestContext context) {
        storeExternally(value).compose(ignored -> authorised()).compose(PgClusterConfiguration::read)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgClusterConfigurationException.class, failure);
                context.completeNow();
            })));
    }

    /** The outcome failed with the port's exception. The next step follows through {@code compose}. */
    protected static Future<Void> refused(AsyncResult<?> outcome, String message) {
        assertTrue(outcome.failed(), message);
        assertInstanceOf(PgClusterConfigurationException.class, outcome.cause());
        return Future.succeededFuture();
    }
}
