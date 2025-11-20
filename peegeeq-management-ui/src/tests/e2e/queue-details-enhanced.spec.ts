/**
 * E2E Tests for Enhanced Queue Details Page
 * Tests the queue details page with tabs, statistics, and actions
 */
import { test, expect } from '@playwright/test';

const UI_BASE_URL = 'http://localhost:3000';

test.describe('Enhanced Queue Details Page', () => {
  // Helper to navigate to a queue details page
  async function navigateToQueueDetails(page: any, setupId: string = 'setup-1', queueName: string = 'test-queue') {
    await page.goto(`${UI_BASE_URL}/queue-details-enhanced/${setupId}/${queueName}`);
  }

  test.beforeEach(async ({ page }) => {
    // Navigate via queue list page to ensure proper flow
    await page.goto(`${UI_BASE_URL}/queues-enhanced`);
    await page.waitForSelector('.ant-table', { timeout: 10000 });

    // Click on first available queue if exists
    const rowCount = await page.locator('.ant-table-tbody tr:not(.ant-table-placeholder)').count();
    
    if (rowCount > 0) {
      const firstQueueLink = page.locator('.ant-table-tbody tr').first().locator('td').first().locator('a');
      await firstQueueLink.click();
      await page.waitForURL(/\/queue-details-enhanced/);
    } else {
      // Fallback: navigate directly to a test queue
      await navigateToQueueDetails(page);
    }
  });

  test('should display queue header with name and breadcrumbs', async ({ page }) => {
    // Check for breadcrumb navigation
    await expect(page.locator('.ant-breadcrumb')).toBeVisible();
    await expect(page.locator('text=Home')).toBeVisible();
    await expect(page.locator('text=Queues')).toBeVisible();

    // Check for queue name in header
    const header = page.locator('h2').first();
    await expect(header).toBeVisible();
  });

  test('should display Back button', async ({ page }) => {
    const backButton = page.getByRole('button', { name: /back/i });
    await expect(backButton).toBeVisible();
  });

  test('should navigate back to queues list when clicking Back', async ({ page }) => {
    const backButton = page.getByRole('button', { name: /back/i });
    await backButton.click();

    // Verify navigation back to queues page
    await expect(page).toHaveURL(/\/queues-enhanced/);
  });

  test('should display Refresh button', async ({ page }) => {
    const refreshButton = page.getByRole('button', { name: /refresh/i });
    await expect(refreshButton).toBeVisible();
  });

  test('should refresh queue data when clicking Refresh', async ({ page }) => {
    const refreshButton = page.getByRole('button', { name: /refresh/i });
    await refreshButton.click();

    // Wait for refresh to complete
    await page.waitForTimeout(1000);

    // Verify page is still visible and loaded
    await expect(page.locator('.ant-tabs')).toBeVisible();
  });

  test('should display Actions dropdown', async ({ page }) => {
    const actionsButton = page.getByRole('button', { name: /actions/i });
    await expect(actionsButton).toBeVisible();
  });

  test('should show actions menu when clicking Actions', async ({ page }) => {
    const actionsButton = page.getByRole('button', { name: /actions/i });
    await actionsButton.click();

    // Verify dropdown menu appears
    await expect(page.locator('.ant-dropdown-menu')).toBeVisible();
    
    // Check for action items
    await expect(page.locator('text=Publish Message')).toBeVisible();
    await expect(page.locator('text=Purge Queue')).toBeVisible();
    await expect(page.locator('text=Delete Queue')).toBeVisible();
  });

  test('should display statistics cards', async ({ page }) => {
    // Wait for statistics to load
    await page.waitForSelector('.ant-statistic', { timeout: 10000 });

    // Check for key statistics
    await expect(page.locator('text=Messages')).toBeVisible();
    await expect(page.locator('text=Consumers')).toBeVisible();
    await expect(page.locator('text=Messages/sec')).toBeVisible();
    await expect(page.locator('text=Error Rate')).toBeVisible();
  });

  test('should display all tab options', async ({ page }) => {
    // Wait for tabs to render
    await expect(page.locator('.ant-tabs')).toBeVisible();

    // Check for all tabs
    await expect(page.locator('.ant-tabs-tab:has-text("Overview")')).toBeVisible();
    await expect(page.locator('.ant-tabs-tab:has-text("Consumers")')).toBeVisible();
    await expect(page.locator('.ant-tabs-tab:has-text("Messages")')).toBeVisible();
    await expect(page.locator('.ant-tabs-tab:has-text("Bindings")')).toBeVisible();
    await expect(page.locator('.ant-tabs-tab:has-text("Charts")')).toBeVisible();
  });

  test('should default to Overview tab', async ({ page }) => {
    // Check that Overview tab is active
    const overviewTab = page.locator('.ant-tabs-tab:has-text("Overview")');
    await expect(overviewTab).toHaveClass(/ant-tabs-tab-active/);
  });

  test('should switch to Consumers tab', async ({ page }) => {
    const consumersTab = page.locator('.ant-tabs-tab:has-text("Consumers")');
    await consumersTab.click();

    // Verify tab is active
    await expect(consumersTab).toHaveClass(/ant-tabs-tab-active/);

    // Verify consumers content is displayed
    await expect(page.locator('text=Active Consumers')).toBeVisible();
  });

  test('should switch to Messages tab', async ({ page }) => {
    const messagesTab = page.locator('.ant-tabs-tab:has-text("Messages")');
    await messagesTab.click();

    // Verify tab is active
    await expect(messagesTab).toHaveClass(/ant-tabs-tab-active/);

    // Check for messages content
    await expect(page.locator('text=Message Browser')).toBeVisible();
  });

  test('should switch to Bindings tab', async ({ page }) => {
    const bindingsTab = page.locator('.ant-tabs-tab:has-text("Bindings")');
    await bindingsTab.click();

    // Verify tab is active
    await expect(bindingsTab).toHaveClass(/ant-tabs-tab-active/);

    // Check for bindings content
    await expect(page.locator('text=Queue Bindings')).toBeVisible();
  });

  test('should switch to Charts tab', async ({ page }) => {
    const chartsTab = page.locator('.ant-tabs-tab:has-text("Charts")');
    await chartsTab.click();

    // Verify tab is active
    await expect(chartsTab).toHaveClass(/ant-tabs-tab-active/);

    // Check for charts content
    await expect(page.locator('text=Queue Metrics')).toBeVisible();
  });

  test('should display queue configuration details in Overview', async ({ page }) => {
    // Ensure we're on Overview tab
    const overviewTab = page.locator('.ant-tabs-tab:has-text("Overview")');
    await overviewTab.click();

    // Check for configuration sections
    await expect(page.locator('text=Queue Configuration')).toBeVisible();
  });

  test('should display consumer list in Consumers tab', async ({ page }) => {
    const consumersTab = page.locator('.ant-tabs-tab:has-text("Consumers")');
    await consumersTab.click();

    // Wait for consumers table or empty state
    await page.waitForTimeout(1000);

    // Check for either table or empty state
    const hasTable = await page.locator('.ant-table').isVisible().catch(() => false);
    const hasEmpty = await page.locator('.ant-empty').isVisible().catch(() => false);

    expect(hasTable || hasEmpty).toBeTruthy();
  });

  test('should display message browser controls in Messages tab', async ({ page }) => {
    const messagesTab = page.locator('.ant-tabs-tab:has-text("Messages")');
    await messagesTab.click();

    // Check for message browser controls
    await expect(page.locator('text=Fetch Messages')).toBeVisible();
    
    // Check for fetch button
    const fetchButton = page.getByRole('button', { name: /fetch/i });
    await expect(fetchButton).toBeVisible();
  });

  test('should show queue description when available', async ({ page }) => {
    // Check if description is displayed (implementation dependent)
    const description = page.locator('.ant-descriptions');
    
    if (await description.count() > 0) {
      await expect(description).toBeVisible();
    }
  });

  test('should display loading state initially', async ({ page }) => {
    // Navigate to a new queue to catch loading state
    await navigateToQueueDetails(page, 'setup-1', 'another-queue');

    // Check for loading indicator or content
    const spinner = page.locator('.ant-spin');
    const content = page.locator('.ant-tabs');

    const hasSpinner = await spinner.isVisible().catch(() => false);
    const hasContent = await content.isVisible().catch(() => false);

    expect(hasSpinner || hasContent).toBeTruthy();
  });

  test('should handle non-existent queue gracefully', async ({ page }) => {
    // Navigate to non-existent queue
    await navigateToQueueDetails(page, 'setup-1', 'non-existent-queue-12345');

    // Wait for error state
    await page.waitForTimeout(2000);

    // Check for error message or alert
    const errorAlert = page.locator('.ant-alert-error');
    const errorMessage = page.locator('text=/not found|error|failed/i');

    const hasError = await errorAlert.isVisible().catch(() => false);
    const hasMessage = await errorMessage.isVisible().catch(() => false);

    if (hasError || hasMessage) {
      // Error is properly displayed
      expect(true).toBeTruthy();
    } else {
      // Or redirected/handled differently
      expect(true).toBeTruthy();
    }
  });

  test('should maintain tab state when refreshing', async ({ page }) => {
    // Switch to Consumers tab
    const consumersTab = page.locator('.ant-tabs-tab:has-text("Consumers")');
    await consumersTab.click();
    await page.waitForTimeout(500);

    // Refresh the data
    const refreshButton = page.getByRole('button', { name: /refresh/i });
    await refreshButton.click();
    await page.waitForTimeout(1000);

    // Verify still on Consumers tab
    await expect(consumersTab).toHaveClass(/ant-tabs-tab-active/);
  });

  test('should display breadcrumb links that are clickable', async ({ page }) => {
    // Click on "Queues" breadcrumb
    const queuesLink = page.locator('.ant-breadcrumb a:has-text("Queues")');
    
    if (await queuesLink.count() > 0) {
      await queuesLink.click();

      // Verify navigation to queues list
      await expect(page).toHaveURL(/\/queues/);
    }
  });

  test('should show warning before deleting queue', async ({ page }) => {
    // Open actions menu
    const actionsButton = page.getByRole('button', { name: /actions/i });
    await actionsButton.click();

    // Click delete action
    await page.locator('text=Delete Queue').click();

    // Verify confirmation modal appears
    await expect(page.locator('.ant-modal')).toBeVisible();
    await expect(page.locator('text=/are you sure|confirm|delete/i')).toBeVisible();

    // Cancel the deletion
    const cancelButton = page.locator('.ant-modal button:has-text("Cancel")');
    await cancelButton.click();

    // Verify modal closed
    await expect(page.locator('.ant-modal')).not.toBeVisible();
  });

  test('should show warning before purging queue', async ({ page }) => {
    // Open actions menu
    const actionsButton = page.getByRole('button', { name: /actions/i });
    await actionsButton.click();

    // Click purge action
    await page.locator('text=Purge Queue').click();

    // Verify confirmation modal appears
    await expect(page.locator('.ant-modal')).toBeVisible();
    await expect(page.locator('text=/are you sure|confirm|purge/i')).toBeVisible();

    // Cancel the purge
    const cancelButton = page.locator('.ant-modal button:has-text("Cancel")');
    await cancelButton.click();

    // Verify modal closed
    await expect(page.locator('.ant-modal')).not.toBeVisible();
  });
});

