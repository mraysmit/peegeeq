package dev.mars.peegeeq.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark classes with schema version information.
 * This is used for schema evolution and backward compatibility.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SchemaVersion {
    
    /**
     * The version number of the schema.
     * Should follow semantic versioning (e.g., "1.0.0", "2.1.0").
     * 
     * @return The schema version
     */
    String value();
    
    /**
     * Optional description of changes in this version.
     * 
     * @return Description of schema changes
     */
    String description() default "";
    
    /**
     * Indicates if this version is backward compatible with previous versions.
     * 
     * @return true if backward compatible, false otherwise
     */
    boolean backwardCompatible() default true;
    
    /**
     * The minimum version that this schema can be downgraded to.
     * Empty string means no downgrade support.
     * 
     * @return Minimum compatible version for downgrades
     */
    String minimumCompatibleVersion() default "";
}
