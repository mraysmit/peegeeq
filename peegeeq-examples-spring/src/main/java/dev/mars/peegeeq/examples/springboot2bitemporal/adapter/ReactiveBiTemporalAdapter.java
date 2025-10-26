package dev.mars.peegeeq.examples.springboot2bitemporal.adapter;

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

import dev.mars.peegeeq.api.BiTemporalEvent;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Adapter for converting PeeGeeQ's reactive API to Project Reactor's Mono/Flux.
 * 
 * <p>This adapter demonstrates TWO approaches for integrating PeeGeeQ with Spring WebFlux:
 * <ol>
 *   <li><b>CompletableFuture API</b> - Current PeeGeeQ public API (broad compatibility)</li>
 *   <li><b>Native Vert.x Future API</b> - Proposed enhancement (better performance)</li>
 * </ol>
 * 
 * <p><b>Important Context:</b>
 * PeeGeeQ internally uses Vert.x 5.x {@link Future} for reactive operations, but currently
 * exposes {@link CompletableFuture} in the public API for broader compatibility. Internally,
 * PeeGeeQ converts via {@code .toCompletionStage().toCompletableFuture()}.
 * 
 * <p><b>Benefits of Native Vert.x Future API:</b>
 * <ul>
 *   <li>Better performance - eliminates conversion overhead</li>
 *   <li>More composable - can use Vert.x operators before converting</li>
 *   <li>Clearer intent - shows PeeGeeQ is Vert.x-based</li>
 * </ul>
 * 
 * <p>This adapter supports both approaches to demonstrate the trade-offs.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Component
public class ReactiveBiTemporalAdapter {
    private static final Logger log = LoggerFactory.getLogger(ReactiveBiTemporalAdapter.class);

    // ========== APPROACH 1: CompletableFuture API (Current) ==========
    
    /**
     * Converts a CompletableFuture to a Mono (current PeeGeeQ public API).
     * 
     * <p>This method works with the current PeeGeeQ API that returns CompletableFuture.
     * 
     * @param future The CompletableFuture from PeeGeeQ public API
     * @param <T> The type of the event payload
     * @return A Mono that completes with the bi-temporal event
     */
    public <T> Mono<BiTemporalEvent<T>> toMono(CompletableFuture<BiTemporalEvent<T>> future) {
        return Mono.fromFuture(future)
            .doOnError(error -> log.error("Error converting CompletableFuture to Mono", error))
            .doOnSuccess(result -> log.trace("Successfully converted CompletableFuture to Mono"));
    }
    
    /**
     * Converts a CompletableFuture of a list to a Flux (current PeeGeeQ public API).
     * 
     * <p>This method works with the current PeeGeeQ API that returns CompletableFuture.
     * 
     * @param future The CompletableFuture<List<T>> from PeeGeeQ public API
     * @param <T> The type of the event payload
     * @return A Flux that emits each bi-temporal event
     */
    public <T> Flux<BiTemporalEvent<T>> toFlux(CompletableFuture<List<BiTemporalEvent<T>>> future) {
        return Mono.fromFuture(future)
            .flatMapMany(Flux::fromIterable)
            .doOnError(error -> log.error("Error converting CompletableFuture<List> to Flux", error))
            .doOnComplete(() -> log.trace("Successfully converted CompletableFuture<List> to Flux"));
    }

    // ========== APPROACH 2: Native Vert.x Future API (Proposed) ==========
    
    /**
     * Converts a Vert.x Future to a Mono (proposed native API - more efficient).
     * 
     * <p>This method demonstrates how to use a native Vert.x Future API if PeeGeeQ
     * were to expose it. This approach is more efficient as it eliminates the
     * intermediate CompletableFuture conversion.
     * 
     * <p><b>Performance Benefit:</b> Eliminates {@code .toCompletionStage().toCompletableFuture()}
     * conversion overhead that happens internally in the current API.
     * 
     * @param future The Vert.x Future from proposed native API
     * @param <T> The type of the event payload
     * @return A Mono that completes with the bi-temporal event
     */
    public <T> Mono<BiTemporalEvent<T>> toMonoFromVertxFuture(Future<BiTemporalEvent<T>> future) {
        return Mono.fromCompletionStage(future.toCompletionStage())
            .doOnError(error -> log.error("Error converting Vert.x Future to Mono", error))
            .doOnSuccess(result -> log.trace("Successfully converted Vert.x Future to Mono"));
    }
    
    /**
     * Converts a Vert.x Future of a list to a Flux (proposed native API - more efficient).
     * 
     * <p>This method demonstrates how to use a native Vert.x Future API if PeeGeeQ
     * were to expose it. This approach is more efficient as it eliminates the
     * intermediate CompletableFuture conversion.
     * 
     * @param future The Vert.x Future<List<T>> from proposed native API
     * @param <T> The type of the event payload
     * @return A Flux that emits each bi-temporal event
     */
    public <T> Flux<BiTemporalEvent<T>> toFluxFromVertxFuture(Future<List<BiTemporalEvent<T>>> future) {
        return Mono.fromCompletionStage(future.toCompletionStage())
            .flatMapMany(Flux::fromIterable)
            .doOnError(error -> log.error("Error converting Vert.x Future<List> to Flux", error))
            .doOnComplete(() -> log.trace("Successfully converted Vert.x Future<List> to Flux"));
    }
    
    // ========== COMPOSABILITY EXAMPLE (Vert.x Future API) ==========
    
    /**
     * Example of composing Vert.x Future operations before converting to Mono.
     * 
     * <p>This demonstrates the composability benefit of the native Vert.x Future API.
     * With the native API, you can use Vert.x operators like {@code .compose()},
     * {@code .map()}, {@code .recover()} before converting to Mono/Flux.
     * 
     * @param future The Vert.x Future from proposed native API
     * @param <T> The type of the event payload
     * @return A Mono that completes with the transformed event
     */
    public <T> Mono<BiTemporalEvent<T>> toMonoWithVertxComposition(
            Future<BiTemporalEvent<T>> future) {
        
        // Example: Use Vert.x operators before converting to Mono
        Future<BiTemporalEvent<T>> composedFuture = future
            .onSuccess(event -> log.debug("Event appended: {}", event.getEventId()))
            .onFailure(error -> log.error("Failed to append event", error));
        
        return Mono.fromCompletionStage(composedFuture.toCompletionStage());
    }
}

