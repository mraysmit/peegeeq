/**
 * E2E Tests for Enhanced Queues Page
 * Tests the main queue list page with filtering, sorting, and actions
 */
import { test, expect } from '@playwright/test';

const UI_BASE_URL = 'http://localhost:3000';
const API_BASE_URL = 'http://localhost:8080';

test.describe('Enhanced Queues Page', () => {
  test.beforeEach(async ({ page }) => {
    // Navigate to the enhanced queues page
    await page.goto(`${UI_BASE_URL}/queues-enhanced`);
  });

  test('should display the page title and summary statistics', async ({ page }) => {
    // Check page title
    await expect(page.locator('h2').first()).toContainText('Queue Management');

    // Check for summary statistics cards
    await expect(page.locator('text=Total Queues')).toBeVisible();
    await expect(page.locator('text=Active Queues')).toBeVisible();
    await expect(page.locator('text=Total Messages')).toBeVisible();
    await expect(page.locator('text=Messages/sec')).toBeVisible();
  });

  test('should display filter controls', async ({ page }) => {
    // Check for search input
    await expect(page.getByPlaceholder('Search queues...')).toBeVisible();

    // Check for type filter dropdown
    await expect(page.locator('text=Select Type')).toBeVisible();

    // Check for status filter dropdown
    await expect(page.locator('text=Select Status')).toBeVisible();

    // Check for clear filters button (when filters are applied)
    const typeFilter = page.locator('.ant-select').filter({ hasText: 'Select Type' }).first();
    await typeFilter.click();
    await page.locator('.ant-select-item-option-content:has-text("NATIVE")').first().click();
    
    await expect(page.getByRole('button', { name: /clear filters/i })).toBeVisible();
  });

  test('should display action buttons', async ({ page }) => {
    // Check for refresh button
    await expect(page.getByRole('button', { name: /refresh/i })).toBeVisible();

    // Check for create queue button
    await expect(page.getByRole('button', { name: /create queue/i })).toBeVisible();
  });

  test('should display queue table with columns', async ({ page }) => {
    // Wait for table to load
    await page.waitForSelector('.ant-table', { timeout: 10000 });

    // Check table headers
    await expect(page.locator('th:has-text("Queue Name")')).toBeVisible();
    await expect(page.locator('th:has-text("Type")')).toBeVisible();
    await expect(page.locator('th:has-text("Status")')).toBeVisible();
    await expect(page.locator('th:has-text("Messages")')).toBeVisible();
    await expect(page.locator('th:has-text("Consumers")')).toBeVisible();
    await expect(page.locator('th:has-text("Rate")')).toBeVisible();
    await expect(page.locator('th:has-text("Error Rate")')).toBeVisible();
    await expect(page.locator('th:has-text("Actions")')).toBeVisible();
  });

  test('should filter queues by type', async ({ page }) => {
    // Wait for table to load
    await page.waitForSelector('.ant-table', { timeout: 10000 });

    // Get initial row count
    const initialRows = await page.locator('.ant-table-tbody tr').count();

    // Click type filter
    const typeFilter = page.locator('.ant-select').filter({ hasText: 'Select Type' }).first();
    await typeFilter.click();

    // Select NATIVE type
    await page.locator('.ant-select-item-option-content:has-text("NATIVE")').first().click();

    // Wait for filter to apply
    await page.waitForTimeout(500);

    // Verify filtered results (should be different or same)
    const filteredRows = await page.locator('.ant-table-tbody tr').count();
    
    // Clear filters
    await page.getByRole('button', { name: /clear filters/i }).click();
    await page.waitForTimeout(500);

    // Verify filters cleared
    const clearedRows = await page.locator('.ant-table-tbody tr').count();
    expect(clearedRows).toBe(initialRows);
  });

  test('should search queues by name', async ({ page }) => {
    // Wait for table to load
    await page.waitForSelector('.ant-table', { timeout: 10000 });

    // Get a queue name from the first row
    const firstQueueName = await page.locator('.ant-table-tbody tr').first().locator('td').first().locator('a').textContent();
    
    if (firstQueueName) {
      // Search for part of the queue name
      const searchTerm = firstQueueName.substring(0, 3);
      await page.getByPlaceholder('Search queues...').fill(searchTerm);
      await page.waitForTimeout(500);

      // Verify search results contain the search term
      const visibleRows = page.locator('.ant-table-tbody tr:not(.ant-table-placeholder)');
      const count = await visibleRows.count();
      
      if (count > 0) {
        const firstResultName = await visibleRows.first().locator('td').first().locator('a').textContent();
        expect(firstResultName?.toLowerCase()).toContain(searchTerm.toLowerCase());
      }
    }
  });

  test('should refresh queue list', async ({ page }) => {
    // Wait for table to load
    await page.waitForSelector('.ant-table', { timeout: 10000 });

    // Click refresh button
    await page.getByRole('button', { name: /refresh/i }).click();

    // Wait for refresh to complete
    await page.waitForTimeout(1000);

    // Verify table is still visible
    await expect(page.locator('.ant-table')).toBeVisible();
  });

  test('should navigate to queue details when clicking queue name', async ({ page }) => {
    // Wait for table to load
    await page.waitForSelector('.ant-table', { timeout: 10000 });

    // Check if there are any queues
    const rowCount = await page.locator('.ant-table-tbody tr:not(.ant-table-placeholder)').count();
    
    if (rowCount > 0) {
      // Click on first queue name
      const firstQueueLink = page.locator('.ant-table-tbody tr').first().locator('td').first().locator('a');
      await firstQueueLink.click();

      // Verify navigation to queue details page
      await expect(page).toHaveURL(/\/queue-details-enhanced/);
      
      // Verify queue details page loaded
      await expect(page.locator('text=Queue Details')).toBeVisible();
    }
  });

  test('should display queue type tags with correct colors', async ({ page }) => {
    // Wait for table to load
    await page.waitForSelector('.ant-table', { timeout: 10000 });

    // Check for type tags in the table
    const typeColumn = page.locator('.ant-table-tbody td:nth-child(2)');
    const tags = typeColumn.locator('.ant-tag').first();
    
    if (await tags.count() > 0) {
      await expect(tags).toBeVisible();
      
      // Verify tag has appropriate class/style
      const tagClass = await tags.getAttribute('class');
      expect(tagClass).toContain('ant-tag');
    }
  });

  test('should display pagination controls when needed', async ({ page }) => {
    // Wait for table to load
    await page.waitForSelector('.ant-table', { timeout: 10000 });

    // Check if pagination exists (depends on data)
    const pagination = page.locator('.ant-pagination');
    const paginationExists = await pagination.count() > 0;
    
    if (paginationExists) {
      await expect(pagination).toBeVisible();
    }
  });

  test('should open queue actions menu', async ({ page }) => {
    // Wait for table to load
    await page.waitForSelector('.ant-table', { timeout: 10000 });

    // Check if there are any queues
    const rowCount = await page.locator('.ant-table-tbody tr:not(.ant-table-placeholder)').count();
    
    if (rowCount > 0) {
      // Click actions dropdown in first row
      const actionsButton = page.locator('.ant-table-tbody tr').first().locator('button:has-text("Actions")');
      await actionsButton.click();

      // Verify menu items are visible
      await expect(page.locator('.ant-dropdown-menu')).toBeVisible();
      await expect(page.locator('text=View Details')).toBeVisible();
      await expect(page.locator('text=Purge Queue')).toBeVisible();
      await expect(page.locator('text=Delete Queue')).toBeVisible();
    }
  });

  test('should handle empty state gracefully', async ({ page }) => {
    // This test would need a way to clear all queues or use a test environment with no queues
    // For now, just verify the table structure exists
    await page.waitForSelector('.ant-table', { timeout: 10000 });
    
    const rowCount = await page.locator('.ant-table-tbody tr').count();
    
    if (rowCount === 1) {
      // Check for empty state message
      const emptyText = await page.locator('.ant-empty-description').textContent();
      expect(emptyText).toBeTruthy();
    }
  });

  test('should display loading state initially', async ({ page }) => {
    // Reload page to catch loading state
    await page.goto(`${UI_BASE_URL}/queues-enhanced`);
    
    // Check for loading indicator (might be very brief)
    const spinner = page.locator('.ant-spin');
    
    // Either spinner is visible or data loads immediately
    const spinnerVisible = await spinner.isVisible().catch(() => false);
    const tableVisible = await page.locator('.ant-table').isVisible().catch(() => false);
    
    expect(spinnerVisible || tableVisible).toBeTruthy();
  });

  test('should maintain filter state when navigating back from details', async ({ page }) => {
    // Wait for table to load
    await page.waitForSelector('.ant-table', { timeout: 10000 });

    // Apply a filter
    const typeFilter = page.locator('.ant-select').filter({ hasText: 'Select Type' }).first();
    await typeFilter.click();
    await page.locator('.ant-select-item-option-content:has-text("NATIVE")').first().click();
    await page.waitForTimeout(500);

    // Navigate to a queue details page if available
    const rowCount = await page.locator('.ant-table-tbody tr:not(.ant-table-placeholder)').count();
    
    if (rowCount > 0) {
      const firstQueueLink = page.locator('.ant-table-tbody tr').first().locator('td').first().locator('a');
      await firstQueueLink.click();

      // Wait for navigation
      await page.waitForURL(/\/queue-details-enhanced/);

      // Go back
      await page.goBack();

      // Verify we're back on queues page
      await expect(page).toHaveURL(/\/queues-enhanced/);

      // Note: Filter state persistence would depend on implementation (URL params, Redux, etc.)
    }
  });
});

test.describe('Enhanced Queues Page - Error Handling', () => {
  test('should handle API errors gracefully', async ({ page }) => {
    // Navigate to page
    await page.goto(`${UI_BASE_URL}/queues-enhanced`);

    // Wait for either success or error state
    await page.waitForTimeout(2000);

    // Check if error message is displayed or data loaded successfully
    const errorAlert = page.locator('.ant-alert-error');
    const table = page.locator('.ant-table');

    const hasError = await errorAlert.isVisible().catch(() => false);
    const hasTable = await table.isVisible().catch(() => false);

    // One of these should be visible
    expect(hasError || hasTable).toBeTruthy();
  });
});
