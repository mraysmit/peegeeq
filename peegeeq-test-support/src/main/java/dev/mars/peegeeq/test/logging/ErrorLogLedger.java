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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Thread-safe ledger that assigns ERROR events to active test scopes. */
final class ErrorLogLedger {

    private final Object lock = new Object();
    private final Map<String, Scope> activeScopes = new TreeMap<>();
    private final Map<String, List<String>> activeContainers = new TreeMap<>();
    private final List<String> unownedViolations = new ArrayList<>();

    void openContainer(String owner) {
        Objects.requireNonNull(owner, "owner");
        synchronized (lock) {
            if (activeContainers.putIfAbsent(owner, new ArrayList<>()) != null) {
                throw new IllegalStateException("ERROR log container is already active: " + owner);
            }
        }
    }

    ScopeResult closeContainer(String owner) {
        synchronized (lock) {
            List<String> violations = activeContainers.remove(owner);
            if (violations == null) {
                return new ScopeResult(owner,
                        List.of("ERROR log container was not active when it was closed: " + owner));
            }
            return new ScopeResult(owner, List.copyOf(violations));
        }
    }

    void openScope(String owner, List<ErrorLogExpectation> expectations) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(expectations, "expectations");
        synchronized (lock) {
            if (activeScopes.containsKey(owner)) {
                throw new IllegalStateException("ERROR log scope is already active: " + owner);
            }
            activeScopes.put(owner, new Scope(owner, expectations));
        }
    }

    void addExpectation(String owner, ErrorLogExpectation expectation) {
        Objects.requireNonNull(expectation, "expectation");
        synchronized (lock) {
            requiredScope(owner).expectations.add(new ExpectationCounter(expectation));
        }
    }

    void capture(ErrorEvent event, String ownerHint) {
        Objects.requireNonNull(event, "event");
        synchronized (lock) {
            List<Match> matches = findMatches(event);
            if (matches.size() == 1) {
                matches.getFirst().counter.count++;
                return;
            }
            if (matches.size() > 1) {
                String violation = "Ambiguous expected ERROR matched " + matches.size()
                        + " active expectations: " + event.describe();
                matches.stream()
                        .map(Match::scope)
                        .distinct()
                        .forEach(scope -> scope.violations.add(violation));
                return;
            }

            assignUnexpected(event, ownerHint);
        }
    }

    ScopeResult closeScope(String owner) {
        synchronized (lock) {
            Scope scope = activeScopes.remove(owner);
            if (scope == null) {
                return new ScopeResult(owner,
                        List.of("ERROR log scope was not active when it was closed: " + owner));
            }

            List<String> violations = new ArrayList<>(scope.violations);
            for (ExpectationCounter counter : scope.expectations) {
                if (counter.count < counter.expectation.minOccurrences()
                        || counter.count > counter.expectation.maxOccurrences()) {
                    violations.add("Expected ERROR occurrence mismatch: "
                            + counter.expectation.describe() + ", observed=" + counter.count);
                }
            }
            return new ScopeResult(owner, List.copyOf(violations));
        }
    }

    List<String> finishRun() {
        synchronized (lock) {
            List<String> violations = new ArrayList<>(unownedViolations);
            for (Scope scope : activeScopes.values()) {
                violations.add("ERROR log scope remained active at run close: " + scope.owner);
                scope.violations.forEach(violation -> violations.add(
                        "Active scope violation for " + scope.owner + ": " + violation));
            }
            for (Map.Entry<String, List<String>> container : activeContainers.entrySet()) {
                violations.add("ERROR log container remained active at run close: "
                        + container.getKey());
                container.getValue().forEach(violation -> violations.add(
                        "Active container violation for " + container.getKey() + ": " + violation));
            }
            activeScopes.clear();
            activeContainers.clear();
            unownedViolations.clear();
            return List.copyOf(violations);
        }
    }

    private List<Match> findMatches(ErrorEvent event) {
        List<Match> matches = new ArrayList<>();
        for (Scope scope : activeScopes.values()) {
            for (ExpectationCounter counter : scope.expectations) {
                if (counter.expectation.matches(event)) {
                    matches.add(new Match(scope, counter));
                }
            }
        }
        return matches;
    }

    private void assignUnexpected(ErrorEvent event, String ownerHint) {
        String violation = "Unexpected ERROR: " + event.describe();
        Scope hintedScope = ownerHint == null ? null : activeScopes.get(ownerHint);
        if (hintedScope != null) {
            hintedScope.violations.add(violation);
            return;
        }
        if (activeScopes.size() == 1) {
            activeScopes.values().iterator().next().violations.add(violation);
            return;
        }
        if (activeScopes.isEmpty()) {
            assignLifecycleUnexpected(violation);
            return;
        }

        String ambiguous = "Ambiguous owner for " + violation + "; active owners="
                + activeScopes.keySet();
        activeScopes.values().forEach(scope -> scope.violations.add(ambiguous));
    }

    private void assignLifecycleUnexpected(String violation) {
        if (activeContainers.size() == 1) {
            activeContainers.values().iterator().next().add(violation);
            return;
        }
        if (activeContainers.isEmpty()) {
            unownedViolations.add("Unowned " + violation);
            return;
        }

        String ambiguous = "Ambiguous container owner for " + violation + "; active owners="
                + activeContainers.keySet();
        activeContainers.values().forEach(violations -> violations.add(ambiguous));
    }

    private Scope requiredScope(String owner) {
        Scope scope = activeScopes.get(owner);
        if (scope == null) {
            throw new IllegalStateException("No active ERROR log scope for owner: " + owner);
        }
        return scope;
    }

    record ScopeResult(String owner, List<String> violations) {
        boolean isSuccessful() {
            return violations.isEmpty();
        }

        String failureMessage() {
            return "ERROR log contract failed for " + owner + ":\n - "
                    + String.join("\n - ", violations);
        }
    }

    record ErrorEvent(
            String loggerName,
            String message,
            Throwable throwable,
            List<String> throwableClassNames) {

        ErrorEvent {
            Objects.requireNonNull(loggerName, "loggerName");
            Objects.requireNonNull(message, "message");
            throwableClassNames = List.copyOf(throwableClassNames);
        }

        static ErrorEvent from(ILoggingEvent event) {
            IThrowableProxy proxy = event.getThrowableProxy();
            Throwable actualThrowable = proxy instanceof ThrowableProxy throwableProxy
                    ? throwableProxy.getThrowable()
                    : null;
            List<String> classNames = new ArrayList<>();
            while (proxy != null) {
                classNames.add(proxy.getClassName());
                proxy = proxy.getCause();
            }
            return new ErrorEvent(
                    event.getLoggerName(),
                    event.getFormattedMessage(),
                    actualThrowable,
                    classNames);
        }

        static ErrorEvent withoutThrowable(String loggerName, String message) {
            return new ErrorEvent(loggerName, message, null, List.of());
        }

        static ErrorEvent withThrowable(String loggerName, String message, Throwable throwable) {
            List<String> classNames = new ArrayList<>();
            Throwable cause = throwable;
            while (cause != null) {
                classNames.add(cause.getClass().getName());
                cause = cause.getCause();
            }
            return new ErrorEvent(loggerName, message, throwable, classNames);
        }

        String describe() {
            String throwableDescription = throwableClassNames.isEmpty()
                    ? "none"
                    : String.join(" -> ", throwableClassNames);
            return "logger='" + loggerName + "', message='" + message
                    + "', throwable=" + throwableDescription;
        }
    }

    private static final class Scope {
        private final String owner;
        private final List<ExpectationCounter> expectations;
        private final List<String> violations = new ArrayList<>();

        private Scope(String owner, List<ErrorLogExpectation> expectations) {
            this.owner = owner;
            this.expectations = expectations.stream().map(ExpectationCounter::new)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
    }

    private static final class ExpectationCounter {
        private final ErrorLogExpectation expectation;
        private int count;

        private ExpectationCounter(ErrorLogExpectation expectation) {
            this.expectation = expectation;
        }
    }

    private record Match(Scope scope, ExpectationCounter counter) {
    }
}
