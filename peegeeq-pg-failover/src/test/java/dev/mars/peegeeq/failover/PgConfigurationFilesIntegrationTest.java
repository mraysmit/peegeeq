package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The rule of plan §9.33 RS1b at the reloads of the durability and promotion adapters: a configuration file that
 * PostgreSQL rejects whole (an unknown parameter, check 6) refuses the operation before its {@code ALTER SYSTEM}, and the
 * failure names the file error, instead of a wait for a reload that PostgreSQL never applies. The admission gate's cases
 * are in {@code PgHbaAdmissionGateIntegrationTest}; those of the configuration adapter in
 * {@code PgSqlConfigurationControlIntegrationTest}. One real PostgreSQL that only the test starts; the refusals come
 * before any standby would be needed.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class PgConfigurationFilesIntegrationTest {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    /** The adapters' budget. A refusal must come well before it; the old behaviour waited all of it. */
    private static final Duration BUDGET = Duration.ofSeconds(20);
    private static final PgSupervisedContainer POSTGRES = new PgSupervisedContainer();
    private Vertx vertx;
    private PgCommandRunner runner;
    private String dataDirectory;
    private PgProcessControl process;

    @BeforeAll static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll static void stopContainer() {
        POSTGRES.close();
    }

    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        runner = new LocalCommandRunner(vertx);
        POSTGRES.newDataDirectory(runner).onSuccess(created -> context.verify(() -> {
            dataDirectory = created;
            process = new PgCtlProcessControl(runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL, created,
                POSTGRES.logFile(created), COMMAND_TIMEOUT);
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        POSTGRES.forceStop(runner, dataDirectory).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aPolicyInstallWithAConfigurationFileThatPostgreSqlRejectsFailsAtOnceAndWritesNothing(
            VertxTestContext context) {
        var durability = new PgSqlDurabilityControl(vertx, runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL,
            "psql", dataDirectory, BUDGET);
        process.startPrimary().compose(ignored -> breakConfiguration()).compose(ignored -> {
            long started = System.nanoTime();
            return durability.install(new PgDurabilityPolicy(1, List.of("pg-node-2", "pg-node-3"))).transform(install -> {
                assertTrue(install.failed(), "A policy was installed with a configuration file that PostgreSQL rejects");
                assertInstanceOf(PgDurabilityException.class, install.cause());
                assertTrue(install.cause().getMessage().contains("no_such_parameter"), install.cause().getMessage());
                assertTrue(System.nanoTime() - started < BUDGET.minusSeconds(5).toNanos(), "The install waited out its budget");
                return persisted("synchronous_standby_names");
            });
        }).onSuccess(count -> context.verify(() -> {
            assertEquals("0", count, "A refused install wrote the setting for the next start");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void aFollowWithAConfigurationFileThatPostgreSqlRejectsFailsAtOnceAndWritesNothing(VertxTestContext context) {
        var promotion = new PgSqlPromotionControl(vertx, runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL, "psql",
            dataDirectory, BUDGET);
        var writer = new PgWriterEndpoint("pg-node-1", 5432, dataDirectory + ".pg-node-2.pgpass");
        process.startInRecovery().compose(ignored -> breakConfiguration()).compose(ignored -> {
            long started = System.nanoTime();
            return promotion.follow("pg-node-2", writer).transform(follow -> {
                assertTrue(follow.failed(), "A follow was confirmed with a configuration file that PostgreSQL rejects");
                assertInstanceOf(PgPromotionException.class, follow.cause());
                assertTrue(follow.cause().getMessage().contains("no_such_parameter"), follow.cause().getMessage());
                assertTrue(System.nanoTime() - started < BUDGET.minusSeconds(5).toNanos(), "The follow waited out its budget");
                return persisted("primary_conninfo");
            });
        }).onSuccess(count -> context.verify(() -> {
            assertEquals("0", count, "A refused follow wrote the connection string for the next start");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    /** An unknown parameter: PostgreSQL then applies no change of postgresql.conf (plan §9.33, check 6). */
    private Future<Void> breakConfiguration() {
        return POSTGRES.exec(runner, "sh", "-c", "printf 'no_such_parameter = 1\\n' >> " + dataDirectory
            + "/postgresql.conf").map(result -> {
                assertEquals(0, result.exitCode(), result.output());
                return null;
            });
    }

    /** How many entries for the parameter {@code postgresql.auto.conf} holds, which {@code ALTER SYSTEM} writes. */
    private Future<String> persisted(String parameter) {
        return POSTGRES.sql(runner, "select count(*) from pg_file_settings where name = '" + parameter
                + "' and sourcefile like '%/postgresql.auto.conf'").map(result -> {
            assertEquals(0, result.exitCode(), result.output());
            return result.output().strip();
        });
    }
}
