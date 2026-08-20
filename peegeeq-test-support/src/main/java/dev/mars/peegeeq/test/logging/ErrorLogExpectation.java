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

import java.util.Objects;

/** Immutable structured contract for ERROR-level log events. */
public record ErrorLogExpectation(
        String loggerName,
        ExpectedErrorLog.MessageMatch messageMatch,
        String message,
        ExpectedErrorLog.ThrowablePolicy throwablePolicy,
        Class<? extends Throwable> throwableType,
        int minOccurrences,
        int maxOccurrences) {

    public ErrorLogExpectation {
        loggerName = requireText(loggerName, "loggerName");
        message = requireText(message, "message");
        Objects.requireNonNull(messageMatch, "messageMatch");
        Objects.requireNonNull(throwablePolicy, "throwablePolicy");
        Objects.requireNonNull(throwableType, "throwableType");

        if (loggerName.indexOf('*') >= 0 || loggerName.indexOf('?') >= 0) {
            throw new IllegalArgumentException("loggerName must be exact and contain no wildcards");
        }
        if ("ROOT".equals(loggerName)) {
            throw new IllegalArgumentException("loggerName must not be the root logger");
        }
        if (minOccurrences < 1) {
            throw new IllegalArgumentException("minOccurrences must be at least 1");
        }
        if (maxOccurrences < minOccurrences) {
            throw new IllegalArgumentException(
                    "maxOccurrences must be greater than or equal to minOccurrences");
        }

        boolean sentinel = throwableType == ExpectedErrorLog.NoThrowable.class;
        if (throwablePolicy == ExpectedErrorLog.ThrowablePolicy.NONE && !sentinel) {
            throw new IllegalArgumentException(
                    "Throwable policy NONE requires ExpectedErrorLog.NoThrowable");
        }
        if (throwablePolicy == ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS && sentinel) {
            throw new IllegalArgumentException(
                    "CAUSE_CHAIN_CONTAINS requires an explicit Throwable type");
        }
    }

    static ErrorLogExpectation from(ExpectedErrorLog annotation) {
        return new ErrorLogExpectation(
                annotation.logger(),
                annotation.messageMatch(),
                annotation.message(),
                annotation.throwable(),
                annotation.throwableType(),
                annotation.minOccurrences(),
                annotation.maxOccurrences());
    }

    boolean matches(ErrorLogLedger.ErrorEvent event) {
        return loggerName.equals(event.loggerName())
                && matchesMessage(event.message())
                && matchesThrowable(event);
    }

    String describe() {
        String messageDescription = messageMatch == ExpectedErrorLog.MessageMatch.EXACT
                ? "message='" + message + "'"
                : "messagePrefix='" + message + "'";
        String throwableDescription = throwablePolicy == ExpectedErrorLog.ThrowablePolicy.NONE
                ? "throwable=NONE"
                : "throwableCause=" + throwableType.getName();
        return "logger='" + loggerName + "', " + messageDescription + ", "
                + throwableDescription + ", occurrences=" + minOccurrences + ".." + maxOccurrences;
    }

    private boolean matchesMessage(String actualMessage) {
        return switch (messageMatch) {
            case EXACT -> message.equals(actualMessage);
            case PREFIX -> actualMessage.startsWith(message);
        };
    }

    private boolean matchesThrowable(ErrorLogLedger.ErrorEvent event) {
        if (throwablePolicy == ExpectedErrorLog.ThrowablePolicy.NONE) {
            return event.throwableClassNames().isEmpty();
        }

        Throwable cause = event.throwable();
        while (cause != null) {
            if (throwableType.isAssignableFrom(cause.getClass())) {
                return true;
            }
            cause = cause.getCause();
        }
        return event.throwableClassNames().contains(throwableType.getName());
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
