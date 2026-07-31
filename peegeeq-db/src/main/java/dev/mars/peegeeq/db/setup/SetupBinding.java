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

package dev.mars.peegeeq.db.setup;

import java.util.Objects;

/**
 * A durable setup binding: the connection coordinates for one remembered setup, as stored in the
 * {@code peegeeq_setup_bindings} registry table.
 *
 * <p>The binding is exactly the knowledge lost when the backend restarts — which setup exists and
 * where to reach it. It deliberately carries <b>no password</b>: only coordinates plus an opaque,
 * nullable {@code credentialRef} resolved through the {@code CredentialProvider} seam at connect
 * time.
 *
 * @param setupId       the setup's durable identity (primary key)
 * @param host          PostgreSQL server host of the setup's database
 * @param port          PostgreSQL server port
 * @param databaseName  the setup's database
 * @param schemaName    the setup's schema within that database
 * @param username      the database user to connect as
 * @param sslEnabled    whether the setup's connection uses SSL
 * @param credentialRef opaque, nullable pointer into the adopter's secret store; never interpreted
 *                      by PeeGeeQ
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-31
 * @version 1.0
 */
public record SetupBinding(
        String setupId,
        String host,
        int port,
        String databaseName,
        String schemaName,
        String username,
        boolean sslEnabled,
        String credentialRef) {

    public SetupBinding {
        Objects.requireNonNull(setupId, "setupId cannot be null");
        Objects.requireNonNull(host, "host cannot be null");
        Objects.requireNonNull(databaseName, "databaseName cannot be null");
        Objects.requireNonNull(schemaName, "schemaName cannot be null");
        Objects.requireNonNull(username, "username cannot be null");
    }
}
