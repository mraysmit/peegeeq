package dev.mars.peegeeq.examples.outbox;

import dev.mars.peegeeq.outbox.OutboxProducer;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating how to participate in an existing transaction.
 * 
 * <p>This pattern is useful for hybrid architectures or when migrating from manual JDBC 
 * to reactive Vert.x, where the application already manages the connection/transaction lifecycle
 * and the OutboxProducer just needs to attach its work to that existing transaction.
 */
public class VertxTransactionParticipationExample {
    private static final Logger logger = LoggerFactory.getLogger(VertxTransactionParticipationExample.class);
    
    private final OutboxProducer<JsonObject> producer;
    private final Pool dbPool;

    public VertxTransactionParticipationExample(OutboxProducer<JsonObject> producer, Pool dbPool) {
        this.producer = producer;
        this.dbPool = dbPool;
    }

    /**
     * Processes an order and participates in a manually managed transaction.
     *
     * @param order the order to process
     * @return a future completing when the transaction commits
     */
    public Future<String> processOrderInExistingTransaction(JsonObject order) {
        // Manually obtain a connection to start a transaction
        return dbPool.withTransaction(connection -> {
            
            // 1. Execute business logic using the connection
            String sql = "INSERT INTO orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)";
            Tuple params = Tuple.of(
                order.getString("id"), 
                order.getString("customerId"), 
                order.getDouble("amount"), 
                "CREATED"
            );

            return connection.preparedQuery(sql).execute(params)
                .compose(result -> {
                    // 2. Generate outbox event
                    JsonObject eventPayload = new JsonObject()
                        .put("eventType", "order.created")
                        .put("orderId", order.getString("id"))
                        .put("timestamp", System.currentTimeMillis());
                    
                    // 3. Participate in the existing transaction
                    // By passing the SqlConnection, the producer appends to the outbox table
                    // without committing; it waits for the caller's transaction to finish.
                    logger.info("Appending outbox event for order {}", order.getString("id"));
                    return producer.sendInExistingTransaction(eventPayload, connection);
                })
                .map(v -> order.getString("id"));
        });
    }
}
