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
 * Resolves a database password from an opaque credential reference at connect time.
 *
 * <p>PeeGeeQ stores no credential material. The durable binding registry holds connection
 * coordinates plus an optional, opaque {@code credential_ref}; the password itself is resolved
 * through this seam when a connection is made and is never persisted by PeeGeeQ.
 *
 * <p>The reference is passed through <b>verbatim and never interpreted</b> by PeeGeeQ core — its
 * format is a private contract between the adopter's provider implementation and their secret
 * store (Vault path, cloud secret ARN, Kubernetes secret name, ...). Authentication to that store
 * is the provider's concern and should use the runtime's ambient identity, never a secret held by
 * PeeGeeQ.
 *
 * <p>Provider selection is explicit, instance-scoped configuration (constructor-injected), never
 * an environment or system-property sweep. Core ships exactly one implementation,
 * {@link SuppliedCredentialProvider} (supplied-at-connect, zero dependencies), which cannot
 * resolve references and fails clearly — callers either supply the password on the request or
 * configure a real provider.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-31
 * @version 1.0
 */
@FunctionalInterface
public interface CredentialProvider {

    /**
     * Resolves the password for the given opaque credential reference.
     *
     * @param credentialRef the opaque reference stored alongside the binding coordinates; may be
     *                      {@code null} when the binding was persisted without one
     * @return a Future completing with the resolved password, or failing when the reference
     *         cannot be resolved — the failure must carry an actionable message and must never
     *         be swallowed by the caller
     */
    Future<String> resolvePassword(String credentialRef);
}
