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

import io.vertx.core.Future;
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
    
    private final Future<HttpClientRequest> requestFuture;
    private final Function<JsonObject, T> parser;
    private final Runnable closeHook;
    
    private Handler<T> dataHandler;
    private Handler<Throwable> exceptionHandler;
    private Handler<Void> endHandler;
    private HttpClientRequest request;
    private HttpClientResponse response;
    private StringBuilder buffer = new StringBuilder();
    private boolean paused = false;
    private boolean closed = false;
    
    public SSEReadStream(HttpClientRequest request, Function<JsonObject, T> parser) {
        this(Future.succeededFuture(request), parser, null);
    }

    public SSEReadStream(Future<HttpClientRequest> requestFuture,
                         Function<JsonObject, T> parser,
                         Runnable closeHook) {
        this.requestFuture = requestFuture;
        this.parser = parser;
        this.closeHook = closeHook;
    }
    
    /**
     * Starts the SSE connection.
     */
    public void start() {
        requestFuture
            .onSuccess(req -> {
                this.request = req;
                req.send()
                    .onSuccess(resp -> {
                        this.response = resp;
                        logger.debug("SSE connection established, status: {}", resp.statusCode());

                        if (resp.statusCode() != 200) {
                            if (exceptionHandler != null) {
                                exceptionHandler.handle(new RuntimeException("SSE connection failed: " + resp.statusCode()));
                            }
                            close();
                            return;
                        }

                        resp.handler(chunk -> {
                            if (closed) {
                                return;
                            }
                            buffer.append(chunk.toString());
                            processBuffer();
                        });

                        resp.endHandler(v -> {
                            if (endHandler != null && !closed) {
                                endHandler.handle(null);
                            }
                            close();
                        });

                        resp.exceptionHandler(err -> {
                            if (exceptionHandler != null && !closed) {
                                exceptionHandler.handle(err);
                            }
                            close();
                        });
                    })
                    .onFailure(err -> {
                        if (exceptionHandler != null && !closed) {
                            exceptionHandler.handle(err);
                        }
                        close();
                    });
            })
            .onFailure(err -> {
                if (exceptionHandler != null && !closed) {
                    exceptionHandler.handle(err);
                }
                close();
            });
    }
    
    private void processBuffer() {
        if (paused) return;
        
        String content = buffer.toString();
        int eventEnd;
        int delimiterLength;

        while ((eventEnd = findEventDelimiter(content)) != -1) {
            delimiterLength = eventDelimiterLength(content, eventEnd);
            String event = content.substring(0, eventEnd);
            content = content.substring(eventEnd + delimiterLength);
            buffer = new StringBuilder(content);

            parseAndEmitEvent(event);
        }
    }
    
    private void parseAndEmitEvent(String event) {
        if (dataHandler == null) return;
        
        StringBuilder dataBuilder = new StringBuilder();
        String eventType = null;
        String eventId = null;

        for (String line : event.split("\\r?\\n")) {
            if (line.isEmpty() || line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("data:")) {
                if (!dataBuilder.isEmpty()) {
                    dataBuilder.append('\n');
                }
                dataBuilder.append(parseFieldValue(line, 5));
            } else if (line.startsWith("event:")) {
                eventType = parseFieldValue(line, 6);
            } else if (line.startsWith("id:")) {
                eventId = parseFieldValue(line, 3);
            }
        }

        String data = dataBuilder.toString();
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
        if (closed) {
            return;
        }
        closed = true;

        if (request != null) {
            request.reset();
        } else if (response != null) {
            response.request().reset();
        }

        if (closeHook != null) {
            closeHook.run();
        }
    }

    private int findEventDelimiter(String content) {
        int lf = content.indexOf("\n\n");
        int crlf = content.indexOf("\r\n\r\n");
        int cr = content.indexOf("\r\r");

        int minIndex = -1;
        if (lf != -1) {
            minIndex = lf;
        }
        if (crlf != -1 && (minIndex == -1 || crlf < minIndex)) {
            minIndex = crlf;
        }
        if (cr != -1 && (minIndex == -1 || cr < minIndex)) {
            minIndex = cr;
        }
        return minIndex;
    }

    private int eventDelimiterLength(String content, int index) {
        if (content.startsWith("\r\n\r\n", index)) {
            return 4;
        }
        return 2;
    }

    private String parseFieldValue(String line, int prefixLength) {
        if (line.length() <= prefixLength) {
            return "";
        }
        String value = line.substring(prefixLength);
        return value.startsWith(" ") ? value.substring(1) : value;
    }
}

