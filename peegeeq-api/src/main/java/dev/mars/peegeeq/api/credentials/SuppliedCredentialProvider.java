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

package dev.mars.peegeeq.api.credentials;

import io.vertx.core.Future;

/**
 * The core default {@link CredentialProvider}: supplied-at-connect, zero dependencies.
 *
 * <p>In this mode the password travels on the request itself (exactly what
 * {@code PgConnectOptions.setPassword(...)} consumes) — nothing external is contacted and nothing
 * is stored, so there is nothing this provider can look up. Any attempt to resolve a credential
 * reference therefore fails with an actionable message rather than returning a fabricated value.
 *
 * <p>Consequence for auto-reload: a persisted binding can only be re-established through this
 * provider if the caller supplies the password some other way; bindings whose passwords require a
 * secret store need an adopter-provided {@link CredentialProvider} configured explicitly.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-31
 * @version 1.0
 */
public final class SuppliedCredentialProvider implements CredentialProvider {

    @Override
    public Future<String> resolvePassword(String credentialRef) {
        String refDescription = (credentialRef != null) ? "credential reference '" + credentialRef + "'" : "a null credential reference";
        return Future.failedFuture(new IllegalStateException(
                "The supplied-at-connect credential provider cannot resolve " + refDescription
                        + " — supply the password on the connect request, or configure a CredentialProvider "
                        + "for your secret store explicitly."));
    }
}
