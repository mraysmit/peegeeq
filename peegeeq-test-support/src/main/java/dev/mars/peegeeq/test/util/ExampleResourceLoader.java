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

package dev.mars.peegeeq.test.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Utility class for loading example resources from the classpath.
 * 
 * <p>This class provides convenient methods to access example JSON files,
 * configuration files, and templates that are packaged with the peegeeq-examples module.
 * 
 * <p>Example usage:
 * <pre>{@code
 * // Load a message example
 * String orderJson = ExampleResourceLoader.loadMessageExample("order-message-request.json");
 * 
 * // Load a configuration example
 * String demoConfig = ExampleResourceLoader.loadConfigExample("demo-setup.json");
 * 
 * // Load any example resource
 * String template = ExampleResourceLoader.loadExample("templates/order-template.json");
 * }</pre>
 */
public class ExampleResourceLoader {
    
    private static final String EXAMPLES_BASE_PATH = "/examples/";
    private static final String MESSAGES_PATH = EXAMPLES_BASE_PATH + "messages/";
    private static final String CONFIG_PATH = EXAMPLES_BASE_PATH + "config/";
    private static final String TEMPLATES_PATH = EXAMPLES_BASE_PATH + "templates/";
    
    private ExampleResourceLoader() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Loads a message example from the examples/messages/ directory.
     * 
     * @param filename the name of the message file (e.g., "order-message-request.json")
     * @return the content of the message file as a string
     * @throws RuntimeException if the file cannot be found or read
     */
    public static String loadMessageExample(String filename) {
        return loadResourceAsString(MESSAGES_PATH + filename);
    }
    
    /**
     * Loads a configuration example from the examples/config/ directory.
     * 
     * @param filename the name of the config file (e.g., "demo-setup.json")
     * @return the content of the config file as a string
     * @throws RuntimeException if the file cannot be found or read
     */
    public static String loadConfigExample(String filename) {
        return loadResourceAsString(CONFIG_PATH + filename);
    }
    
    /**
     * Loads a template example from the examples/templates/ directory.
     * 
     * @param filename the name of the template file
     * @return the content of the template file as a string
     * @throws RuntimeException if the file cannot be found or read
     */
    public static String loadTemplateExample(String filename) {
        return loadResourceAsString(TEMPLATES_PATH + filename);
    }
    
    /**
     * Loads any example resource by relative path from the examples/ directory.
     * 
     * @param relativePath the path relative to examples/ (e.g., "messages/order.json")
     * @return the content of the resource as a string
     * @throws RuntimeException if the resource cannot be found or read
     */
    public static String loadExample(String relativePath) {
        return loadResourceAsString(EXAMPLES_BASE_PATH + relativePath);
    }
    
    /**
     * Gets an InputStream for a message example.
     * 
     * @param filename the name of the message file
     * @return an InputStream for the resource
     * @throws RuntimeException if the resource cannot be found
     */
    public static InputStream getMessageExampleStream(String filename) {
        return getResourceAsStream(MESSAGES_PATH + filename);
    }
    
    /**
     * Gets an InputStream for a configuration example.
     * 
     * @param filename the name of the config file
     * @return an InputStream for the resource
     * @throws RuntimeException if the resource cannot be found
     */
    public static InputStream getConfigExampleStream(String filename) {
        return getResourceAsStream(CONFIG_PATH + filename);
    }
    
    /**
     * Gets an InputStream for any example resource.
     * 
     * @param relativePath the path relative to examples/
     * @return an InputStream for the resource
     * @throws RuntimeException if the resource cannot be found
     */
    public static InputStream getExampleStream(String relativePath) {
        return getResourceAsStream(EXAMPLES_BASE_PATH + relativePath);
    }
    
    private static String loadResourceAsString(String resourcePath) {
        try (InputStream inputStream = getResourceAsStream(resourcePath)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read resource: " + resourcePath, e);
        }
    }
    
    private static InputStream getResourceAsStream(String resourcePath) {
        InputStream inputStream = ExampleResourceLoader.class.getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new RuntimeException("Resource not found: " + resourcePath);
        }
        return inputStream;
    }
}
