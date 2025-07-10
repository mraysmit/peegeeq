package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter that creates a Vert.x PgPool from PgClientFactory configuration.
 * This bridges the JDBC-based PgClientFactory with Vert.x's reactive PostgreSQL client.
 */
public class VertxPoolAdapter {
    private static final Logger logger = LoggerFactory.getLogger(VertxPoolAdapter.class);
    
    private final Vertx vertx;
    private PgPool pool;
    
    public VertxPoolAdapter() {
        this.vertx = Vertx.vertx();
    }
    
    public VertxPoolAdapter(Vertx vertx) {
        this.vertx = vertx;
    }
    
    /**
     * Creates a Vert.x PgPool from the configuration used by PgClientFactory.
     * 
     * @param clientFactory The PgClientFactory to extract configuration from
     * @param clientId The client ID to use for configuration lookup
     * @return A Vert.x PgPool
     */
    public PgPool createPool(PgClientFactory clientFactory, String clientId) {
        if (pool != null) {
            return pool;
        }
        
        // We need to extract configuration from the client factory
        // Since we can't directly access the configuration, we'll use default values
        // In a real implementation, you'd want to pass the configuration explicitly
        
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost("localhost")
            .setPort(5432)
            .setDatabase("peegeeq")
            .setUser("peegeeq")
            .setPassword("peegeeq");
        
        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(10);
        
        pool = PgPool.pool(vertx, connectOptions, poolOptions);
        logger.info("Created Vert.x PgPool for client: {}", clientId);
        
        return pool;
    }
    
    /**
     * Creates a Vert.x PgPool with explicit configuration.
     * 
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The pool configuration
     * @return A Vert.x PgPool
     */
    public PgPool createPool(PgConnectionConfig connectionConfig, PgPoolConfig poolConfig) {
        if (pool != null) {
            return pool;
        }
        
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(connectionConfig.getHost())
            .setPort(connectionConfig.getPort())
            .setDatabase(connectionConfig.getDatabase())
            .setUser(connectionConfig.getUsername())
            .setPassword(connectionConfig.getPassword());
        
        if (connectionConfig.isSslEnabled()) {
            connectOptions.setSsl(true);
        }
        
        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(poolConfig.getMaximumPoolSize());
        
        pool = PgPool.pool(vertx, connectOptions, poolOptions);
        logger.info("Created Vert.x PgPool with explicit configuration");
        
        return pool;
    }
    
    /**
     * Gets the current pool, or null if not created yet.
     * 
     * @return The current PgPool or null
     */
    public PgPool getPool() {
        return pool;
    }
    
    /**
     * Closes the pool and Vert.x instance.
     */
    public void close() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
        
        if (vertx != null) {
            vertx.close();
        }
        
        logger.info("Closed VertxPoolAdapter");
    }
}