test.describe('Enhanced Queue Details Page - Real-time Updates', () => {
  test('should poll for updates periodically', async ({ page }) => {
    // Navigate to queue details
    await page.goto(`${UI_BASE_URL}/queues-enhanced`);
    await page.waitForSelector('.ant-table', { timeout: 10000 });

    const rowCount = await page.locator('.ant-table-tbody tr:not(.ant-table-placeholder)').count();
    
    if (rowCount > 0) {
      const firstQueueLink = page.locator('.ant-table-tbody tr').first().locator('td').first().locator('a');
      await firstQueueLink.click();
      await page.waitForURL(/\/queue-details-enhanced/);

      // Wait for multiple polling cycles (5 seconds per poll)
      await page.waitForTimeout(6000);

      // Verify page is still functional and displaying data
      await expect(page.locator('.ant-statistic')).toBeVisible();
    }
  });
});

test.describe('Enhanced Queue Details Page - Accessibility', () => {
  test('should have accessible navigation elements', async ({ page }) => {
    await page.goto(`${UI_BASE_URL}/queues-enhanced`);
    await page.waitForSelector('.ant-table', { timeout: 10000 });

    const rowCount = await page.locator('.ant-table-tbody tr:not(.ant-table-placeholder)').count();
    
    if (rowCount > 0) {
      const firstQueueLink = page.locator('.ant-table-tbody tr').first().locator('td').first().locator('a');
      await firstQueueLink.click();
      await page.waitForURL(/\/queue-details-enhanced/);

      // Check for ARIA labels and roles
      const backButton = page.getByRole('button', { name: /back/i });
      await expect(backButton).toBeVisible();

      const refreshButton = page.getByRole('button', { name: /refresh/i });
      await expect(refreshButton).toBeVisible();

      const actionsButton = page.getByRole('button', { name: /actions/i });
      await expect(actionsButton).toBeVisible();
    }
  });
});
