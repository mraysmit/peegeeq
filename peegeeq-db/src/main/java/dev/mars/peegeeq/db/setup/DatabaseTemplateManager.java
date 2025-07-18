package dev.mars.peegeeq.db.setup;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

public class DatabaseTemplateManager {
    
    public void createDatabaseFromTemplate(Connection adminConnection, 
                                         String newDatabaseName, 
                                         String templateName,
                                         String encoding,
                                         Map<String, String> options) throws SQLException {
        
        String sql = buildCreateDatabaseSql(newDatabaseName, templateName, encoding, options);
        
        try (var stmt = adminConnection.createStatement()) {
            stmt.execute(sql);
        }
    }
    
    private String buildCreateDatabaseSql(String dbName, String template, 
                                        String encoding, Map<String, String> options) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE DATABASE ").append(dbName);
        
        if (template != null) {
            sql.append(" TEMPLATE ").append(template);
        }
        
        if (encoding != null) {
            sql.append(" ENCODING '").append(encoding).append("'");
        }
        
        return sql.toString();
    }
}