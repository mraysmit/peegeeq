package dev.mars.peegeeq.db.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

public class SqlTemplateProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SqlTemplateProcessor.class);
    
    public void applyTemplate(Connection connection, String templateName,
                            Map<String, String> parameters) throws SQLException, IOException {

        logger.info("SqlTemplateProcessor.applyTemplate called for template: {}", templateName);
        String templateContent = loadTemplate(templateName);
        String processedSql = processTemplate(templateContent, parameters);

        logger.info("About to execute processed SQL for template: {}", templateName);
        logger.debug("Processed SQL content: {}", processedSql);

        try (var stmt = connection.createStatement()) {
            stmt.execute(processedSql);
            logger.info("Successfully executed SQL for template: {}", templateName);
        } catch (SQLException e) {
            logger.error("Failed to execute SQL for template: {} - Error: {}", templateName, e.getMessage());
            logger.debug("Failed SQL content: {}", processedSql);
            throw e;
        }
    }
    
    private String loadTemplate(String templateName) throws IOException {
        try (var inputStream = getClass().getResourceAsStream("/db/templates/" + templateName)) {
            if (inputStream == null) {
                throw new IOException("Template not found: " + templateName);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    
    private String processTemplate(String template, Map<String, String> parameters) {
        String result = template;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}