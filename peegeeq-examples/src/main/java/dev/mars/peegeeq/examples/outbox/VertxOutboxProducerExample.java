package dev.mars.peegeeq.examples.outbox;

import dev.mars.peegeeq.outbox.OutboxProducer;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.TransactionPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating the "Pure Vert.x Pattern" using the OutboxProducer.
 * 
 * <p>This example shows an OrderVerticle that consumes messages from the Vert.x Event Bus,
 * processes them, and publishes an outbox event. It uses {@code sendInOwnTransaction} with 
 * {@code TransactionPropagation.CONTEXT} to automatically manage the transaction lifecycle.
 */
public class VertxOutboxProducerExample extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(VertxOutboxProducerExample.class);
    
    private final OutboxProducer<JsonObject> producer;

    public VertxOutboxProducerExample(OutboxProducer<JsonObject> producer) {
        this.producer = producer;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        // Register event bus handler to listen for new orders
        vertx.eventBus().<JsonObject>consumer("order.create", message -> {
            JsonObject order = message.body();
            
            // Create an event payload from the incoming order
            JsonObject eventPayload = new JsonObject()
                .put("eventType", "order.created")
                .put("orderId", order.getString("id"))
                .put("amount", order.getDouble("amount"))
                .put("status", "PENDING");

            logger.info("Processing order creation for ID: {}", order.getString("id"));

            // Use the outbox producer to send the event in its own transaction.
            // TransactionPropagation.CONTEXT shares the existing Vert.x context transaction
            // or starts a new one if none exists.
            producer.sendInOwnTransaction(
                eventPayload,
                TransactionPropagation.CONTEXT
            )
            .onSuccess(v -> {
                // Transaction committed successfully
                logger.info("Successfully processed order and sent outbox event for ID: {}", order.getString("id"));
                message.reply(new JsonObject()
                    .put("status", "success")
                    .put("orderId", order.getString("id")));
            })
            .onFailure(error -> {
                // Any error (e.g. database constraint) automatically rolls back the transaction
                logger.error("Order creation failed, transaction rolled back", error);
                message.fail(500, error.getMessage());
            });
        });

        logger.info("VertxOutboxProducerExample started, listening on 'order.create'");
        startPromise.complete();
    }
}
