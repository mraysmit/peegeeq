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

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

import java.util.List;

/**
 * JUnit extension that converts unclaimed ERROR logs into test failures.
 *
 * <p>The extension is packaged for JUnit service discovery and auto-detected by the
 * repository's normal Maven test profiles.</p>
 */
public final class UnexpectedErrorLogExtension implements
        BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback {

    static {
        UnexpectedErrorLogCaptureCoordinator.initializeLogging();
    }

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(UnexpectedErrorLogExtension.class);
    private static final String RUN_LEASE_KEY = "process-wide-error-log-capture";

    @Override
    public void beforeAll(ExtensionContext context) {
        runLease(context).ledger().openContainer(owner(context));
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        String owner = owner(context);
        List<ErrorLogExpectation> expectations = AnnotationSupport
                .findRepeatableAnnotations(context.getElement(), ExpectedErrorLog.class)
                .stream()
                .map(ErrorLogExpectation::from)
                .toList();
        ErrorLogLedger ledger = runLease(context).ledger();
        ledger.openScope(owner, expectations);
        ErrorLogExpectations.bind(ledger, owner);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ErrorLogExpectations.clear();
        ErrorLogLedger.ScopeResult result = runLease(context).ledger().closeScope(owner(context));
        if (!result.isSuccessful()) {
            throw new AssertionError(result.failureMessage());
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        ErrorLogLedger.ScopeResult result =
                runLease(context).ledger().closeContainer(owner(context));
        if (!result.isSuccessful()) {
            throw new AssertionError(result.failureMessage());
        }
    }

    private static UnexpectedErrorLogCaptureCoordinator.RunLease runLease(
            ExtensionContext context) {
        return context.getRoot()
                .getStore(NAMESPACE)
                .getOrComputeIfAbsent(
                        RUN_LEASE_KEY,
                        ignored -> UnexpectedErrorLogCaptureCoordinator.acquire(),
                        UnexpectedErrorLogCaptureCoordinator.RunLease.class);
    }

    private static String owner(ExtensionContext context) {
        return context.getUniqueId() + " [" + context.getDisplayName() + "]";
    }

}
