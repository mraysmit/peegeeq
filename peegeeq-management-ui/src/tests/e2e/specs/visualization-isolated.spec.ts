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
    // 1. Mock the specific query that fetches the tree data
    // Note: The component uses the setupId and eventStoreName from props
    await page.route('**/api/v1/eventstores/test-setup/test-store/events*', async route => {
      console.log('Mocking events response');
      await route.fulfill({ 
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_EVENTS) 
      });
    });

    // 2. Navigate to the test harness
    // We pass setupId and eventStoreName as query params which the harness passes as props
    await page.goto('http://localhost:3000/test-harness?setupId=test-setup&eventStoreName=test-store');
    
    // 3. Enter Correlation ID and Trace
    // The component still requires user interaction to trigger the fetch
    await page.getByPlaceholder(/enter correlation id/i).fill('corr-123');
    await page.getByRole('button', { name: /trace/i }).click();

    // 4. Verify Tree Rendering
    await expect(page.locator('.ant-tree-treenode').filter({ hasText: /RootEvent/ }).first()).toBeVisible();
    await expect(page.locator('.ant-tree-treenode').filter({ hasText: /ChildEvent/ }).first()).toBeVisible();
    await expect(page.locator('.ant-tree-treenode').filter({ hasText: /GrandChildEvent/ }).first()).toBeVisible();
  });
});
