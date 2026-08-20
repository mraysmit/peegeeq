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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Owns the single process-wide ERROR capture appender used by active JUnit engine runs. */
final class UnexpectedErrorLogCaptureCoordinator {

    static final String APPENDER_NAME = "peegeeq-unexpected-error-global";

    private static final Object LOCK = new Object();
    private static LoggerContext loggerContext;
    private static Logger rootLogger;
    private static ErrorCaptureAppender appender;
    private static final List<RunLease> ACTIVE_RUNS = new ArrayList<>();

    private UnexpectedErrorLogCaptureCoordinator() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    static RunLease acquire() {
        RunLease lease;
        synchronized (LOCK) {
            if (ACTIVE_RUNS.isEmpty()) {
                installAppender();
            }
            lease = new RunLease(new ErrorLogLedger());
            ACTIVE_RUNS.add(lease);
        }
        ErrorLogExpectations.bindRun(lease.ledger);
        return lease;
    }

    /**
     * Initializes SLF4J/Logback while JUnit is still discovering its global
     * extensions, before test classes begin concurrent execution.
     */
    static void initializeLogging() {
        synchronized (LOCK) {
            if (loggerContext != null) {
                return;
            }
            ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
            if (!(loggerFactory instanceof LoggerContext resolvedContext)) {
                throw new IllegalStateException(
                        "Unexpected ERROR enforcement requires a Logback LoggerContext");
            }
            loggerContext = resolvedContext;
        }
    }

    static int installedAppenderCount() {
        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (!(loggerFactory instanceof LoggerContext loggerContext)) {
            return 0;
        }

        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        Iterator<Appender<ILoggingEvent>> appenders = root.iteratorForAppenders();
        int count = 0;
        while (appenders.hasNext()) {
            if (APPENDER_NAME.equals(appenders.next().getName())) {
                count++;
            }
        }
        return count;
    }

    private static void installAppender() {
        initializeLogging();

        rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ErrorCaptureAppender();
        appender.setContext(loggerContext);
        appender.setName(APPENDER_NAME);
        appender.start();
        rootLogger.addAppender(appender);
    }

    private static void release(RunLease lease) {
        List<String> violations;
        synchronized (LOCK) {
            if (lease.closed) {
                return;
            }
            lease.closed = true;
            if (!ACTIVE_RUNS.remove(lease)) {
                throw new IllegalStateException("Unexpected ERROR appender lease was not active");
            }
            violations = lease.ledger.finishRun();
            if (ACTIVE_RUNS.isEmpty()) {
                rootLogger.detachAppender(appender);
                appender.stop();
                rootLogger = null;
                appender = null;
            }
        }
        ErrorLogExpectations.unbindRun(lease.ledger);

        if (!violations.isEmpty()) {
            throw new AssertionError("Unowned ERROR log contract failed at JUnit run close:\n - "
                    + String.join("\n - ", violations));
        }
    }

    static final class RunLease implements ExtensionContext.Store.CloseableResource {
        private final ErrorLogLedger ledger;
        private boolean closed;

        private RunLease(ErrorLogLedger ledger) {
            this.ledger = ledger;
        }

        ErrorLogLedger ledger() {
            return ledger;
        }

        @Override
        public void close() {
            release(this);
        }
    }

    private static final class ErrorCaptureAppender extends AppenderBase<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            if (Level.ERROR.equals(event.getLevel())) {
                capture(ErrorLogLedger.ErrorEvent.from(event));
            }
        }

        private static void capture(ErrorLogLedger.ErrorEvent event) {
            ErrorLogExpectations.CaptureTarget target =
                    ErrorLogExpectations.currentCaptureTarget();
            if (target != null) {
                target.ledger().capture(event, target.owner());
                return;
            }

            List<RunLease> activeRuns;
            synchronized (LOCK) {
                activeRuns = List.copyOf(ACTIVE_RUNS);
            }
            for (RunLease run : activeRuns) {
                run.ledger.capture(event, null);
            }
        }
    }
}
