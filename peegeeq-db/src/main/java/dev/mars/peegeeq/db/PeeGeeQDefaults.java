package dev.mars.peegeeq.db;

/**
 * Default constants for PeeGeeQ configuration.
 * 
 * <p>This class contains internal default values used by PeeGeeQ components.
 * These constants are implementation details and should not be exposed to
 * external consumers of the library.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public final class PeeGeeQDefaults {
    
    /**
     * The default pool identifier used when no specific client ID is provided.
     * 
     * <p>This is an internal implementation detail. External modules should pass
     * {@code null} to indicate they want the default pool, rather than referencing
     * this constant directly.</p>
     */
    public static final String DEFAULT_POOL_ID = "peegeeq-main";
    
    private PeeGeeQDefaults() {
        // Prevent instantiation
    }
}

