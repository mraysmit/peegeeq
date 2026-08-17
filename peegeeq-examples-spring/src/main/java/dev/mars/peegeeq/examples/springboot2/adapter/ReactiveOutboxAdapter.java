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

import java.util.Arrays;
import java.util.List;
import io.vertx.core.Future;


/**
 * Boundary adapter that bridges PeeGeeQ's Vert.x {@link io.vertx.core.Future} API to
 * Project Reactor's {@link Mono} / {@link Flux} types used by Spring WebFlux.
 *
 * <p><b>Why this adapter exists.</b> PeeGeeQ is implemented with Vert.x 5 and exposes
 * a reactive, composable API based on {@code io.vertx.core.Future<T>}. A Spring WebFlux
 * application  which is the example client demonstrated in {@code springboot2}  composes
 * its pipelines with Reactor {@code Mono}/{@code Flux}. Reactor is not aware of Vert.x
 * {@code Future}, so a small, well-defined adapter is required at the boundary between
 * the two reactive worlds. This class is that boundary.
 *
 * <p><b>How the bridge works.</b> The adapter observes the Vert.x success and failure
 * signals directly and forwards them to a Reactor sink. Reactor operators then provide
 * aggregation and fallback behavior without introducing a second future abstraction.
 *
 * <p><b>Teaching intent.</b> The {@code springboot2} example is designed to show developers
 * how a non-Vert.x consumer (in this case Spring WebFlux / Reactor) integrates with the
 * Vert.x-based PeeGeeQ outbox without violating the reactive contract on either side.
 * The pattern shown here keeps Vert.x futures at the PeeGeeQ boundary and Reactor types
 * everywhere else inside the Spring layer.
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
     * <p>The future's success and failure handlers feed a Reactor sink directly.
     * Errors propagate through the reactive stream as an {@code onError} signal.
     *
     * @param future the Vert.x Future to bridge into a Mono
     * @param <T> the type of the result
     * @return a Mono that emits the future's result or its failure
     */
    public <T> Mono<T> toMono(Future<T> future) {
        return bridge(future)
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
        return bridge(future)
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
     * <p>All futures are observed directly and their signals are combined by Reactor.
     * Failure is delayed until every supplied future has terminated, matching the
     * wait-for-all contract and ensuring every failure is observed.
     *
     * @param futures the Vert.x Futures to await
     * @return a {@code Mono<Void>} that completes when all futures complete
     */
    public Mono<Void> allOf(Future<?>... futures) {
        int concurrency = Math.max(1, futures.length);
        return Flux.fromArray(futures)
            .flatMapDelayError(future -> bridge(future), concurrency, 1)
            .then()
            .doOnError(error -> log.error("Error in reactive adapter while waiting for all Futures", error))
            .doOnSuccess(v -> log.trace("All {} Futures completed successfully", futures.length));
    }

    /**
     * Returns a {@link Mono} that completes with the result of whichever supplied
     * Vert.x {@link Future} completes first (success or failure).
     *
     * <p>Each future is bridged directly and Reactor selects the first success or failure
     * signal. An empty input produces an empty Mono.
     *
     * @param futures the Vert.x Futures to race
     * @param <T> the type of the result
     * @return a Mono that emits the first completed future's value
     */
    @SafeVarargs
    public final <T> Mono<T> anyOf(Future<T>... futures) {
        List<Mono<T>> candidates = Arrays.stream(futures)
            .map(this::bridge)
            .toList();

        return Mono.firstWithSignal(candidates)
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
        return bridge(future)
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

    private <T> Mono<T> bridge(Future<T> future) {
        return Mono.create(sink -> future
            .onSuccess(sink::success)
            .onFailure(sink::error));
    }
}
