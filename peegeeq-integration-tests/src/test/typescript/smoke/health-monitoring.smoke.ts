/**
 * Health Monitoring E2E Smoke Tests (TypeScript)
 * 
 * Verifies health monitoring endpoints work correctly from TypeScript client.
 */
import { describe, it, expect } from 'vitest';

const API_BASE_URL = process.env.PEEGEEQ_API_URL || 'http://localhost:8080';

interface HealthResponse {
  status?: string;
  healthy?: boolean;
  timestamp?: string;
}

interface ManagementOverview {
  systemStats?: object;
  queueSummary?: object;
  consumerGroupSummary?: object;
  eventStoreSummary?: object;
  recentActivity?: Array<object>;
  timestamp?: string;
}

describe('Health Monitoring Smoke Tests (TypeScript)', () => {

  it('should return server health status', async () => {
    const response = await fetch(`${API_BASE_URL}/api/v1/health`);

    expect(response.ok).toBe(true);

    const health: HealthResponse = await response.json();

    expect(health.status === 'UP' || health.healthy === true).toBe(true);
    console.log(`Health status: ${JSON.stringify(health)}`);
  });

  it('should return management overview', async () => {
    const response = await fetch(`${API_BASE_URL}/api/v1/management/overview`);

    expect(response.ok).toBe(true);

    const overview: ManagementOverview = await response.json();

    expect(overview.timestamp || overview.systemStats).toBeDefined();
    console.log(`Management overview retrieved`);
  });

  it('should list all setups', async () => {
    const response = await fetch(`${API_BASE_URL}/api/v1/setups`);

    expect(response.ok).toBe(true);

    const body = await response.text();
    expect(body.startsWith('[') || body.startsWith('{')).toBe(true);
    console.log(`Setups list retrieved`);
  });

  it('should return metrics endpoint', async () => {
    const response = await fetch(`${API_BASE_URL}/api/v1/metrics`);

    // Metrics endpoint may return 200 or 404 depending on configuration
    expect(response.status === 200 || response.status === 404).toBe(true);

    if (response.ok) {
      console.log(`Metrics endpoint available`);
    } else {
      console.log(`Metrics endpoint not configured (expected)`);
    }
  });
});

