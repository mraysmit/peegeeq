package dev.mars.peegeeq.test.containers;

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

/**
 * The Docker images of the containers that PeeGeeQ tests run beside PostgreSQL.
 *
 * <p>Each image is named once, here, so every test runs the same version and a version is
 * changed in one place. The PostgreSQL image is
 * {@link dev.mars.peegeeq.test.PostgreSQLTestConstants#POSTGRES_IMAGE}.
 * {@code SchemaInitializerTestInfrastructureGuardTest} rejects a test that names an image itself.</p>
 */
public final class TestContainerImages {

    /** The TCP proxy that the failover and durability tests place between a client and PostgreSQL. */
    public static final String HAPROXY_IMAGE = "haproxy:2.8-alpine";

    /** The connection pooler that the transaction-pooling tests place in front of PostgreSQL. */
    public static final String PGBOUNCER_IMAGE = "edoburu/pgbouncer:v1.25.2-p0";

    /** The Consul server that the service-discovery and failover-coordination tests run against. */
    public static final String CONSUL_IMAGE = "hashicorp/consul:1.22.1";

    private TestContainerImages() {
        throw new UnsupportedOperationException("This is a constants class and cannot be instantiated");
    }
}
