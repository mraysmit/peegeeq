package dev.mars.peegeeq.db.setup;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Simple schema initializer for development.
 * Drops and recreates the entire schema from SQL files.
 * 
 * WARNING: This is for development only. Do NOT use in production.
 */
public class SimpleSchemaInitializer {
    private static final Logger logger = LoggerFactory.getLogger(SimpleSchemaInitializer.class);
    
    private final Pool pool;
    
    public SimpleSchemaInitializer(Pool pool) {
        this.pool = pool;
    }
    
    /**
     * Initialize the database schema by executing all SQL files in order.
     * This will drop existing objects and recreate them.
     */
    public Future<Void> initializeSchema() {
        logger.info("Initializing database schema (development mode - will drop and recreate)");
        
        return loadSchemaScript()
            .compose(sql -> executeSchemaScript(sql))
            .onSuccess(v -> logger.info("Database schema initialized successfully"))
            .onFailure(error -> logger.error("Failed to initialize database schema", error));
    }
    
    private Future<String> loadSchemaScript() {
        return Future.future(promise -> {
            try {
                String sql = loadResourceAsString("/db/migration/V001__Create_Base_Tables.sql");
                if (sql == null) {
                    promise.fail(new RuntimeException("Schema script not found: V001__Create_Base_Tables.sql"));
                } else {
                    promise.complete(sql);
                }
            } catch (Exception e) {
                promise.fail(e);
            }
        });
    }
    
    private Future<Void> executeSchemaScript(String sql) {
        logger.debug("Executing schema script");
        
        // Split into statements, handling CONCURRENTLY indexes specially
        List<String> statements = parseSqlStatements(sql);
        List<String> regularStatements = new ArrayList<>();
        List<String> concurrentStatements = new ArrayList<>();
        
        for (String statement : statements) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                if (trimmed.toUpperCase().contains("CONCURRENTLY")) {
                    concurrentStatements.add(trimmed);
                } else {
                    regularStatements.add(trimmed);
                }
            }
        }
        
        // Execute regular statements first (in a transaction)
        Future<Void> regularFuture = Future.succeededFuture();
        if (!regularStatements.isEmpty()) {
            regularFuture = pool.withTransaction(conn -> {
                Future<Void> chain = Future.succeededFuture();
                for (String statement : regularStatements) {
                    chain = chain.compose(v -> {
                        logger.trace("Executing: {}", statement.substring(0, Math.min(60, statement.length())) + "...");
                        return conn.query(statement).execute().map(rs -> null);
                    });
                }
                return chain;
            });
        }
        
        // Then execute CONCURRENTLY statements outside transaction
        return regularFuture.compose(v -> {
            if (concurrentStatements.isEmpty()) {
                return Future.succeededFuture();
            }
            
            Future<Void> concurrentFuture = Future.succeededFuture();
            for (String statement : concurrentStatements) {
                concurrentFuture = concurrentFuture.compose(v2 -> {
                    logger.debug("Executing concurrent index: {}", statement.substring(0, Math.min(60, statement.length())) + "...");
                    // Get fresh connection to execute outside transaction
                    return pool.getConnection()
                        .compose(conn -> {
                            return conn.query(statement).execute()
                                .map(rs -> (Void) null)
                                .recover(error -> {
                                    // Ignore "already exists" errors
                                    if (error.getMessage().contains("already exists")) {
                                        logger.debug("Index already exists, skipping");
                                        return Future.succeededFuture();
                                    }
                                    return Future.failedFuture(error);
                                })
                                .eventually(() -> conn.close());
                        });
                });
            }
            return concurrentFuture;
        });
    }
    
    private List<String> parseSqlStatements(String content) {
        List<String> statements = new ArrayList<>();
        StringBuilder currentStatement = new StringBuilder();
        
        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);
            
            // Handle single-line comments
            if (c == '-' && i + 1 < content.length() && content.charAt(i + 1) == '-') {
                while (i < content.length() && content.charAt(i) != '\n') {
                    currentStatement.append(content.charAt(i));
                    i++;
                }
                if (i < content.length()) {
                    currentStatement.append(content.charAt(i));
                    i++;
                }
                continue;
            }
            
            // Handle dollar-quoted strings (PostgreSQL functions)
            if (c == '$') {
                currentStatement.append(c);
                i++;
                
                // Find the tag
                StringBuilder tag = new StringBuilder("$");
                while (i < content.length() && content.charAt(i) != '$') {
                    tag.append(content.charAt(i));
                    currentStatement.append(content.charAt(i));
                    i++;
                }
                if (i < content.length()) {
                    tag.append('$');
                    currentStatement.append('$');
                    i++;
                }
                
                // Find the closing tag
                String closingTag = tag.toString();
                while (i < content.length()) {
                    currentStatement.append(content.charAt(i));
                    if (content.substring(i).startsWith(closingTag)) {
                        for (int j = 0; j < closingTag.length(); j++) {
                            if (i < content.length()) {
                                currentStatement.append(content.charAt(i));
                                i++;
                            }
                        }
                        break;
                    }
                    i++;
                }
                continue;
            }
            
            // Handle semicolon (statement terminator)
            if (c == ';') {
                currentStatement.append(c);
                String stmt = currentStatement.toString().trim();
                if (!stmt.isEmpty() && !stmt.equals(";")) {
                    statements.add(stmt);
                }
                currentStatement = new StringBuilder();
                i++;
                continue;
            }
            
            currentStatement.append(c);
            i++;
        }
        
        // Add last statement if any
        String lastStmt = currentStatement.toString().trim();
        if (!lastStmt.isEmpty()) {
            statements.add(lastStmt);
        }
        
        return statements;
    }
    
    private String loadResourceAsString(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            logger.error("Failed to load resource: {}", resourcePath, e);
            return null;
        }
    }
}

