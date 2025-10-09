package dev.mars.peegeeq.examples.springboot2;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
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

import dev.mars.peegeeq.api.database.ConnectionProvider;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.examples.springboot2.adapter.ReactiveOutboxAdapter;
import dev.mars.peegeeq.examples.springboot2.events.OrderEvent;
import dev.mars.peegeeq.examples.springboot2.model.Order;
import dev.mars.peegeeq.examples.springboot2.repository.OrderItemRepository;
import dev.mars.peegeeq.examples.springboot2.repository.OrderRepository;
import dev.mars.peegeeq.examples.springboot2.service.OrderService;
import dev.mars.peegeeq.outbox.OutboxProducer;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for OrderService using mocks to verify ConnectionProvider.withConnection() usage.
 * 
 * This test verifies that our implementation correctly uses:
 * - ConnectionProvider.withConnection() for read operations
 * - Proper reactive patterns with Mono/StepVerifier
 * - Error handling and edge cases
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-09
 * @version 1.0
 */
class OrderServiceUnitTest {

    @Mock
    private DatabaseService databaseService;
    
    @Mock
    private ConnectionProvider connectionProvider;
    
    @Mock
    private OutboxProducer<OrderEvent> orderEventProducer;
    
    @Mock
    private OrderRepository orderRepository;
    
    @Mock
    private OrderItemRepository orderItemRepository;
    
    @Mock
    private ReactiveOutboxAdapter adapter;
    
    @Mock
    private SqlConnection connection;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Setup basic mocks
        when(databaseService.getConnectionProvider()).thenReturn(connectionProvider);
        
