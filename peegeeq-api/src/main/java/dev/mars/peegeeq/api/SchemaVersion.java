package dev.mars.peegeeq.api;

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


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark classes with schema version information.
 * 
 * This annotation is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
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
