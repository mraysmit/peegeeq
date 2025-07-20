/**
 * Database services and configuration contracts for the PeeGeeQ message queue system.
 * 
 * <p>This package contains interfaces for database operations, connection management,
 * metrics collection, and database configuration. It provides clean abstractions that 
 * hide database-specific implementation details while offering production-ready 
 * database capabilities.</p>
 * 
 * <h2>Core Database Services:</h2>
 * <ul>
 *   <li>{@link dev.mars.peegeeq.api.database.DatabaseService} - Core database operations</li>
 *   <li>{@link dev.mars.peegeeq.api.database.ConnectionProvider} - Database connection management</li>
 *   <li>{@link dev.mars.peegeeq.api.database.MetricsProvider} - Metrics collection interface</li>
 * </ul>
 * 
 * <h2>Configuration:</h2>
 * <ul>
 *   <li>{@link dev.mars.peegeeq.api.database.DatabaseConfig} - Database configuration</li>
 *   <li>{@link dev.mars.peegeeq.api.database.QueueConfig} - Queue configuration</li>
 *   <li>{@link dev.mars.peegeeq.api.database.EventStoreConfig} - Event store configuration</li>
 *   <li>{@link dev.mars.peegeeq.api.database.ConnectionPoolConfig} - Connection pool settings</li>
 * </ul>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Build database configuration
 * DatabaseConfig dbConfig = DatabaseConfig.builder()
 *     .withHost("localhost")
 *     .withPort(5432)
 *     .withDatabaseName("mydb")
 *     .withUsername("user")
 *     .withPassword("password")
 *     .build();
 * 
 * // Create a database service
 * DatabaseService dbService = new PgDatabaseService(manager);
 * 
 * // Perform health check
 * boolean healthy = dbService.performHealthCheck().join();
 * 
 * // Run migrations
 * dbService.runMigrations().join();
 * 
 * // Get connection provider
 * ConnectionProvider connectionProvider = dbService.getConnectionProvider();
 * 
 * // Get metrics provider
 * MetricsProvider metricsProvider = dbService.getMetricsProvider();
 * }</pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 1.0
 * @version 1.0
 */
package dev.mars.peegeeq.api.database;
