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

package dev.mars.peegeeq.client.sse;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * A ReadStream implementation for Server-Sent Events (SSE).
 * Parses SSE format and emits parsed objects.
 *
 * @param <T> the type of objects emitted by this stream
 */
public class SSEReadStream<T> implements ReadStream<T> {
    
    private static final Logger logger = LoggerFactory.getLogger(SSEReadStream.class);
    
    private final HttpClientRequest request;
    private final Function<JsonObject, T> parser;
    
    private Handler<T> dataHandler;
    private Handler<Throwable> exceptionHandler;
    private Handler<Void> endHandler;
    private HttpClientResponse response;
    private StringBuilder buffer = new StringBuilder();
    private boolean paused = false;
    private boolean closed = false;
    
    public SSEReadStream(HttpClientRequest request, Function<JsonObject, T> parser) {
        this.request = request;
        this.parser = parser;
    }
    
    /**
     * Starts the SSE connection.
     */
    public void start() {
        request.send()
            .onSuccess(resp -> {
                this.response = resp;
                logger.debug("SSE connection established, status: {}", resp.statusCode());
                
                if (resp.statusCode() != 200) {
                    if (exceptionHandler != null) {
                        exceptionHandler.handle(new RuntimeException("SSE connection failed: " + resp.statusCode()));
                    }
                    return;
                }
                
                resp.handler(chunk -> {
                    if (closed) return;
                    buffer.append(chunk.toString());
                    processBuffer();
                });
                
                resp.endHandler(v -> {
                    if (endHandler != null && !closed) {
                        endHandler.handle(null);
                    }
                });
                
                resp.exceptionHandler(err -> {
                    if (exceptionHandler != null && !closed) {
                        exceptionHandler.handle(err);
                    }
                });
            })
            .onFailure(err -> {
                if (exceptionHandler != null) {
                    exceptionHandler.handle(err);
                }
            });
    }
    
    private void processBuffer() {
        if (paused) return;
        
        String content = buffer.toString();
        int eventEnd;
        
        // SSE events are separated by double newlines
        while ((eventEnd = content.indexOf("\n\n")) != -1) {
            String event = content.substring(0, eventEnd);
            content = content.substring(eventEnd + 2);
            buffer = new StringBuilder(content);
            
            parseAndEmitEvent(event);
        }
    }
    
    private void parseAndEmitEvent(String event) {
        if (dataHandler == null) return;
        
        String data = null;
        String eventType = null;
        String eventId = null;
        
        for (String line : event.split("\n")) {
            if (line.startsWith("data:")) {
                data = line.substring(5).trim();
            } else if (line.startsWith("event:")) {
                eventType = line.substring(6).trim();
            } else if (line.startsWith("id:")) {
                eventId = line.substring(3).trim();
            }
        }
        
        if (data != null && !data.isEmpty()) {
            try {
                JsonObject json = new JsonObject(data);
                T parsed = parser.apply(json);
                if (parsed != null) {
                    dataHandler.handle(parsed);
                }
            } catch (Exception e) {
                logger.warn("Failed to parse SSE event data: {}", data, e);
                if (exceptionHandler != null) {
                    exceptionHandler.handle(e);
                }
            }
        }
    }
    
    @Override
    public ReadStream<T> exceptionHandler(Handler<Throwable> handler) {
        this.exceptionHandler = handler;
        return this;
    }
    
    @Override
    public ReadStream<T> handler(Handler<T> handler) {
        this.dataHandler = handler;
        return this;
    }
    
    @Override
    public ReadStream<T> pause() {
        this.paused = true;
        if (response != null) {
            response.pause();
        }
        return this;
    }
    
    @Override
    public ReadStream<T> resume() {
        this.paused = false;
        if (response != null) {
            response.resume();
        }
        processBuffer();
        return this;
    }
    
    @Override
    public ReadStream<T> fetch(long amount) {
        return resume();
    }
    
    @Override
    public ReadStream<T> endHandler(Handler<Void> handler) {
        this.endHandler = handler;
        return this;
    }
    
    /**
     * Closes the SSE connection.
     */
    public void close() {
        closed = true;
        if (response != null) {
            response.request().reset();
        }
    }
}

