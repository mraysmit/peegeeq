package dev.mars.peegeeq.examples.outbox;

import dev.mars.peegeeq.outbox.OutboxProducer;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.TransactionPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Example demonstrating batch operations using TransactionPropagation.CONTEXT.
 * 
 * <p>This pattern allows multiple separate outbox emissions (and business logic operations)
 * to be grouped into a single atomic transaction without passing a connection object around.
 */
public class VertxBatchOutboxExample {
    private static final Logger logger = LoggerFactory.getLogger(VertxBatchOutboxExample.class);
    
    private final OutboxProducer<JsonObject> producer;

    public VertxBatchOutboxExample(OutboxProducer<JsonObject> producer) {
        this.producer = producer;
    }

    /**
     * Processes a batch of orders, emitting individual events and a summary event,
     * all within a single transaction.
     *
     * @param orders the list of orders to process
     * @return a future containing the IDs of successfully processed orders
     */
    public Future<List<String>> processBatchOrders(List<JsonObject> orders) {
        
        JsonObject batchStartedEvent = new JsonObject()
            .put("eventType", "batch.started")
            .put("size", orders.size());

        // Start the transaction context
        return producer.sendInOwnTransaction(batchStartedEvent, TransactionPropagation.CONTEXT)
            .compose(v -> {
                // Process each order in the same transaction
                List<Future<String>> futures = orders.stream()
                    .map(this::processSingleOrder)
                    .collect(Collectors.toList());

                // Wait for all to complete
                return Future.all(futures)
                    .map(compositeFuture -> compositeFuture.<String>list());
            })
            .compose(results -> {
                // Send completion event in the same transaction
                JsonObject batchCompletedEvent = new JsonObject()
                    .put("eventType", "batch.completed")
                    .put("successCount", results.size())
                    .put("orderIds", new JsonArray(results));
                
                return producer.sendInOwnTransaction(batchCompletedEvent, TransactionPropagation.CONTEXT)
                    .map(v -> results);
            })
            .onFailure(error -> {
                // If any order processing fails, or any outbox insert fails,
                // the entire batch is rolled back automatically.
                logger.error("Batch processing failed, entire batch rolled back", error);
            });
    }

    /**
     * Processes a single order. Because it uses TransactionPropagation.CONTEXT,
     * it will automatically join the transaction started by processBatchOrders.
     */
    private Future<String> processSingleOrder(JsonObject order) {
        // ... business logic ...
        
        JsonObject orderEvent = new JsonObject()
            .put("eventType", "order.processed")
            .put("orderId", order.getString("id"));
            
        logger.debug("Processing individual order {}", order.getString("id"));
        
        return producer.sendInOwnTransaction(orderEvent, TransactionPropagation.CONTEXT)
            .map(v -> order.getString("id"));
    }
}
