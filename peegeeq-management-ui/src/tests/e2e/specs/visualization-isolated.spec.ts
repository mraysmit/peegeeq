import { test, expect } from '@playwright/test';

test.describe('Event Visualization Component Isolated', () => {
  test.setTimeout(30000);

  const MOCK_EVENTS = {
    "message": "Events retrieved successfully",
    "eventStoreName": "mock-store",
    "setupId": "default",
    "eventCount": 3,
    "limit": 1000,
    "offset": 0,
    "hasMore": false,
    "events": [
      {
        "eventType": "GrandChildEvent",
        "eventData": { "msg": "test" },
        "transactionTime": 1767455565.171568,
        "correlationId": "corr-123",
        "causationId": "child-id",
        "aggregateId": "agg-1",
        "version": 1,
        "eventId": "grandchild-id",
        "validTime": 1767455565.171568
      },
      {
        "eventType": "ChildEvent",
        "eventData": { "msg": "test" },
        "transactionTime": 1767455550.683065,
        "correlationId": "corr-123",
        "causationId": "root-id",
        "aggregateId": "agg-1",
        "version": 1,
        "eventId": "child-id",
        "validTime": 1767455550.683065
      },
      {
        "eventType": "RootEvent",
        "eventData": { "msg": "test" },
        "transactionTime": 1767455536.189212,
        "correlationId": "corr-123",
        "causationId": null,
        "aggregateId": "agg-1",
        "version": 1,
        "eventId": "root-id",
        "validTime": 1767455536.189212
      }
    ]
  };

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
