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

/**
 * Adapter for converting PeeGeeQ's CompletableFuture-based API to Project Reactor's Mono/Flux.
 * 
 * This adapter bridges the gap between PeeGeeQ's asynchronous operations (which return
 * CompletableFuture) and Spring WebFlux's reactive types (Mono and Flux).
 * 
 * Key Features:
 * - Converts CompletableFuture to Mono with proper error handling
 * - Converts CompletableFuture<Void> to Mono<Void>
 * - Converts multiple CompletableFutures to Flux
 * - Provides consistent error logging and handling
 * - Maintains reactive stream semantics
 * 
 * Usage Example:
 * <pre>
 * {@code
 * // Convert single CompletableFuture to Mono
 * Mono<String> result = adapter.toMono(outboxProducer.send(event));
 * 
 * // Convert void CompletableFuture to Mono<Void>
 * Mono<Void> completion = adapter.toMonoVoid(outboxProducer.sendWithTransaction(event));
 * 
 * // Convert multiple CompletableFutures to Flux
 * Flux<String> results = adapter.toFlux(List.of(future1, future2, future3));
 * }
 * </pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
@Component
public class ReactiveOutboxAdapter {
    private static final Logger log = LoggerFactory.getLogger(ReactiveOutboxAdapter.class);

    /**
     * Converts a CompletableFuture to a Mono with proper error handling.
     * 
     * This method wraps the CompletableFuture in a Mono, ensuring that any errors
     * are properly propagated through the reactive stream.
     * 
     * @param future The CompletableFuture to convert
     * @param <T> The type of the result
     * @return A Mono that completes with the future's result
     */
    public <T> Mono<T> toMono(CompletableFuture<T> future) {
        return Mono.fromFuture(future)
            .doOnError(error -> log.error("Error in reactive adapter while converting CompletableFuture to Mono", error))
            .doOnSuccess(result -> log.trace("Successfully converted CompletableFuture to Mono with result: {}", result));
    }

    /**
     * Converts a CompletableFuture<Void> to a Mono<Void>.
     * 
     * This is a specialized version for void operations, commonly used with
     * transactional operations that don't return a value.
     * 
     * @param future The CompletableFuture<Void> to convert
     * @return A Mono<Void> that completes when the future completes
     */
    public Mono<Void> toMonoVoid(CompletableFuture<Void> future) {
        return Mono.fromFuture(future)
            .then()
            .doOnError(error -> log.error("Error in reactive adapter while converting CompletableFuture<Void> to Mono<Void>", error))
            .doOnSuccess(v -> log.trace("Successfully converted CompletableFuture<Void> to Mono<Void>"));
    }

    /**
     * Converts a list of CompletableFutures to a Flux.
     * 
     * This method is useful when you need to process multiple asynchronous operations
     * as a reactive stream. Each CompletableFuture is converted to a Mono and then
     * combined into a Flux.
     * 
     * @param futures The list of CompletableFutures to convert
     * @param <T> The type of the results
     * @return A Flux that emits the results of all futures
     */
    public <T> Flux<T> toFlux(List<CompletableFuture<T>> futures) {
        return Flux.fromIterable(futures)
            .flatMap(this::toMono)
            .doOnError(error -> log.error("Error in reactive adapter while converting CompletableFutures to Flux", error))
            .doOnComplete(() -> log.trace("Successfully converted {} CompletableFutures to Flux", futures.size()));
    }

    /**
     * Converts multiple CompletableFutures to a Mono that completes when all futures complete.
     * 
     * This is useful for operations that need to wait for multiple asynchronous operations
     * to complete before proceeding.
     * 
     * @param futures The CompletableFutures to wait for
     * @return A Mono<Void> that completes when all futures complete
     */
    public Mono<Void> allOf(CompletableFuture<?>... futures) {
        return Mono.fromFuture(CompletableFuture.allOf(futures))
            .then()
            .doOnError(error -> log.error("Error in reactive adapter while waiting for all CompletableFutures", error))
            .doOnSuccess(v -> log.trace("All {} CompletableFutures completed successfully", futures.length));
    }

    /**
     * Converts multiple CompletableFutures to a Mono that completes when any future completes.
     * 
     * This is useful for race conditions or timeout scenarios where you want to proceed
     * as soon as any operation completes.
     * 
     * @param futures The CompletableFutures to race
     * @param <T> The type of the result
     * @return A Mono that completes with the first future's result
     */
    public <T> Mono<T> anyOf(CompletableFuture<T>... futures) {
        @SuppressWarnings("unchecked")
        CompletableFuture<T> anyFuture = (CompletableFuture<T>) CompletableFuture.anyOf(futures);
        
        return Mono.fromFuture(anyFuture)
            .doOnError(error -> log.error("Error in reactive adapter while racing CompletableFutures", error))
            .doOnSuccess(result -> log.trace("First CompletableFuture completed with result: {}", result));
    }

    /**
     * Converts a CompletableFuture to a Mono with a custom error handler.
     * 
     * This allows for more sophisticated error handling strategies, such as
     * fallback values or retry logic.
     * 
     * @param future The CompletableFuture to convert
     * @param errorHandler Function to handle errors and provide fallback
     * @param <T> The type of the result
     * @return A Mono that completes with the future's result or fallback
     */
    public <T> Mono<T> toMonoWithFallback(CompletableFuture<T> future, java.util.function.Function<Throwable, T> errorHandler) {
        return Mono.fromFuture(future)
            .onErrorResume(error -> {
                log.warn("Error in CompletableFuture, applying fallback handler", error);
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

