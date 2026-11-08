package dev.mars.peegeeq.servicemanager;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;
import dev.mars.peegeeq.test.vertx.TestCleanup;

/**
 * A service manager that cannot reach Consul says so (finding 11).
 *
 * <p>Before the correction a failed registration was followed by the INFO line "Service Manager
 * registered with Consul", and {@code GET /health} answered {@code "consul":"connected"} because
 * it tested only that a client object existed. The health field now reports whether this service
 * manager is registered.</p>
 *
 * <p>No Consul runs: the service manager is pointed at a local port that nothing listens on.</p>
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class PeeGeeQServiceManagerConsulUnavailableTest {

    private static final int SERVICE_PORT = 19093;

    @Test
    void healthReportsNotRegisteredWhenConsulCannotBeReached(Vertx vertx, VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);
        unusedLocalPort(vertx)
                .compose(closedPort -> vertx.deployVerticle(
                        new PeeGeeQServiceManager(SERVICE_PORT, "localhost", closedPort)))
                .compose(deploymentId -> client.get(SERVICE_PORT, "localhost", "/health").send()
                        .transform(TestCleanup.cleanup(() -> vertx.undeploy(deploymentId))))
                .transform(TestCleanup.cleanup(() -> {
                    client.close();
                    return Future.succeededFuture();
                }))
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject health = response.bodyAsJsonObject();
                    assertEquals("not-registered", health.getString("consul"),
                            "a service manager that Consul refused must not report itself registered: "
                                    + health.encode());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    /** Binds a server to an ephemeral port, closes it, and returns the port nothing now listens on. */
    private static Future<Integer> unusedLocalPort(Vertx vertx) {
        return vertx.createNetServer().connectHandler(socket -> socket.close()).listen(0, "localhost")
                .compose(server -> {
                    int port = server.actualPort();
                    return server.close().map(port);
                });
    }
}
