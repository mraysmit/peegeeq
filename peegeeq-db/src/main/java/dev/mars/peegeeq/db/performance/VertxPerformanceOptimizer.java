package dev.mars.peegeeq.db.performance;

import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performance optimization utility following Vert.x PostgreSQL performance checklist:
 * 
 * 1. Set pool size (not 4): try 16/32 and tune with your DBA
 * 2. Share one pool across all verticles (setShared(true))
 * 3. Deploy multiple instances of your verticles (≃ cores)
 * 4. Don't hold a SqlConnection for the whole app; use pool ops or short-lived withConnection
 * 5. Keep transactions short, and don't wrap everything in a tx
 * 6. Enable/test pipelining (8–32), if you aren't behind a proxy that chokes on it
 * 7. Measure: p95 latency, pool wait time, DB CPU and iowait
 */
public class VertxPerformanceOptimizer {
    private static final Logger logger = LoggerFactory.getLogger(VertxPerformanceOptimizer.class);
    
    /**
     * Creates an optimized Vertx instance following performance best practices.
     * 
     * @return Optimized Vertx instance
     */
    public static Vertx createOptimizedVertx() {
        int eventLoopPoolSize = getOptimalEventLoopSize();
        int workerPoolSize = getOptimalWorkerPoolSize();
        
        VertxOptions options = new VertxOptions()
            .setEventLoopPoolSize(eventLoopPoolSize)
            .setWorkerPoolSize(workerPoolSize)
            .setMaxEventLoopExecuteTime(2000000000L) // 2 seconds
            .setMaxWorkerExecuteTime(60000000000L); // 60 seconds
        
        logger.info("Creating optimized Vertx instance: eventLoops={}, workers={}", 
                   eventLoopPoolSize, workerPoolSize);
        
        return Vertx.vertx(options);
    }
    
    /**
     * Creates an optimized PostgreSQL pool following performance checklist.
     * 
     * @param vertx The Vertx instance
     * @param connectionConfig Connection configuration
     * @param poolConfig Pool configuration
     * @return Optimized Pool
     */
    public static Pool createOptimizedPool(Vertx vertx, 
                                         PgConnectionConfig connectionConfig, 
                                         PgPoolConfig poolConfig) {
        
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(connectionConfig.getHost())
            .setPort(connectionConfig.getPort())
            .setDatabase(connectionConfig.getDatabase())
            .setUser(connectionConfig.getUsername())
            .setPassword(connectionConfig.getPassword());
        
        if (connectionConfig.isSslEnabled()) {
            connectOptions.setSslMode(io.vertx.pgclient.SslMode.REQUIRE);
        }
        
        // Enable pipelining for performance (checklist item #6)
        int pipeliningLimit = getPipeliningLimit();
        connectOptions.setPipeliningLimit(pipeliningLimit);
        
        // Set application name for monitoring (checklist item #7)
        connectOptions.addProperty("application_name", "peegeeq-optimized");
        
        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(poolConfig.getMaximumPoolSize())
            .setShared(poolConfig.isShared()) // Share across verticles (checklist item #2)
            .setMaxWaitQueueSize(poolConfig.getMaximumPoolSize() * 4); // Prevent queue buildup
        
        Pool pool = PgBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build();
        
        logger.info("Created optimized PostgreSQL pool: maxSize={}, shared={}, pipelining={}", 
                   poolConfig.getMaximumPoolSize(), poolConfig.isShared(), pipeliningLimit);
        
        return pool;
    }
    
    /**
     * Creates deployment options for multiple verticle instances (checklist item #3).
     * 
     * @return DeploymentOptions with optimal instance count
     */
    public static DeploymentOptions createOptimizedDeploymentOptions() {
        int instances = getOptimalVerticleInstances();
        
        DeploymentOptions options = new DeploymentOptions()
            .setInstances(instances)
            .setMaxWorkerExecuteTime(60000000000L); // 60 seconds
        
        logger.info("Created deployment options with {} verticle instances", instances);
        
        return options;
    }
    
    /**
     * Gets optimal event loop pool size based on available processors.
     * 
     * @return Optimal event loop pool size
     */
    private static int getOptimalEventLoopSize() {
        int processors = Runtime.getRuntime().availableProcessors();
        int eventLoops = Integer.parseInt(System.getProperty("peegeeq.database.event.loop.size", 
                                                            String.valueOf(processors * 2)));
        
        // Ensure reasonable bounds
        return Math.max(2, Math.min(eventLoops, 32));
    }
    
    /**
     * Gets optimal worker pool size for blocking operations.
     * 
     * @return Optimal worker pool size
     */
    private static int getOptimalWorkerPoolSize() {
        int processors = Runtime.getRuntime().availableProcessors();
        int workers = Integer.parseInt(System.getProperty("peegeeq.database.worker.pool.size", 
                                                         String.valueOf(processors * 4)));
        
        // Ensure reasonable bounds
        return Math.max(4, Math.min(workers, 64));
    }
    
    /**
     * Gets optimal number of verticle instances (≃ cores).
     * 
     * @return Optimal verticle instance count
     */
    private static int getOptimalVerticleInstances() {
        int processors = Runtime.getRuntime().availableProcessors();
        int instances = Integer.parseInt(System.getProperty("peegeeq.verticle.instances", 
                                                           String.valueOf(processors)));
        
        // Ensure reasonable bounds
        return Math.max(1, Math.min(instances, 16));
    }
    
    /**
     * Gets optimal pipelining limit for PostgreSQL connections.
     * 
     * @return Optimal pipelining limit
     */
    private static int getPipeliningLimit() {
        boolean pipeliningEnabled = Boolean.parseBoolean(
            System.getProperty("peegeeq.database.pipelining.enabled", "true"));
        
        if (!pipeliningEnabled) {
            return 1; // Disable pipelining
        }
        
        int limit = Integer.parseInt(System.getProperty("peegeeq.database.pipelining.limit", "32"));
        
        // Ensure reasonable bounds (8-256 as per checklist)
        return Math.max(8, Math.min(limit, 256));
    }
    
    /**
     * Validates pool configuration against performance checklist.
     * 
     * @param poolConfig Pool configuration to validate
     * @return Validation warnings/recommendations
     */
    public static String validatePoolConfiguration(PgPoolConfig poolConfig) {
        StringBuilder warnings = new StringBuilder();
        
        // Check pool size (checklist item #1)
        if (poolConfig.getMaximumPoolSize() < 16) {
            warnings.append("⚠️  Pool size (").append(poolConfig.getMaximumPoolSize())
                   .append(") is below recommended minimum of 16. Consider increasing to 16-32.\n");
        }
        
        // Check shared setting (checklist item #2)
        if (!poolConfig.isShared()) {
            warnings.append("⚠️  Pool sharing is disabled. Enable setShared(true) to share pools across verticles.\n");
        }
        
        // Check pipelining
        int pipeliningLimit = getPipeliningLimit();
        if (pipeliningLimit < 8) {
            warnings.append("⚠️  Pipelining limit (").append(pipeliningLimit)
                   .append(") is below recommended minimum of 8.\n");
        }
        
        if (warnings.length() == 0) {
            return "✅ Pool configuration follows performance best practices";
        }
        
        return warnings.toString();
    }
}
