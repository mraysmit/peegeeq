package dev.mars.peegeeq.examples.springboot2.adapter;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import io.vertx.core.Future;


/**
 * Boundary adapter that bridges PeeGeeQ's Vert.x {@link io.vertx.core.Future} API to
 * Project Reactor's {@link Mono} / {@link Flux} types used by Spring WebFlux.
 *
 * <p><b>Why this adapter exists.</b> PeeGeeQ is implemented with Vert.x 5 and exposes
 * a reactive, composable API based on {@code io.vertx.core.Future<T>}. A Spring WebFlux
 * application — which is the example client demonstrated in {@code springboot2} — composes
 * its pipelines with Reactor {@code Mono}/{@code Flux}. Reactor is not aware of Vert.x
 * {@code Future}, so a small, well-defined adapter is required at the boundary between
 * the two reactive worlds. This class is that boundary.
 *
 * <p><b>How the bridge works.</b> Vert.x {@code Future} exposes
 * {@link io.vertx.core.Future#toCompletionStage()}, which returns a standard
 * {@link java.util.concurrent.CompletionStage}. Reactor's {@link Mono#fromCompletionStage}
 * subscribes to that stage and emits the result (or error) into the reactive stream.
 * No thread is parked and no value is ever blocked on with {@code .join()} or {@code .get()};
 * the conversion is purely a non-blocking signal hand-off.
 *
 * <p><b>Legitimate use of {@code CompletableFuture}.</b> A few combinator methods on this
 * class ({@link #allOf}, {@link #anyOf}) convert to {@code CompletableFuture} internally
 * solely to leverage the JDK's built-in {@code allOf} / {@code anyOf} combinators, then
 * immediately wrap the result back into a {@code Mono}. This is the only place the JDK
 * {@code CompletableFuture} type appears, and it is never blocked on. Everywhere else in
 * PeeGeeQ code the rule is reactive-only Vert.x {@code Future} composition.
 *
 * <p><b>Teaching intent.</b> The {@code springboot2} example is designed to show developers
 * how a non-Vert.x consumer (in this case Spring WebFlux / Reactor) integrates with the
 * Vert.x-based PeeGeeQ outbox without violating the reactive contract on either side.
 * The pattern shown here — {@code Future → CompletionStage → Mono} at the boundary, and
 * Reactor everywhere else inside the Spring layer — is the recommended approach.
 *
 * <p><b>Usage example.</b>
 * <pre>{@code
 * // Convert a single Vert.x Future to a Reactor Mono
 * Mono<String> result = adapter.toMono(outboxProducer.send(event));
 *
 * // Convert a Future<Void> (e.g. a transactional send) to a Mono<Void>
 * Mono<Void> completion = adapter.toMonoVoid(outboxProducer.sendInOwnTransaction(event));
 *
 * // Convert a list of Futures into a Flux of their results
 * Flux<String> results = adapter.toFlux(List.of(future1, future2, future3));
 * }</pre>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
@Component
public class ReactiveOutboxAdapter {
    private static final Logger log = LoggerFactory.getLogger(ReactiveOutboxAdapter.class);

    /**
     * Converts a Vert.x {@link Future} to a Reactor {@link Mono} without blocking.
     *
     * <p>The bridge goes through {@link Future#toCompletionStage()} and
     * {@link Mono#fromCompletionStage}; no thread is ever parked. Errors propagate
     * through the reactive stream as an {@code onError} signal.
     *
     * @param future the Vert.x Future to bridge into a Mono
     * @param <T> the type of the result
     * @return a Mono that emits the future's result or its failure
     */
    public <T> Mono<T> toMono(Future<T> future) {
        return Mono.fromCompletionStage(future.toCompletionStage())
            .doOnError(error -> log.error("Error in reactive adapter while converting Future to Mono", error))
            .doOnSuccess(result -> log.trace("Successfully converted Future to Mono with result: {}", result));
    }

    /**
     * Converts a Vert.x {@code Future<Void>} to a Reactor {@code Mono<Void>}.
     *
     * <p>Specialised for fire-and-complete operations that carry no payload, such as
     * transactional sends. As with {@link #toMono(Future)}, the conversion is purely
     * a non-blocking signal hand-off.
     *
     * @param future the {@code Future<Void>} to bridge
     * @return a {@code Mono<Void>} that completes when the future completes
     */
    public Mono<Void> toMonoVoid(Future<Void> future) {
        return Mono.fromCompletionStage(future.toCompletionStage())
            .then()
            .doOnError(error -> log.error("Error in reactive adapter while converting Future<Void> to Mono<Void>", error))
            .doOnSuccess(v -> log.trace("Successfully converted Future<Void> to Mono<Void>"));
    }

    /**
     * Converts a list of Vert.x {@link Future}s into a Reactor {@link Flux}.
     *
     * <p>Each Future is bridged via {@link #toMono(Future)} and the results are merged
     * into a single reactive stream. Useful for fan-out scenarios such as batch sends.
     *
     * @param futures the list of Vert.x Futures to bridge
     * @param <T> the type of the results
     * @return a Flux that emits the result of each future as it completes
     */
    public <T> Flux<T> toFlux(List<Future<T>> futures) {
        return Flux.fromIterable(futures)
            .flatMap(this::toMono)
            .doOnError(error -> log.error("Error in reactive adapter while converting Futures to Flux", error))
            .doOnComplete(() -> log.trace("Successfully converted {} Futures to Flux", futures.size()));
    }

    /**
     * Returns a {@code Mono<Void>} that completes when <em>all</em> of the supplied
     * Vert.x {@link Future}s complete successfully, or errors as soon as any of them fail.
     *
     * <p>Implementation note: this method reuses the JDK's
     * {@link CompletableFuture#allOf(CompletableFuture[])} combinator. Each Vert.x Future
     * is converted to a {@link CompletableFuture} purely so the JDK combinator can be
     * applied; the resulting future is then wrapped back into a Mono. No blocking call
     * ({@code .join()} / {@code .get()}) is made on any future.
     *
     * @param futures the Vert.x Futures to await
     * @return a {@code Mono<Void>} that completes when all futures complete
     */
    public Mono<Void> allOf(Future<?>... futures) {
        CompletableFuture<?>[] cfs = new CompletableFuture[futures.length];
        for (int i = 0; i < futures.length; i++) {
            cfs[i] = futures[i].toCompletionStage().toCompletableFuture();
        }
        return Mono.fromFuture(CompletableFuture.allOf(cfs))
            .then()
            .doOnError(error -> log.error("Error in reactive adapter while waiting for all Futures", error))
            .doOnSuccess(v -> log.trace("All {} Futures completed successfully", futures.length));
    }

    /**
     * Returns a {@link Mono} that completes with the result of whichever supplied
     * Vert.x {@link Future} completes first (success or failure).
     *
     * <p>Implementation note: as with {@link #allOf(Future...)}, this method reuses the
     * JDK's {@link CompletableFuture#anyOf(CompletableFuture[])} combinator. The conversion
     * to {@code CompletableFuture} is internal and non-blocking.
     *
     * @param futures the Vert.x Futures to race
     * @param <T> the type of the result
     * @return a Mono that emits the first completed future's value
     */
    @SafeVarargs
    public final <T> Mono<T> anyOf(Future<T>... futures) {
        CompletableFuture<?>[] cfs = new CompletableFuture[futures.length];
        for (int i = 0; i < futures.length; i++) {
            cfs[i] = futures[i].toCompletionStage().toCompletableFuture();
        }
        @SuppressWarnings("unchecked")
        CompletableFuture<T> anyFuture = (CompletableFuture<T>) CompletableFuture.anyOf(cfs);
        
        return Mono.fromFuture(anyFuture)
            .doOnError(error -> log.error("Error in reactive adapter while racing Futures", error))
            .doOnSuccess(result -> log.trace("First Future completed with result: {}", result));
    }

    /**
     * Converts a Vert.x {@link Future} to a Reactor {@link Mono}, applying a fallback
     * function if the future fails.
     *
     * <p>Use this overload when the caller wants to recover from a failure with a
     * default value rather than propagate the error downstream. If the fallback function
     * itself throws, the original error is replaced by the fallback's error.
     *
     * @param future the Vert.x Future to bridge
     * @param errorHandler function that produces a fallback value from the failure
     * @param <T> the type of the result
     * @return a Mono that emits either the future's result or the fallback value
     */
    public <T> Mono<T> toMonoWithFallback(Future<T> future, java.util.function.Function<Throwable, T> errorHandler) {
        return Mono.fromCompletionStage(future.toCompletionStage())
            .onErrorResume(error -> {
                log.warn("Error in Future, applying fallback handler", error);
                try {
                    T fallback = errorHandler.apply(error);
                    return Mono.just(fallback);
                } catch (Exception e) {
                    log.error("Fallback handler also failed", e);
                    return Mono.error(e);
                }
            });
    }
}

