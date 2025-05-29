package dev.mars.peegeeq.db.connection;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Specialized connection for PostgreSQL LISTEN/NOTIFY functionality.
 */
public class PgListenerConnection implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PgListenerConnection.class);
    private static final long DEFAULT_POLLING_INTERVAL_MS = 100;

    private final Connection connection;
    private final PGConnection pgConnection;
    private final ScheduledExecutorService pollingExecutor;
    private final CopyOnWriteArrayList<Consumer<PGNotification>> notificationListeners = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;

    /**
     * Creates a new PgListenerConnection.
     *
     * @param connection The database connection
     * @throws SQLException If the connection cannot be unwrapped
     */
    public PgListenerConnection(Connection connection) throws SQLException {
        this.connection = connection;
        this.pgConnection = connection.unwrap(PGConnection.class);
        this.pollingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "pg-notification-poller");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Adds a listener for notifications.
     *
     * @param listener The notification listener
     */
    public void addNotificationListener(Consumer<PGNotification> listener) {
        notificationListeners.add(listener);
    }

    /**
     * Removes a notification listener.
     *
     * @param listener The listener to remove
     */
    public void removeNotificationListener(Consumer<PGNotification> listener) {
        notificationListeners.remove(listener);
    }

    /**
     * Starts polling for notifications.
     */
    public void start() {
        if (running) {
            return;
        }

        running = true;
        pollingExecutor.scheduleAtFixedRate(
            this::pollNotifications,
            0,
            DEFAULT_POLLING_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Listens for notifications on a specific channel.
     *
     * @param channel The channel to listen on
     * @throws SQLException If the LISTEN command fails
     */
    public void listen(String channel) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("LISTEN " + channel);
            LOGGER.info("Listening on channel: " + channel);
        }
    }

    /**
     * Stops listening for notifications on a specific channel.
     *
     * @param channel The channel to stop listening on
     * @throws SQLException If the UNLISTEN command fails
     */
    public void unlisten(String channel) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("UNLISTEN " + channel);
            LOGGER.info("Stopped listening on channel: " + channel);
        }
    }

    private void pollNotifications() {
        try {
            PGNotification[] notifications = pgConnection.getNotifications();
            if (notifications != null && notifications.length > 0) {
                for (PGNotification notification : notifications) {
                    for (Consumer<PGNotification> listener : notificationListeners) {
                        try {
                            listener.accept(notification);
                        } catch (Exception e) {
                            LOGGER.warn("Error processing notification", e);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Error polling for notifications", e);
        }
    }

    @Override
    public void close() throws Exception {
        running = false;
        if (pollingExecutor != null) {
            pollingExecutor.shutdown();
            try {
                if (!pollingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    pollingExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                pollingExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