        orderService = new OrderService(
            databaseService,
            orderEventProducer,
            orderRepository,
            orderItemRepository,
            adapter
        );
    }

    @Test
    @DisplayName("findById should use ConnectionProvider.withConnection() and return order when found")
    void testFindByIdFound() {
        // Given
        String orderId = "test-order-123";
        Order expectedOrder = new Order(orderId, "CUST-001", new BigDecimal("99.99"), Arrays.asList());
        
        // Mock the connection provider to call the operation with our mock connection
        when(connectionProvider.withConnection(eq("peegeeq-main"), any()))
            .thenAnswer(invocation -> {
                Function<SqlConnection, Future<Order>> operation = invocation.getArgument(1);
                return operation.apply(connection).toCompletionStage().toCompletableFuture();
            });
        
        // Mock the repository to return the order
        when(orderRepository.findById(orderId, connection))
            .thenReturn(Future.succeededFuture(Optional.of(expectedOrder)));
        
        // Mock the adapter to convert CompletableFuture to Mono
        when(adapter.toMono(any(CompletableFuture.class)))
            .thenReturn(Mono.just(expectedOrder));

        // When
        Mono<Order> result = orderService.findById(orderId);

        // Then
        StepVerifier.create(result)
            .expectNext(expectedOrder)
            .expectComplete()
            .verify(Duration.ofSeconds(5));

        // Verify interactions
        verify(databaseService).getConnectionProvider();
        verify(connectionProvider).withConnection(eq("peegeeq-main"), any());
        verify(orderRepository).findById(orderId, connection);
        verify(adapter).toMono(any(CompletableFuture.class));
    }

    @Test
    @DisplayName("findById should return empty Mono when order not found")
    void testFindByIdNotFound() {
        // Given
        String orderId = "non-existent-order";
        
        // Mock the connection provider
        when(connectionProvider.withConnection(eq("peegeeq-main"), any()))
            .thenAnswer(invocation -> {
                Function<SqlConnection, Future<Optional<Order>>> operation = invocation.getArgument(1);
                return operation.apply(connection).toCompletionStage().toCompletableFuture();
            });
        
        // Mock the repository to return empty
        when(orderRepository.findById(orderId, connection))
            .thenReturn(Future.succeededFuture(Optional.empty()));
        
        // Mock the adapter to convert CompletableFuture to Mono
        when(adapter.toMono(any(CompletableFuture.class)))
            .thenReturn(Mono.empty());

        // When
        Mono<Order> result = orderService.findById(orderId);

        // Then
        StepVerifier.create(result)
            .expectNextCount(0)
            .expectComplete()
            .verify(Duration.ofSeconds(5));

        // Verify interactions
        verify(connectionProvider).withConnection(eq("peegeeq-main"), any());
        verify(orderRepository).findById(orderId, connection);
    }

    @Test
    @DisplayName("findByCustomerId should use ConnectionProvider.withConnection() and return order when found")
    void testFindByCustomerIdFound() {
        // Given
        String customerId = "CUST-001";
        Order expectedOrder = new Order("order-123", customerId, new BigDecimal("149.99"), Arrays.asList());
        
        // Mock the connection provider
        when(connectionProvider.withConnection(eq("peegeeq-main"), any()))
            .thenAnswer(invocation -> {
                Function<SqlConnection, Future<Order>> operation = invocation.getArgument(1);
                return operation.apply(connection).toCompletionStage().toCompletableFuture();
            });
        
        // Mock the repository to return the order
        when(orderRepository.findByCustomerId(customerId, connection))
            .thenReturn(Future.succeededFuture(Optional.of(expectedOrder)));
        
        // Mock the adapter
        when(adapter.toMono(any(CompletableFuture.class)))
            .thenReturn(Mono.just(expectedOrder));

        // When
        Mono<Order> result = orderService.findByCustomerId(customerId);

        // Then
        StepVerifier.create(result)
            .expectNext(expectedOrder)
            .expectComplete()
            .verify(Duration.ofSeconds(5));

        // Verify interactions
        verify(connectionProvider).withConnection(eq("peegeeq-main"), any());
        verify(orderRepository).findByCustomerId(customerId, connection);
    }

    @Test
    @DisplayName("validateOrder should use ConnectionProvider.withConnection() and complete successfully")
    void testValidateOrderSuccess() {
        // Given
        String orderId = "valid-order-123";
        Order existingOrder = new Order(orderId, "CUST-001", new BigDecimal("99.99"), Arrays.asList());
        
        // Mock the connection provider
        when(connectionProvider.withConnection(eq("peegeeq-main"), any()))
            .thenAnswer(invocation -> {
                Function<SqlConnection, Future<Void>> operation = invocation.getArgument(1);
                return operation.apply(connection).toCompletionStage().toCompletableFuture();
            });
        
        // Mock the repository to return the order
        when(orderRepository.findById(orderId, connection))
            .thenReturn(Future.succeededFuture(Optional.of(existingOrder)));
        
        // Mock the adapter
        when(adapter.toMono(any(CompletableFuture.class)))
            .thenReturn(Mono.empty());

        // When
        Mono<Void> result = orderService.validateOrder(orderId);

        // Then
        StepVerifier.create(result)
            .expectComplete()
            .verify(Duration.ofSeconds(5));

        // Verify interactions
        verify(connectionProvider).withConnection(eq("peegeeq-main"), any());
        verify(orderRepository).findById(orderId, connection);
    }

    @Test
    @DisplayName("validateOrder should fail when order not found")
    void testValidateOrderNotFound() {
        // Given
        String orderId = "non-existent-order";
        
        // Mock the connection provider
        when(connectionProvider.withConnection(eq("peegeeq-main"), any()))
            .thenAnswer(invocation -> {
                Function<SqlConnection, Future<Void>> operation = invocation.getArgument(1);
                return operation.apply(connection).toCompletionStage().toCompletableFuture();
            });
        
        // Mock the repository to return empty
        when(orderRepository.findById(orderId, connection))
            .thenReturn(Future.succeededFuture(Optional.empty()));
        
        // Mock the adapter to return error
        when(adapter.toMono(any(CompletableFuture.class)))
            .thenReturn(Mono.error(new IllegalArgumentException("Order not found: " + orderId)));

        // When
        Mono<Void> result = orderService.validateOrder(orderId);

        // Then
        StepVerifier.create(result)
            .expectError(IllegalArgumentException.class)
            .verify(Duration.ofSeconds(5));

        // Verify interactions
        verify(connectionProvider).withConnection(eq("peegeeq-main"), any());
        verify(orderRepository).findById(orderId, connection);
    }
}
