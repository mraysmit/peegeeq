package dev.mars.peegeeq.test.logging;

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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Programmatic registration API for runtime ERROR expectations. */
public final class ErrorLogExpectations {

    private static final ConcurrentMap<Thread, Deque<RegistrationContext>> CURRENT_BY_THREAD =
            new ConcurrentHashMap<>();

    private ErrorLogExpectations() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Registers an expectation for the current JUnit method scope.
     *
     * <p>Call this from the JUnit test thread before triggering asynchronous
     * work. Annotation-based expectations should be preferred for static data.</p>
     */
    public static void expect(ErrorLogExpectation expectation) {
        RegistrationContext context = currentContexts().peek();
        if (context == null || context.owner == null) {
            throw new IllegalStateException(
                    "No active UnexpectedErrorLogExtension method scope on this thread");
        }
        context.ledger.addExpectation(context.owner, Objects.requireNonNull(expectation));
    }

    static void bind(ErrorLogLedger ledger, String owner) {
        Deque<RegistrationContext> contexts = currentContexts();
        RegistrationContext current = contexts.peek();
        if (current != null && current.owner != null) {
            throw new IllegalStateException("An ERROR log expectation scope is already bound");
        }
        contexts.push(new RegistrationContext(ledger, owner));
    }

    static void bindRun(ErrorLogLedger ledger) {
        currentContexts().push(new RegistrationContext(ledger, null));
    }

    static void clear() {
        Deque<RegistrationContext> contexts = currentContexts();
        RegistrationContext current = contexts.peek();
        if (current == null || current.owner == null) {
            throw new IllegalStateException("No ERROR log expectation scope is bound");
        }
        contexts.pop();
        removeThreadStateWhenEmpty(contexts);
    }

    static void unbindRun(ErrorLogLedger ledger) {
        Deque<RegistrationContext> contexts = currentContexts();
        RegistrationContext current = contexts.peek();
        if (current != null && current.owner == null && current.ledger == ledger) {
            contexts.pop();
        } else {
            contexts.removeFirstOccurrence(new RegistrationContext(ledger, null));
        }
        removeThreadStateWhenEmpty(contexts);
    }

    static CaptureTarget currentCaptureTarget() {
        Deque<RegistrationContext> contexts = currentContexts();
        RegistrationContext context = contexts.peek();
        if (context == null) {
            removeThreadStateWhenEmpty(contexts);
            return null;
        }
        return new CaptureTarget(context.ledger, context.owner);
    }

    private static Deque<RegistrationContext> currentContexts() {
        return CURRENT_BY_THREAD.computeIfAbsent(
                Thread.currentThread(), ignored -> new ArrayDeque<>());
    }

    private static void removeThreadStateWhenEmpty(Deque<RegistrationContext> contexts) {
        if (contexts.isEmpty()) {
            CURRENT_BY_THREAD.remove(Thread.currentThread(), contexts);
        }
    }

    record CaptureTarget(ErrorLogLedger ledger, String owner) {
    }

    private record RegistrationContext(ErrorLogLedger ledger, String owner) {
    }
}
