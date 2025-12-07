/*
 * Copyright (c) 2025 Cityline Ltd
 * All rights reserved.
 */
package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ReactiveUtils utility methods.
 * Tests the bridge methods between Vert.x Future and CompletableFuture.
 */
@Tag(TestCategories.CORE)
class ReactiveUtilsTest {

    private static final Logger logger = LoggerFactory.getLogger(ReactiveUtilsTest.class);
    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            vertx.close();
        }
    }

    @Test
    @DisplayName("toCompletableFuture - converts successful Future to CompletableFuture")
    void testToCompletableFuture_Success() throws Exception {
        // Given
        String expectedResult = "test-result";
        Future<String> future = Future.succeededFuture(expectedResult);

        // When
        CompletableFuture<String> completableFuture = ReactiveUtils.toCompletableFuture(future);
        String result = completableFuture.get(5, TimeUnit.SECONDS);

        // Then
        assertEquals(expectedResult, result);
    }

    @Test
    @DisplayName("toCompletableFuture - converts failed Future to failed CompletableFuture")
    void testToCompletableFuture_Failure() {
        // Given
        RuntimeException expectedException = new RuntimeException("Test error");
        Future<String> future = Future.failedFuture(expectedException);

        // When
        CompletableFuture<String> completableFuture = ReactiveUtils.toCompletableFuture(future);

        // Then
        ExecutionException thrown = assertThrows(ExecutionException.class, 
            () -> completableFuture.get(5, TimeUnit.SECONDS));
        assertEquals(expectedException, thrown.getCause());
    }

    @Test
    @DisplayName("fromCompletableFuture - converts successful CompletableFuture to Future")
    void testFromCompletableFuture_Success() throws Exception {
        // Given
        String expectedResult = "test-result";
        CompletableFuture<String> completableFuture = CompletableFuture.completedFuture(expectedResult);

        // When
        Future<String> future = ReactiveUtils.fromCompletableFuture(completableFuture);
        String result = future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Then
        assertEquals(expectedResult, result);
    }

    @Test
    @DisplayName("fromCompletableFuture - converts failed CompletableFuture to failed Future")
    void testFromCompletableFuture_Failure() {
        // Given
        RuntimeException expectedException = new RuntimeException("Test error");
        CompletableFuture<String> completableFuture = new CompletableFuture<>();
        completableFuture.completeExceptionally(expectedException);

        // When
        Future<String> future = ReactiveUtils.fromCompletableFuture(completableFuture);

        // Then
        ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(expectedException, thrown.getCause());
    }

    @Test
    @DisplayName("failedFuture - creates a failed CompletableFuture")
    void testFailedFuture() {
        // Given
        RuntimeException expectedException = new RuntimeException("Test error");

        // When
        CompletableFuture<String> future = ReactiveUtils.failedFuture(expectedException);

        // Then
        assertTrue(future.isCompletedExceptionally());
        ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> future.get(5, TimeUnit.SECONDS));
        assertEquals(expectedException, thrown.getCause());
    }

    @Test
    @DisplayName("completedFuture - creates a completed CompletableFuture")
    void testCompletedFuture() throws Exception {
        // Given
        String expectedResult = "test-result";

        // When
        CompletableFuture<String> future = ReactiveUtils.completedFuture(expectedResult);

        // Then
        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        assertEquals(expectedResult, future.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("completedFuture - handles null result")
    void testCompletedFuture_NullResult() throws Exception {
        // When
        CompletableFuture<String> future = ReactiveUtils.completedFuture(null);

        // Then
        assertTrue(future.isDone());
        assertNull(future.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("logExecutionContext - does not throw when called outside Vert.x context")
    void testLogExecutionContext_OutsideVertxContext() {
        // When/Then - should not throw
        assertDoesNotThrow(() -> ReactiveUtils.logExecutionContext());
    }

    @Test
    @DisplayName("executeOnVertxContext - executes operation and returns result")
    void testExecuteOnVertxContext_Success() throws Exception {
        // Given
        String expectedResult = "context-result";

        // When
        Future<String> future = ReactiveUtils.executeOnVertxContext(vertx,
            () -> Future.succeededFuture(expectedResult));
        String result = future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Then
        assertEquals(expectedResult, result);
    }

    @Test
    @DisplayName("executeOnVertxContext - propagates failure from operation")
    void testExecuteOnVertxContext_Failure() {
        // Given
        RuntimeException expectedException = new RuntimeException("Context operation failed");

        // When
        Future<String> future = ReactiveUtils.executeOnVertxContext(vertx,
            () -> Future.failedFuture(expectedException));

        // Then
        ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(expectedException, thrown.getCause());
    }

    @Test
    @DisplayName("executeOnVertxContextAsync - executes async operation and returns result")
    void testExecuteOnVertxContextAsync_Success() throws Exception {
        // Given
        String expectedResult = "async-context-result";

        // When
        CompletableFuture<String> future = ReactiveUtils.executeOnVertxContextAsync(vertx,
            () -> CompletableFuture.completedFuture(expectedResult));
        String result = future.get(5, TimeUnit.SECONDS);

        // Then
        assertEquals(expectedResult, result);
    }

    @Test
    @DisplayName("executeOnVertxContextAsync - propagates failure from async operation")
    void testExecuteOnVertxContextAsync_Failure() {
        // Given
        RuntimeException expectedException = new RuntimeException("Async context operation failed");
        CompletableFuture<String> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(expectedException);

        // When
        CompletableFuture<String> future = ReactiveUtils.executeOnVertxContextAsync(vertx,
            () -> failedFuture);

        // Then
        ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> future.get(5, TimeUnit.SECONDS));
        assertEquals(expectedException, thrown.getCause());
    }

    @Test
    @DisplayName("toCompletableFuture - handles delayed Future completion")
    void testToCompletableFuture_DelayedCompletion() throws Exception {
        // Given
        String expectedResult = "delayed-result";

        // When - create a Future that completes after a delay
        Future<String> future = vertx.executeBlocking(() -> {
            Thread.sleep(100);
            return expectedResult;
        });

        CompletableFuture<String> completableFuture = ReactiveUtils.toCompletableFuture(future);
        String result = completableFuture.get(5, TimeUnit.SECONDS);

        // Then
        assertEquals(expectedResult, result);
    }

    @Test
    @DisplayName("fromCompletableFuture - handles delayed CompletableFuture completion")
    void testFromCompletableFuture_DelayedCompletion() throws Exception {
        // Given
        String expectedResult = "delayed-cf-result";
        CompletableFuture<String> completableFuture = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return expectedResult;
        });

        // When
        Future<String> future = ReactiveUtils.fromCompletableFuture(completableFuture);
        String result = future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Then
        assertEquals(expectedResult, result);
    }

    @Test
    @DisplayName("Round-trip conversion preserves result")
    void testRoundTripConversion() throws Exception {
        // Given
        String expectedResult = "round-trip-result";
        Future<String> originalFuture = Future.succeededFuture(expectedResult);

        // When - convert Future -> CompletableFuture -> Future
        CompletableFuture<String> cf = ReactiveUtils.toCompletableFuture(originalFuture);
        Future<String> backToFuture = ReactiveUtils.fromCompletableFuture(cf);
        String result = backToFuture.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Then
        assertEquals(expectedResult, result);
    }

    @Test
    @DisplayName("Round-trip conversion preserves error")
    void testRoundTripConversion_Error() {
        // Given
        RuntimeException expectedException = new RuntimeException("Round-trip error");
        Future<String> originalFuture = Future.failedFuture(expectedException);

        // When - convert Future -> CompletableFuture -> Future
        CompletableFuture<String> cf = ReactiveUtils.toCompletableFuture(originalFuture);
        Future<String> backToFuture = ReactiveUtils.fromCompletableFuture(cf);

        // Then
        ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> backToFuture.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(expectedException, thrown.getCause());
    }
}

