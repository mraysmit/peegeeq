/*
 * Copyright (c) 2025 Cityline Ltd
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of Cityline Ltd.
 * You shall not disclose such confidential information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Cityline Ltd.
 */

package dev.mars.peegeeq.bitemporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Query;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.CORE)
class ReactiveNotificationHandlerFailurePathTest {

    @Test
    void stopShouldFailWhenUnlistenFailsAndStillCleanupState() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
                    vertx,
                    new PgConnectOptions().setHost("localhost").setPort(5432).setDatabase("test").setUser("u").setPassword("p"),
                    new ObjectMapper(),
                    String.class,
                    eventId -> Future.succeededFuture(null));

            PgConnection failingConnection = createFailingConnection();
            setField(handler, "active", true);
            setField(handler, "listenConnection", failingConnection);

            @SuppressWarnings("unchecked")
            Set<String> listeningChannels = (Set<String>) getField(handler, "listeningChannels");
            listeningChannels.add("test_channel");

            CompletionException thrown = assertThrows(CompletionException.class,
                    () -> handler.stop().toCompletionStage().toCompletableFuture().join());
            assertNotNull(thrown.getCause());
            assertTrue(thrown.getCause().getMessage().contains("forced unlisten failure"));

            assertFalse((boolean) getField(handler, "active"));
            assertNull(getField(handler, "listenConnection"));
            assertTrue(listeningChannels.isEmpty(), "Listening channels should be cleaned up on failed stop");
        } finally {
            vertx.close();
        }
    }

    @Test
    void subscribeShouldRejectNullHandlerBeforeActiveCheck() {
        Vertx vertx = Vertx.vertx();
        try {
            ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
                    vertx,
                    new PgConnectOptions().setHost("localhost").setPort(5432).setDatabase("test").setUser("u").setPassword("p"),
                    new ObjectMapper(),
                    String.class,
                    eventId -> Future.succeededFuture(null));

            Throwable thrown = assertThrows(CompletionException.class,
                    () -> handler.subscribe("order.created", null, (MessageHandler<BiTemporalEvent<String>>) null)
                            .toCompletionStage()
                            .toCompletableFuture()
                            .join());
            assertTrue(thrown.getCause() instanceof IllegalArgumentException);
            assertTrue(thrown.getCause().getMessage().contains("Handler cannot be null"));
        } finally {
            vertx.close();
        }
    }

    private PgConnection createFailingConnection() {
        Query<?> failingQuery = (Query<?>) Proxy.newProxyInstance(
                Query.class.getClassLoader(),
                new Class<?>[] { Query.class },
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName())) {
                        return Future.failedFuture(new RuntimeException("forced unlisten failure"));
                    }
                    return defaultValue(method.getReturnType(), proxy);
                });

        return (PgConnection) Proxy.newProxyInstance(
                PgConnection.class.getClassLoader(),
                new Class<?>[] { PgConnection.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("query".equals(name)) {
                        return failingQuery;
                    }
                    if ("close".equals(name)) {
                        return Future.succeededFuture();
                    }
                    if ("closeHandler".equals(name)) {
                        return proxy;
                    }
                    return defaultValue(method.getReturnType(), proxy);
                });
    }

    private Object defaultValue(Class<?> returnType, Object proxy) {
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType.isInstance(proxy)) {
            return proxy;
        }
        return null;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
