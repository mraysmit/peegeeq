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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Executable D11-B contracts for packaged, process-wide JUnit integration. */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
class UnexpectedErrorLogPackagedIntegrationTest {

    private static final String AUTO_DETECTION =
            "junit.jupiter.extensions.autodetection.enabled";

    @Test
    void serviceLoaderAutoDetectionFailsUnexpectedError() {
        EngineExecutionResults results = executeAuto(AutoUnexpectedFixture.class);

        results.testEvents().assertStatistics(statistics ->
                statistics.started(1).finished(1).succeeded(0).failed(1));
    }

    @Test
    void serviceLoaderAutoDetectionHonorsStructuredExpectation() {
        EngineExecutionResults results = executeAuto(AutoExpectedFixture.class);

        results.testEvents().assertStatistics(statistics ->
                statistics.started(1).finished(1).succeeded(1).failed(0));
    }

    @Test
    void explicitAndAutomaticRegistrationPreserveTheOuterProcessAppender() {
        EngineExecutionResults results = executeAuto(ExplicitAndAutomaticFixture.class);

        results.testEvents().assertStatistics(statistics ->
                statistics.started(1).finished(1).succeeded(1).failed(0));
        assertEquals(1, UnexpectedErrorLogCaptureCoordinator.installedAppenderCount(),
                "Closing the nested engine must preserve the outer Maven run appender");
    }

    @Test
    void parallelClassesDoNotLoseExpectedEvents() {
        EngineExecutionResults results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(ParallelClassAFixture.class),
                        selectClass(ParallelClassBFixture.class))
                .configurationParameter(AUTO_DETECTION, "true")
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "true")
                .configurationParameter("junit.jupiter.execution.parallel.mode.default", "concurrent")
                .configurationParameter("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
                .configurationParameter("junit.jupiter.execution.parallel.config.strategy", "fixed")
                .configurationParameter("junit.jupiter.execution.parallel.config.fixed.parallelism", "2")
                .execute();

        results.testEvents().assertStatistics(statistics ->
                statistics.started(2).finished(2).succeeded(2).failed(0));
    }

    @Test
    void expectationDoesNotLeakIntoTheNextMethod() {
        EngineExecutionResults results = executeAuto(MethodIsolationFixture.class);

        results.testEvents().assertStatistics(statistics ->
                statistics.started(2).finished(2).succeeded(1).failed(1));
        assertTrue(failureMessage(results).contains("Unexpected ERROR"));
    }

    @Test
    void diagnosticsAreStableAndNameOwnerLoggerAndEvent() {
        String first = failureMessage(executeAuto(AutoUnexpectedFixture.class));
        String second = failureMessage(executeAuto(AutoUnexpectedFixture.class));

        assertEquals(first, second);
        assertTrue(first.contains("[emitsUnexpectedError()]"));
        assertTrue(first.contains(AutoUnexpectedFixture.class.getName()));
        assertTrue(first.contains("auto-detected unexpected error"));
    }

    @Test
    void testOwnedAppenderStillReceivesExpectedEvent() {
        EngineExecutionResults results = executeAuto(TestOwnedAppenderFixture.class);

        results.testEvents().assertStatistics(statistics ->
                statistics.started(1).finished(1).succeeded(1).failed(0));
    }

    private static EngineExecutionResults executeAuto(Class<?> fixtureClass) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(fixtureClass))
                .configurationParameter(AUTO_DETECTION, "true")
                .execute();
    }

    private static String failureMessage(EngineExecutionResults results) {
        TestExecutionResult executionResult = results.testEvents()
                .failed()
                .list()
                .getFirst()
                .getRequiredPayload(TestExecutionResult.class);
        return executionResult.getThrowable().orElseThrow().getMessage();
    }

    static class AutoUnexpectedFixture {
        private static final org.slf4j.Logger LOGGER =
                LoggerFactory.getLogger(AutoUnexpectedFixture.class);

        @Test
        void emitsUnexpectedError() {
            LOGGER.error("auto-detected unexpected error");
        }
    }

    static class AutoExpectedFixture {
        private static final org.slf4j.Logger LOGGER =
                LoggerFactory.getLogger(AutoExpectedFixture.class);

        @Test
        @ExpectedErrorLog(
                logger = "dev.mars.peegeeq.test.logging.UnexpectedErrorLogPackagedIntegrationTest$AutoExpectedFixture",
                message = "auto-detected expected error",
                throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
        void emitsExpectedError() {
            LOGGER.error("auto-detected expected error");
        }
    }

    @ExtendWith(UnexpectedErrorLogExtension.class)
    static class ExplicitAndAutomaticFixture {

        @Test
        void seesOneInstalledProcessAppender() {
            assertEquals(1, UnexpectedErrorLogCaptureCoordinator.installedAppenderCount());
        }
    }

    static class ParallelClassAFixture {
        private static final org.slf4j.Logger LOGGER =
                LoggerFactory.getLogger(ParallelClassAFixture.class);

        @Test
        @ExpectedErrorLog(
                logger = "dev.mars.peegeeq.test.logging.UnexpectedErrorLogPackagedIntegrationTest$ParallelClassAFixture",
                message = "parallel class error a",
                throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
        void emitsExpectedError() {
            LOGGER.error("parallel class error a");
        }
    }

    static class ParallelClassBFixture {
        private static final org.slf4j.Logger LOGGER =
                LoggerFactory.getLogger(ParallelClassBFixture.class);

        @Test
        @ExpectedErrorLog(
                logger = "dev.mars.peegeeq.test.logging.UnexpectedErrorLogPackagedIntegrationTest$ParallelClassBFixture",
                message = "parallel class error b",
                throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
        void emitsExpectedError() {
            LOGGER.error("parallel class error b");
        }
    }

    @TestMethodOrder(MethodOrderer.MethodName.class)
    static class MethodIsolationFixture {
        private static final org.slf4j.Logger LOGGER =
                LoggerFactory.getLogger(MethodIsolationFixture.class);

        @Test
        @ExpectedErrorLog(
                logger = "dev.mars.peegeeq.test.logging.UnexpectedErrorLogPackagedIntegrationTest$MethodIsolationFixture",
                message = "method-isolation error",
                throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
        void a_expectedMethodOwnsOneEvent() {
            LOGGER.error("method-isolation error");
        }

        @Test
        void b_nextMethodCannotReusePriorExpectation() {
            LOGGER.error("method-isolation error");
        }
    }

    static class TestOwnedAppenderFixture {
        private static final Logger LOGGER =
                (Logger) LoggerFactory.getLogger(TestOwnedAppenderFixture.class);
        private ListAppender<ILoggingEvent> ownedAppender;

        @BeforeEach
        void attachOwnedAppender() {
            ownedAppender = new ListAppender<>();
            ownedAppender.setContext(LOGGER.getLoggerContext());
            ownedAppender.start();
            LOGGER.addAppender(ownedAppender);
        }

        @AfterEach
        void verifyAndDetachOwnedAppender() {
            try {
                assertEquals(1, ownedAppender.list.size());
                assertEquals("test-owned appender event",
                        ownedAppender.list.getFirst().getFormattedMessage());
            } finally {
                LOGGER.detachAppender(ownedAppender);
                ownedAppender.stop();
            }
        }

        @Test
        @ExpectedErrorLog(
                logger = "dev.mars.peegeeq.test.logging.UnexpectedErrorLogPackagedIntegrationTest$TestOwnedAppenderFixture",
                message = "test-owned appender event",
                throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
        void expectedEventReachesBothAppenders() {
            LOGGER.error("test-owned appender event");
        }
    }
}
