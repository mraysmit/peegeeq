package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.api.database.ConnectionProvider;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.database.NoOpMetricsProvider;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

final class TestDatabaseService implements DatabaseService {

    private static final ConnectionProvider CONNECTION_PROVIDER = new ConnectionProvider() {
        @Override
        public Future<Pool> getReactivePool(String clientId) {
            return Future.failedFuture("not available in test stub");
        }

        @Override
        public Future<SqlConnection> getConnection(String clientId) {
            return Future.failedFuture("not available in test stub");
        }

        @Override
        public <T> Future<T> withConnection(String clientId, Function<SqlConnection, Future<T>> operation) {
            return Future.failedFuture("not available in test stub");
        }

        @Override
        public <T> Future<T> withTransaction(String clientId, Function<SqlConnection, Future<T>> operation) {
            return Future.failedFuture("not available in test stub");
        }

        @Override
        public boolean hasClient(String clientId) {
            return false;
        }

        @Override
        public Future<Boolean> isHealthy() {
            return Future.succeededFuture(false);
        }

        @Override
        public Future<Boolean> isClientHealthy(String clientId) {
            return Future.succeededFuture(false);
        }

        @Override
        public void close() {
        }
    };

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    @Override
    public boolean isHealthy() {
        return false;
    }

    @Override
    public ConnectionProvider getConnectionProvider() {
        return CONNECTION_PROVIDER;
    }

    @Override
    public MetricsProvider getMetricsProvider() {
        return NoOpMetricsProvider.INSTANCE;
    }

    @Override
    public SubscriptionService getSubscriptionService() {
        return null;
    }

    @Override
    public CompletableFuture<Void> runMigrations() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Boolean> performHealthCheck() {
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public void close() {
    }

    @Override
    public Vertx getVertx() {
        return null;
    }

    @Override
    public Pool getPool() {
        return null;
    }

    @Override
    public PgConnectOptions getConnectOptions() {
        return null;
    }
}