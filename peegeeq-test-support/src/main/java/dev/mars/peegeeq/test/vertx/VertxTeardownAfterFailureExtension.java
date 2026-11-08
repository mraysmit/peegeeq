package dev.mars.peegeeq.test.vertx;

/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
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

import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Lets the asynchronous {@code @AfterEach} of a failed test run to completion (finding 13).
 *
 * <p>A {@code @BeforeEach} or test method that throws leaves its {@link VertxTestContext} in the
 * vertx-junit5 store. vertx-junit5 5.1 then hands that context to the {@code @AfterEach}
 * parameter. When the context has already failed, the teardown is cancelled before it finishes
 * and Vert.x closes under it, so pools, containers and child processes it closes can stay open
 * and fail the next test. This interceptor is registered by service loading and removes the
 * leftover contexts when the method throws, so the {@code @AfterEach} receives a fresh context
 * that vertx-junit5 awaits. A context that has failed carries the asynchronous failure, which is
 * often the cause of the thrown one; its cause is added to the thrown failure as a suppressed
 * exception.</p>
 *
 * <p>On vertx-junit5 5.0.x this class also awaited the teardown contexts after a failed test,
 * because 5.0.x did not join them. 5.1 joins them, and that wait was deleted with the upgrade.</p>
 *
 * <p>The contexts are read from the store entry vertx-junit5 uses ({@code GLOBAL},
 * {@code "VertxTestContext"}). {@code VertxTeardownAfterFailureGuardTest} pins the behaviour, so
 * a version that changes the entry fails that guard.</p>
 */
public final class VertxTeardownAfterFailureExtension implements InvocationInterceptor {

    static final String VERTX_TEST_CONTEXT_KEY = "VertxTestContext";

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation,
                                          ReflectiveInvocationContext<Method> invocationContext,
                                          ExtensionContext extensionContext) throws Throwable {
        proceedAndRemoveContextsOnFailure(invocation, extensionContext);
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        proceedAndRemoveContextsOnFailure(invocation, extensionContext);
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
                                            ReflectiveInvocationContext<Method> invocationContext,
                                            ExtensionContext extensionContext) throws Throwable {
        proceedAndRemoveContextsOnFailure(invocation, extensionContext);
    }

    private static void proceedAndRemoveContextsOnFailure(Invocation<Void> invocation,
                                                          ExtensionContext extensionContext) throws Throwable {
        try {
            invocation.proceed();
        } catch (Throwable failure) {
            for (VertxTestContext leftover : removeContexts(extensionContext)) {
                Throwable cause = leftover.causeOfFailure();
                if (cause != null && cause != failure) {
                    failure.addSuppressed(cause);
                }
            }
            throw failure;
        }
    }

    /** Removes the contexts vertx-junit5 left in the store: one context in 5.1, a list in 5.0. */
    private static List<VertxTestContext> removeContexts(ExtensionContext extensionContext) {
        Object entry = extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).remove(VERTX_TEST_CONTEXT_KEY);
        List<VertxTestContext> contexts = new ArrayList<>();
        if (entry instanceof VertxTestContext context) {
            contexts.add(context);
        } else if (entry instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof VertxTestContext context) {
                    contexts.add(context);
                }
            }
        }
        return contexts;
    }
}
