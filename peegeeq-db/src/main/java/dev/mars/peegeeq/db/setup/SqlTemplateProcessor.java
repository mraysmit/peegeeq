package dev.mars.peegeeq.db.setup;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class SqlTemplateProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SqlTemplateProcessor.class);

    /**
     * Apply SQL template using reactive Vert.x SqlConnection.
     * <p>
     * CRITICAL: Vert.x PostgreSQL client's .query().execute() only executes the FIRST statement
     * in multi-statement SQL. To handle this, templates are organized as DIRECTORIES containing
     * numbered SQL files (01-xxx.sql, 02-xxx.sql, etc.) that are executed in sequence.
     * <p>
     * This approach is simpler and more reliable than parsing multi-statement SQL.
     *
     * @param connection The reactive SQL connection
     * @param templateDir The name of the template directory to load (or single file for backward compatibility)
     * @param parameters The parameters to substitute in the templates
     * @return Future that completes when all templates have been applied
     */
    public Future<Void> applyTemplateReactive(SqlConnection connection, String templateDir,
                                            Map<String, String> parameters) {
        logger.error("Applying template directory: {}", templateDir);

        try {
            // Load all SQL files from directory (or single file for backward compatibility)
            List<String> sqlFiles = loadTemplateFiles(templateDir);
            
            if (sqlFiles.isEmpty()) {
                logger.error("No SQL files found in template: {}", templateDir);
                return Future.succeededFuture();
            }

            logger.error("Template {} contains {} SQL files", templateDir, sqlFiles.size());

            // Execute SQL files sequentially using Future composition
            Future<Void> chain = Future.succeededFuture();
            int fileNum = 0;
            for (String sqlContent : sqlFiles) {
                fileNum++;
                final int currentFileNum = fileNum;
                final String processedSql = processTemplate(sqlContent, parameters);
                
                chain = chain.compose(v -> {
                    logger.error("Executing SQL file {}/{} for template: {}", 
                        currentFileNum, sqlFiles.size(), templateDir);
                    return connection.query(processedSql).execute()
                        .map(rowSet -> {
                            logger.error("SQL file {}/{} executed successfully", 
                                currentFileNum, sqlFiles.size());
                            return (Void) null;
                        })
                        .recover(throwable -> {
                            logger.error("Failed to execute SQL file {}/{} for template: {} - Error: {}", 
                                currentFileNum, sqlFiles.size(), templateDir, throwable.getMessage());
                            return Future.failedFuture(new RuntimeException(
                                "Failed to execute SQL file " + currentFileNum + " of template: " + templateDir, 
                                throwable));
                        });
                });
            }

            return chain.map(v -> {
                logger.error("Successfully executed all {} SQL files for template: {}", 
                    sqlFiles.size(), templateDir);
                return (Void) null;
            });
        } catch (IOException e) {
            logger.error("Failed to load template: {} - Error: {}", templateDir, e.getMessage(), e);
            return Future.failedFuture(new RuntimeException("Failed to load template: " + templateDir, e));
        }
    }

    /**
     * Load template files from directory or single file.
     * For directories, looks for a .manifest file listing the SQL files in order.
     * If no manifest exists, tries to load as single file for backward compatibility.
     */
    private List<String> loadTemplateFiles(String templateDir) throws IOException {
        List<String> sqlContents = new ArrayList<>();
        String basePath = "/db/templates/";
        
        // Try to load manifest file first (for directories with multiple SQL files)
        String manifestPath = basePath + templateDir + "/.manifest";
        try (var manifestStream = getClass().getResourceAsStream(manifestPath)) {
            if (manifestStream != null) {
                // Read manifest file line by line
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(manifestStream, StandardCharsets.UTF_8))) {
                    List<String> fileNames = reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .collect(Collectors.toList());
                    
                    logger.debug("Found manifest for {} with {} files", templateDir, fileNames.size());
                    
                    for (String fileName : fileNames) {
                        String filePath = basePath + templateDir + "/" + fileName;
                        String content = loadFile(filePath);
                        sqlContents.add(content);
                    }
                    return sqlContents;
                }
            }
        } catch (Exception e) {
            logger.debug("No manifest found for {}, trying as single file", templateDir);
        }
        
        // No manifest - try to load as single file
        String singleFilePath = basePath + templateDir;
        try (var inputStream = getClass().getResourceAsStream(singleFilePath)) {
            if (inputStream != null) {
                String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                sqlContents.add(content);
                return sqlContents;
            }
        }
        
        throw new IOException("Template not found as directory or file: " + templateDir);
    }

    /**
     * Load a single file from resources.
     */
    private String loadFile(String filePath) throws IOException {
        try (var inputStream = getClass().getResourceAsStream(filePath)) {
            if (inputStream == null) {
                throw new IOException("File not found: " + filePath);
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