package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for H1 remediation: defensive parameter validation in SqlTemplateProcessor.
 */
@Tag(TestCategories.CORE)
class SqlTemplateProcessorValidationTest {

    private SqlTemplateProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SqlTemplateProcessor();
    }

    // Use reflection to directly test the private processTemplate method
    private String invokeProcessTemplate(String template, Map<String, String> parameters) throws Exception {
        Method method = SqlTemplateProcessor.class.getDeclaredMethod("processTemplate", String.class, Map.class);
        method.setAccessible(true);
        return (String) method.invoke(processor, template, parameters);
    }

    @Test
    void testRejectsParameterWithSingleQuote() {
        Map<String, String> params = Map.of("TABLE", "test'; DROP TABLE users;--");
        assertThrows(Exception.class, () -> invokeProcessTemplate("CREATE TABLE {TABLE}", params));
    }

    @Test
    void testRejectsParameterWithSemicolon() {
        Map<String, String> params = Map.of("TABLE", "test; DROP TABLE users");
        assertThrows(Exception.class, () -> invokeProcessTemplate("CREATE TABLE {TABLE}", params));
    }

    @Test
    void testRejectsParameterWithDoubleDash() {
        Map<String, String> params = Map.of("TABLE", "test--injection");
        assertThrows(Exception.class, () -> invokeProcessTemplate("CREATE TABLE {TABLE}", params));
    }

    @Test
    void testRejectsParameterWithBlockComment() {
        Map<String, String> params = Map.of("TABLE", "test/*injection*/end");
        assertThrows(Exception.class, () -> invokeProcessTemplate("CREATE TABLE {TABLE}", params));
    }

    @Test
    void testAcceptsValidParameter() throws Exception {
        Map<String, String> params = Map.of("TABLE", "my_valid_table");
        String result = invokeProcessTemplate("CREATE TABLE {TABLE} (id int)", params);
        assertEquals("CREATE TABLE my_valid_table (id int)", result);
    }

    @Test
    void testAcceptsEmptyParameterValue() throws Exception {
        Map<String, String> params = Map.of("TABLE", "");
        // Empty string is allowed — it's not a SQL injection marker
        String result = invokeProcessTemplate("CREATE TABLE {TABLE}", params);
        assertEquals("CREATE TABLE ", result);
    }

    @Test
    void testAcceptsParameterWithUnderscore() throws Exception {
        Map<String, String> params = Map.of("SCHEMA", "my_schema_v2");
        String result = invokeProcessTemplate("SET search_path TO {SCHEMA}", params);
        assertEquals("SET search_path TO my_schema_v2", result);
    }

    @Test
    void testMultipleParametersAllValidated() {
        Map<String, String> params = Map.of(
            "SCHEMA", "valid_schema",
            "TABLE", "evil'; DROP TABLE x;--"
        );
        assertThrows(Exception.class, () -> invokeProcessTemplate(
            "CREATE TABLE {SCHEMA}.{TABLE} (id int)", params));
    }
}
