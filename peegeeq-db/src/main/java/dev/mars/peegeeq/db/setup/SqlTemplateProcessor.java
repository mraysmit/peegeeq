package dev.mars.peegeeq.db.setup;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class SqlTemplateProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SqlTemplateProcessor.class);



    /**
     * Apply SQL template using reactive Vert.x SqlConnection.
     *
     * @param connection The reactive SQL connection
     * @param templateName The name of the template file to load
     * @param parameters The parameters to substitute in the template
     * @return Future that completes when the template has been applied
     */
    public Future<Void> applyTemplateReactive(SqlConnection connection, String templateName,
                                            Map<String, String> parameters) {
        logger.info("SqlTemplateProcessor.applyTemplateReactive called for template: {}", templateName);

        try {
            String templateContent = loadTemplate(templateName);
            String processedSql = processTemplate(templateContent, parameters);

            logger.info("About to execute processed SQL for template: {}", templateName);
            logger.debug("Processed SQL content: {}", processedSql);

            return connection.query(processedSql).execute()
                .map(rowSet -> {
                    logger.info("Successfully executed SQL for template: {}", templateName);
                    return (Void) null;
                })
                .recover(throwable -> {
                    logger.error("Failed to execute SQL for template: {} - Error: {}", templateName, throwable.getMessage());
                    logger.debug("Failed SQL content: {}", processedSql);
                    return Future.failedFuture(new RuntimeException("Failed to execute template: " + templateName, throwable));
                });
        } catch (IOException e) {
            logger.error("Failed to load template: {} - Error: {}", templateName, e.getMessage());
            return Future.failedFuture(new RuntimeException("Failed to load template: " + templateName, e));
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