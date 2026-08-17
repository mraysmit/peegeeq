package dev.mars.peegeeq.examples.springboot2.adapter;

/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertSame;

@Tag(TestCategories.CORE)
class ReactiveOutboxAdapterTest {

    private ReactiveOutboxAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReactiveOutboxAdapter();
    }

    @Test
    void toMonoPropagatesSuccessAndFailure() {
        IllegalStateException failure = new IllegalStateException("adapter failure");

        StepVerifier.create(adapter.toMono(Future.succeededFuture("value")))
                .expectNext("value")
                .verifyComplete();

        StepVerifier.create(adapter.toMono(Future.failedFuture(failure)))
                .expectErrorMatches(error -> error == failure)
                .verify();
    }

    @Test
    void toMonoVoidCompletes() {
        StepVerifier.create(adapter.toMonoVoid(Future.succeededFuture()))
                .verifyComplete();
    }

    @Test
    void toFluxEmitsEveryFutureResult() {
        StepVerifier.create(adapter.toFlux(java.util.List.of(
                        Future.succeededFuture("first"),
                        Future.succeededFuture("second"))))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void allOfWaitsForEveryFutureAndPropagatesFailure() {
        Promise<String> first = Promise.promise();
        Promise<Integer> second = Promise.promise();

        StepVerifier.create(adapter.allOf(first.future(), second.future()))
                .then(() -> first.complete("done"))
                .then(() -> second.complete(2))
                .verifyComplete();

        IllegalArgumentException failure = new IllegalArgumentException("all failed");
        StepVerifier.create(adapter.allOf(
                        Future.succeededFuture("done"), Future.failedFuture(failure)))
                .expectErrorMatches(error -> error == failure)
                .verify();
    }

    @Test
    void anyOfEmitsTheFirstSignal() {
        Promise<String> first = Promise.promise();
        Promise<String> second = Promise.promise();

        StepVerifier.create(adapter.anyOf(first.future(), second.future()))
                .then(() -> second.complete("second"))
                .expectNext("second")
                .verifyComplete();
    }

    @Test
    void fallbackHandlesOriginalFailureAndSurfacesHandlerFailure() {
        IllegalStateException original = new IllegalStateException("original");
        StepVerifier.create(adapter.toMonoWithFallback(
                        Future.failedFuture(original), error -> {
                            assertSame(original, error);
                            return "fallback";
                        }))
                .expectNext("fallback")
                .verifyComplete();

        IllegalArgumentException fallbackFailure = new IllegalArgumentException("fallback failed");
        StepVerifier.create(adapter.toMonoWithFallback(
                        Future.failedFuture(original), error -> {
                            throw fallbackFailure;
                        }))
                .expectErrorMatches(error -> error == fallbackFailure)
                .verify();
    }
}
