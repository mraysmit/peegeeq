import { test, expect } from '@playwright/test';
import MOCK_EVENTS from '../../fixtures/visualization-events.json' with { type: 'json' };
import EVENT_SCHEMA from '../../fixtures/event-response-schema.json' with { type: 'json' };
import Ajv from 'ajv';

test.describe('Event Visualization Component Isolated', () => {
  test.setTimeout(30000);

  test('should validate mock data against schema', async () => {
    const ajv = new Ajv();
    const validate = ajv.compile(EVENT_SCHEMA);
    const valid = validate(MOCK_EVENTS);
    
    if (!valid) {
      console.error('Schema validation errors:', validate.errors);
    }
    expect(valid, 'Mock data should match the API schema').toBe(true);
  });

  test('should render causation tree correctly with mocked data', async ({ page }) => {
    // Mock: setups list
    await page.route('**/api/v1/setups', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ setupIds: ['test-setup'] })
      });
    });

    // Mock: event stores list
    await page.route('**/api/v1/management/event-stores', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ eventStores: [{ setup: 'test-setup', name: 'test-store', events: 3 }] })
      });
    });

    // Mock: events query for the causation tree trace
    await page.route('**/api/v1/eventstores/test-setup/test-store/events*', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_EVENTS)
      });
    });

    await page.goto('http://localhost:3000/causation-tree');

    // Select setup
    const setupSelect = page.getByTestId('causation-setup-select');
    await setupSelect.click();
    await page.locator('.ant-select-item-option', { hasText: 'test-setup' }).click();

    // Select event store
    const storeSelect = page.getByTestId('causation-eventstore-select');
    await storeSelect.click();
    await page.locator('.ant-select-item-option', { hasText: 'test-store' }).click();

    // Enter Correlation ID and Trace
    await page.getByPlaceholder(/enter correlation id/i).fill('corr-123');
    await page.getByRole('button', { name: /trace/i }).click();

    // Verify Tree Rendering
    await expect(page.locator('.ant-tree-treenode').filter({ hasText: /RootEvent/ }).first()).toBeVisible();
    await expect(page.locator('.ant-tree-treenode').filter({ hasText: /ChildEvent/ }).first()).toBeVisible();
    await expect(page.locator('.ant-tree-treenode').filter({ hasText: /GrandChildEvent/ }).first()).toBeVisible();
  });
});
