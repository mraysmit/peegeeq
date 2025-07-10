package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.MessageConsumer;
import dev.mars.peegeeq.api.MessageProducer;
import dev.mars.peegeeq.db.client.PgClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating native PostgreSQL queue producers and consumers.
 * Uses PostgreSQL's LISTEN/NOTIFY and advisory locks for real-time message processing.
 */
public class PgNativeQueueFactory {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueFactory.class);

    private final PgClientFactory clientFactory;
    private final ObjectMapper objectMapper;
    private final VertxPoolAdapter poolAdapter;

    public PgNativeQueueFactory(PgClientFactory clientFactory) {
        this.clientFactory = clientFactory;
        this.objectMapper = new ObjectMapper();
        this.poolAdapter = new VertxPoolAdapter();
        logger.info("Initialized PgNativeQueueFactory");
    }

    public PgNativeQueueFactory(PgClientFactory clientFactory, ObjectMapper objectMapper) {
        this.clientFactory = clientFactory;
        this.objectMapper = objectMapper;
        this.poolAdapter = new VertxPoolAdapter();
        logger.info("Initialized PgNativeQueueFactory with custom ObjectMapper");
    }
    
    /**
     * Creates a message producer for the specified topic.
     *
     * @param topic The topic to produce messages to
     * @param payloadType The type of message payload
     * @return A message producer instance
     */
    public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
        logger.info("Creating native queue producer for topic: {}", topic);
        return new PgNativeQueueProducer<>(poolAdapter, objectMapper, topic, payloadType);
    }

    /**
     * Creates a message consumer for the specified topic.
     *
     * @param topic The topic to consume messages from
     * @param payloadType The type of message payload
     * @return A message consumer instance
     */
    public <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType) {
        logger.info("Creating native queue consumer for topic: {}", topic);
        return new PgNativeQueueConsumer<>(poolAdapter, objectMapper, topic, payloadType);
    }
    
    /**
     * Closes the factory and releases resources.
     */
    public void close() {
        logger.info("Closing PgNativeQueueFactory");
        if (poolAdapter != null) {
            poolAdapter.close();
        }
    }
}
