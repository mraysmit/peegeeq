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

package dev.mars.peegeeq.api.setup;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Per-entry outcome of {@link DatabaseSetupService#reloadPersistedSetups()}.
 *
 * <p>Reload is resilient by contract: one binding failing to reconnect (database unreachable,
 * schema gone, credential unresolvable) must not abort the reload of the others. That makes the
 * skipped entries data, not silence — every persisted binding appears in exactly one of the two
 * collections, so a caller can always account for the whole registry.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-31
 * @version 1.0
 */
public final class SetupReloadReport {

    private final List<String> reconnectedSetupIds;
    private final Map<String, String> skippedSetups;

    /**
     * @param reconnectedSetupIds setup ids re-established as active by this reload
     * @param skippedSetups       setup ids that could not be re-established, each mapped to the
     *                            reason it was skipped
     */
    @JsonCreator
    public SetupReloadReport(@JsonProperty("reconnectedSetupIds") List<String> reconnectedSetupIds,
                             @JsonProperty("skippedSetups") Map<String, String> skippedSetups) {
        this.reconnectedSetupIds = (reconnectedSetupIds == null) ? List.of() : List.copyOf(reconnectedSetupIds);
        this.skippedSetups = (skippedSetups == null) ? Map.of() : Map.copyOf(skippedSetups);
    }

    public List<String> getReconnectedSetupIds() { return reconnectedSetupIds; }
    public Map<String, String> getSkippedSetups() { return skippedSetups; }
}
