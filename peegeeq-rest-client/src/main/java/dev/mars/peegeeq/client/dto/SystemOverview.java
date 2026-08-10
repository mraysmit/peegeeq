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

package dev.mars.peegeeq.client.dto;

/**
 * System overview information, mirroring the {@code systemStats} block of
 * GET /api/v1/management/overview.
 *
 * <p>Reshaped 2026-08-09 (metrics-stack review backlog): the previous fields
 * ({@code totalEvents}, {@code deadLetterMessages}, {@code systemStatus},
 * {@code uptimeSeconds}) were never emitted by the endpoint — the record described an
 * imagined contract, and parsing the real payload into it failed on every call. These
 * fields mirror the endpoint one-to-one; change them only together with the endpoint.
 */
public record SystemOverview(
    int totalSetups,
    int totalQueues,
    int totalConsumerGroups,
    int totalEventStores,
    long totalMessages,
    int activeConnections,
    String uptime
) {
}

