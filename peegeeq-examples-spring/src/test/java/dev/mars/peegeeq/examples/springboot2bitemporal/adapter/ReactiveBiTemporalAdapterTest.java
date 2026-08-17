package dev.mars.peegeeq.examples.springboot2bitemporal.adapter;

/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.SimpleBiTemporalEvent;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

@Tag(TestCategories.CORE)
class ReactiveBiTemporalAdapterTest {

    private ReactiveBiTemporalAdapter adapter;
    private BiTemporalEvent<String> firstEvent;
    private BiTemporalEvent<String> secondEvent;

    @BeforeEach
    void setUp() {
        adapter = new ReactiveBiTemporalAdapter();
        Instant now = Instant.parse("2026-08-17T00:00:00Z");
        firstEvent = new SimpleBiTemporalEvent<>("event-1", "settlement.created", "first", now, now);
        secondEvent = new SimpleBiTemporalEvent<>("event-2", "settlement.updated", "second", now, now);
    }

    @Test
    void toMonoPropagatesSuccessAndFailure() {
        IllegalStateException failure = new IllegalStateException("mono failure");

        StepVerifier.create(adapter.toMono(Future.succeededFuture(firstEvent)))
                .expectNext(firstEvent)
                .verifyComplete();

        StepVerifier.create(adapter.<String>toMono(Future.failedFuture(failure)))
                .expectErrorMatches(error -> error == failure)
                .verify();
    }

    @Test
    void toFluxEmitsEveryEventAndPropagatesFailure() {
        IllegalArgumentException failure = new IllegalArgumentException("flux failure");

        StepVerifier.create(adapter.toFlux(Future.succeededFuture(List.of(firstEvent, secondEvent))))
                .expectNext(firstEvent, secondEvent)
                .verifyComplete();

        StepVerifier.create(adapter.<String>toFlux(Future.failedFuture(failure)))
                .expectErrorMatches(error -> error == failure)
                .verify();
    }

    @Test
    void nativeMonoAdapterPropagatesSuccessAndFailure() {
        IllegalStateException failure = new IllegalStateException("native mono failure");

        StepVerifier.create(adapter.toMonoFromVertxFuture(Future.succeededFuture(firstEvent)))
                .expectNext(firstEvent)
                .verifyComplete();

        StepVerifier.create(adapter.<String>toMonoFromVertxFuture(Future.failedFuture(failure)))
                .expectErrorMatches(error -> error == failure)
                .verify();
    }

    @Test
    void nativeFluxAdapterEmitsEveryEventAndPropagatesFailure() {
        IllegalStateException failure = new IllegalStateException("native flux failure");

        StepVerifier.create(adapter.toFluxFromVertxFuture(
                        Future.succeededFuture(List.of(firstEvent, secondEvent))))
                .expectNext(firstEvent, secondEvent)
                .verifyComplete();

        StepVerifier.create(adapter.<String>toFluxFromVertxFuture(Future.failedFuture(failure)))
                .expectErrorMatches(error -> error == failure)
                .verify();
    }

    @Test
    void composedAdapterPropagatesSuccessAndFailure() {
        IllegalStateException failure = new IllegalStateException("composition failure");

        StepVerifier.create(adapter.toMonoWithVertxComposition(Future.succeededFuture(firstEvent)))
                .expectNext(firstEvent)
                .verifyComplete();

        StepVerifier.create(adapter.<String>toMonoWithVertxComposition(Future.failedFuture(failure)))
                .expectErrorMatches(error -> error == failure)
                .verify();
    }
}
