package dev.mars.peegeeq.db.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

public class SqlTemplateProcessor {
    
    public void applyTemplate(Connection connection, String templateName, 
                            Map<String, String> parameters) throws SQLException, IOException {
        
        String templateContent = loadTemplate(templateName);
        String processedSql = processTemplate(templateContent, parameters);
        
        try (var stmt = connection.createStatement()) {
            stmt.execute(processedSql);
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