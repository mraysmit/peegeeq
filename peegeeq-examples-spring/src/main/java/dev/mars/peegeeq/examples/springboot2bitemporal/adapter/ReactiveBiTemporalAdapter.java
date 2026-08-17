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


/**
 * Boundary adapter that converts PeeGeeQ's Vert.x {@link Future} API to Project
 * Reactor's {@link Mono} and {@link Flux} types.
 *
 * <p>The adapter observes Vert.x success and failure signals directly and forwards
 * them to a Reactor sink. It therefore keeps one asynchronous abstraction on each
 * side of the application boundary and introduces no blocking or future bridge.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Component
public class ReactiveBiTemporalAdapter {
    private static final Logger log = LoggerFactory.getLogger(ReactiveBiTemporalAdapter.class);

    /**
     * Converts a Vert.x future containing one event to a Mono.
     *
     * @param future The future returned by the PeeGeeQ public API
     * @param <T> The type of the event payload
     * @return A Mono that completes with the bi-temporal event
     */
    public <T> Mono<BiTemporalEvent<T>> toMono(Future<BiTemporalEvent<T>> future) {
        return bridge(future)
            .doOnError(error -> log.error("Error converting Vert.x Future to Mono", error))
            .doOnSuccess(result -> log.trace("Successfully converted Vert.x Future to Mono"));
    }
    
    /**
     * Converts a Vert.x future containing a list of events to a Flux.
     *
     * @param future The Future<List<T>> from PeeGeeQ public API
     * @param <T> The type of the event payload
     * @return A Flux that emits each bi-temporal event
     */
    public <T> Flux<BiTemporalEvent<T>> toFlux(Future<List<BiTemporalEvent<T>>> future) {
        return bridge(future)
            .flatMapMany(Flux::fromIterable)
            .doOnError(error -> log.error("Error converting Future<List> to Flux", error))
            .doOnComplete(() -> log.trace("Successfully converted Future<List> to Flux"));
    }

    /**
     * Converts a Vert.x future to a Mono. This explicitly named form is retained
     * for the example service API and has the same signaling contract as {@link #toMono(Future)}.
     *
     * @param future The Vert.x future to convert
     * @param <T> The type of the event payload
     * @return A Mono that completes with the bi-temporal event
     */
    public <T> Mono<BiTemporalEvent<T>> toMonoFromVertxFuture(Future<BiTemporalEvent<T>> future) {
        return bridge(future)
            .doOnError(error -> log.error("Error converting Vert.x Future to Mono", error))
            .doOnSuccess(result -> log.trace("Successfully converted Vert.x Future to Mono"));
    }
    
    /**
     * Converts a Vert.x future containing a list to a Flux. This explicitly named
     * form is retained for the example service API.
     *
     * @param future The Vert.x future containing the events
     * @param <T> The type of the event payload
     * @return A Flux that emits each bi-temporal event
     */
    public <T> Flux<BiTemporalEvent<T>> toFluxFromVertxFuture(Future<List<BiTemporalEvent<T>>> future) {
        return bridge(future)
            .flatMapMany(Flux::fromIterable)
            .doOnError(error -> log.error("Error converting Vert.x Future<List> to Flux", error))
            .doOnComplete(() -> log.trace("Successfully converted Vert.x Future<List> to Flux"));
    }
    
    /**
     * Example of composing Vert.x Future operations before converting to Mono.
     * 
     * <p>This demonstrates that Vert.x operators such as {@code .compose()},
     * {@code .map()}, {@code .transform()}, {@code .eventually()},
     * {@code .onSuccess()}, and {@code .onFailure()} before converting to Mono/Flux.
     * 
     * @param future The Vert.x future to compose and convert
     * @param <T> The type of the event payload
     * @return A Mono that completes with the transformed event
     */
    public <T> Mono<BiTemporalEvent<T>> toMonoWithVertxComposition(
            Future<BiTemporalEvent<T>> future) {
        
        // Example: Use Vert.x operators before converting to Mono
        Future<BiTemporalEvent<T>> composedFuture = future
            .onSuccess(event -> log.debug("Event appended: {}", event.getEventId()))
            .onFailure(error -> log.error("Failed to append event", error));
        
        return bridge(composedFuture);
    }

    private <T> Mono<T> bridge(Future<T> future) {
        return Mono.create(sink -> future
            .onSuccess(sink::success)
            .onFailure(sink::error));
    }
}
