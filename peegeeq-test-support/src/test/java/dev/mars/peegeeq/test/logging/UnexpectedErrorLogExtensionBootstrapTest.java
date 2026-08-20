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

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Executable bootstrap proof that the D11 extension changes JUnit execution results.
 *
 * <p>The fixture classes are selected only through {@link EngineTestKit}; Surefire
 * does not discover them as independent tests.</p>
 */
@Tag(TestCategories.CORE)
class UnexpectedErrorLogExtensionBootstrapTest {

    @Test
    void unexpectedErrorChangesSuccessfulTestToFailure() {
        EngineExecutionResults results = execute(UnexpectedFixture.class);

        results.testEvents().assertStatistics(statistics ->
                statistics.started(1).finished(1).succeeded(0).failed(1));
    }

    @Test
    void annotationExpectationAllowsExactError() {
        EngineExecutionResults results = execute(AnnotationExpectedFixture.class);

        results.testEvents().assertStatistics(statistics ->
                statistics.started(1).finished(1).succeeded(1).failed(0));
    }

    @Test
    void programmaticExpectationAllowsRuntimeRegistration() {
        EngineExecutionResults results = execute(ProgrammaticExpectedFixture.class);

        results.testEvents().assertStatistics(statistics ->
                statistics.started(1).finished(1).succeeded(1).failed(0));
    }

    @Test
    void throwableExpectationUsesCauseChain() {
        EngineExecutionResults results = execute(ThrowableExpectedFixture.class);

        results.testEvents().assertStatistics(statistics ->
                statistics.started(1).finished(1).succeeded(1).failed(0));
    }

    @Test
    void missingExpectedErrorFailsTest() {
        EngineExecutionResults results = execute(MissingExpectedFixture.class);

        results.testEvents().assertStatistics(statistics ->
                statistics.started(1).finished(1).succeeded(0).failed(1));
    }

    @Test
    void teardownErrorFailsTest() {
        EngineExecutionResults results = execute(TeardownErrorFixture.class);

        results.testEvents().assertStatistics(statistics ->
                statistics.started(1).finished(1).succeeded(0).failed(1));
    }

    @Test
    void afterAllErrorFailsContainer() {
        EngineExecutionResults results = execute(AfterAllErrorFixture.class);

        results.testEvents().assertStatistics(statistics ->
                statistics.started(1).finished(1).succeeded(1).failed(0));
        assertEquals(1, results.containerEvents().failed().count(),
                "The class container must fail for an unowned lifecycle ERROR");
    }

    @Test
    void parallelMethodScopesRouteUniqueExpectations() {
        EngineExecutionResults results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(ParallelExpectedFixture.class))
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "true")
                .configurationParameter("junit.jupiter.execution.parallel.mode.default", "concurrent")
                .configurationParameter("junit.jupiter.execution.parallel.config.strategy", "fixed")
                .configurationParameter("junit.jupiter.execution.parallel.config.fixed.parallelism", "2")
                .execute();

        results.testEvents().assertStatistics(statistics ->
                statistics.started(2).finished(2).succeeded(2).failed(0));
    }

    private static EngineExecutionResults execute(Class<?> fixtureClass) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(fixtureClass))
                .execute();
    }

    @ExtendWith(UnexpectedErrorLogExtension.class)
    static class UnexpectedFixture {
        private static final Logger LOGGER = LoggerFactory.getLogger(UnexpectedFixture.class);

        @Test
        void emitsUnexpectedError() {
            LOGGER.error("unexpected bootstrap error");
        }
    }

    @ExtendWith(UnexpectedErrorLogExtension.class)
    static class AnnotationExpectedFixture {
        private static final Logger LOGGER = LoggerFactory.getLogger(AnnotationExpectedFixture.class);

        @Test
        @ExpectedErrorLog(
                logger = "dev.mars.peegeeq.test.logging.UnexpectedErrorLogExtensionBootstrapTest$AnnotationExpectedFixture",
                message = "expected bootstrap error",
                throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
        void emitsExpectedError() {
            LOGGER.error("expected bootstrap error");
        }
    }

    @ExtendWith(UnexpectedErrorLogExtension.class)
    static class ProgrammaticExpectedFixture {
        private static final Logger LOGGER = LoggerFactory.getLogger(ProgrammaticExpectedFixture.class);

        @Test
        void registersThenEmitsExpectedError() {
            ErrorLogExpectations.expect(new ErrorLogExpectation(
                    ProgrammaticExpectedFixture.class.getName(),
                    ExpectedErrorLog.MessageMatch.PREFIX,
                    "runtime item failed: ",
                    ExpectedErrorLog.ThrowablePolicy.NONE,
                    ExpectedErrorLog.NoThrowable.class,
                    1,
                    1));

            LOGGER.error("runtime item failed: item-42");
        }
    }

    @ExtendWith(UnexpectedErrorLogExtension.class)
    static class ThrowableExpectedFixture {
        private static final Logger LOGGER = LoggerFactory.getLogger(ThrowableExpectedFixture.class);

        @Test
        @ExpectedErrorLog(
                logger = "dev.mars.peegeeq.test.logging.UnexpectedErrorLogExtensionBootstrapTest$ThrowableExpectedFixture",
                message = "expected throwable bootstrap error",
                throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
                throwableType = IOException.class)
        void emitsExpectedCauseChain() {
            LOGGER.error("expected throwable bootstrap error",
                    new IllegalStateException("outer", new IOException("disk")));
        }
    }

    @ExtendWith(UnexpectedErrorLogExtension.class)
    static class MissingExpectedFixture {

        @Test
        @ExpectedErrorLog(
                logger = "dev.mars.peegeeq.test.logging.UnexpectedErrorLogExtensionBootstrapTest$MissingExpectedFixture",
                message = "required but absent",
                throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
        void doesNotEmitDeclaredError() {
            assertEquals(4, 2 + 2);
        }
    }

    @ExtendWith(UnexpectedErrorLogExtension.class)
    static class TeardownErrorFixture {
        private static final Logger LOGGER = LoggerFactory.getLogger(TeardownErrorFixture.class);

        @Test
        void bodySucceeds() {
            assertEquals(4, 2 + 2);
        }

        @AfterEach
        void emitsTeardownError() {
            LOGGER.error("unexpected teardown error");
        }
    }

    @ExtendWith(UnexpectedErrorLogExtension.class)
    static class AfterAllErrorFixture {
        private static final Logger LOGGER = LoggerFactory.getLogger(AfterAllErrorFixture.class);

        @Test
        void bodySucceeds() {
            assertEquals(4, 2 + 2);
        }

        @AfterAll
        static void emitsAfterAllError() {
            LOGGER.error("unexpected after-all error");
        }
    }

    @ExtendWith(UnexpectedErrorLogExtension.class)
    static class ParallelExpectedFixture {
        private static final Logger LOGGER = LoggerFactory.getLogger(ParallelExpectedFixture.class);
        private static final String LOGGER_NAME =
                "dev.mars.peegeeq.test.logging.UnexpectedErrorLogExtensionBootstrapTest$ParallelExpectedFixture";

        @Test
        @ExpectedErrorLog(
                logger = LOGGER_NAME,
                message = "parallel error a",
                throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
        void emitsParallelErrorA() {
            LOGGER.error("parallel error a");
        }

        @Test
        @ExpectedErrorLog(
                logger = LOGGER_NAME,
                message = "parallel error b",
                throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
        void emitsParallelErrorB() {
            LOGGER.error("parallel error b");
        }
    }
}
