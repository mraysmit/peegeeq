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

import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Awaits the asynchronous {@code @AfterEach} of a failed test (finding 13).
 *
 * <p>vertx-junit5 5.0.x joins the {@link VertxTestContext} of an {@code @AfterEach} method only
 * when the test passed. After a failed test it returns at once and then closes Vert.x under the
 * teardown, so pools, containers and child processes the teardown closes can stay open and fail
 * the next test. This interceptor is registered by service loading, so it is outside the
 * {@link VertxExtension} interceptor: when that interceptor skips the join, this one awaits the
 * teardown contexts with the same timeout rules and reports a failed or unfinished teardown.</p>
 *
 * <p>A {@code @BeforeEach} or test method that throws skips the join too, and its contexts stay in
 * the store. They are removed when the method throws, so that only the teardown's own contexts
 * are awaited after the teardown. A context that has failed carries the asynchronous failure,
 * which is often the cause of the thrown one; its cause is added to the thrown failure as a
 * suppressed exception. vertx-junit5 5.1 keeps one context per method in the store instead of a
 * list, and an {@code @AfterEach} parameter then receives the context left by the test method;
 * the removal covers both forms.</p>
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

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation,
                                         ReflectiveInvocationContext<Method> invocationContext,
                                         ExtensionContext extensionContext) throws Throwable {
        invocation.proceed();
        if (extensionContext.getExecutionException().isEmpty()) {
            // The test passed: VertxExtension has joined the teardown contexts.
            return;
        }
        for (VertxTestContext teardownContext : removeContexts(extensionContext)) {
            await(teardownContext, extensionContext);
        }
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

    /** Removes the contexts vertx-junit5 did not join: a list in 5.0.x, a single context in 5.1.x. */
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

    private static void await(VertxTestContext teardownContext, ExtensionContext extensionContext) throws Throwable {
        Timeout timeout = timeoutOf(extensionContext);
        long duration = timeout != null ? timeout.value() : VertxExtension.DEFAULT_TIMEOUT_DURATION;
        TimeUnit unit = timeout != null ? timeout.timeUnit() : VertxExtension.DEFAULT_TIMEOUT_UNIT;
        if (!teardownContext.awaitCompletion(duration, unit)) {
            throw new TimeoutException("The teardown of a failed test did not complete within "
                    + duration + " " + unit + "; resources it closes may still be open");
        }
        if (teardownContext.failed()) {
            throw teardownContext.causeOfFailure();
        }
    }

    /** The same lookup as VertxExtension: the test method, then the class and its enclosing classes. */
    private static Timeout timeoutOf(ExtensionContext extensionContext) {
        Method testMethod = extensionContext.getTestMethod().orElse(null);
        if (testMethod != null && testMethod.isAnnotationPresent(Timeout.class)) {
            return testMethod.getAnnotation(Timeout.class);
        }
        for (Class<?> testClass = extensionContext.getTestClass().orElse(null);
             testClass != null;
             testClass = testClass.isAnnotationPresent(Nested.class) ? testClass.getEnclosingClass() : null) {
            if (testClass.isAnnotationPresent(Timeout.class)) {
                return testClass.getAnnotation(Timeout.class);
            }
        }
        return null;
    }
}
