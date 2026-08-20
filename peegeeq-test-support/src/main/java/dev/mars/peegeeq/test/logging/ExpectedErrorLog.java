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

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one ERROR-level log event contract for a test method.
 *
 * <p>The logger, message, Throwable policy, and finite occurrence bounds are
 * deliberately structural. Free-form marker messages and broad allowlists do
 * not satisfy this contract.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(ExpectedErrorLog.List.class)
public @interface ExpectedErrorLog {

    String logger();

    String message();

    MessageMatch messageMatch() default MessageMatch.EXACT;

    ThrowablePolicy throwable();

    Class<? extends Throwable> throwableType() default NoThrowable.class;

    int minOccurrences() default 1;

    int maxOccurrences() default 1;

    enum MessageMatch {
        EXACT,
        PREFIX
    }

    enum ThrowablePolicy {
        NONE,
        CAUSE_CHAIN_CONTAINS
    }

    /** Sentinel used when the expected event must not carry a Throwable. */
    final class NoThrowable extends Throwable {
        private NoThrowable() {
        }
    }

    /** Container annotation for repeatable ERROR expectations. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {
        ExpectedErrorLog[] value();
    }
}
