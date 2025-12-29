# Playwright E2E Test Workflow Analysis

## Overview
This document provides a detailed analysis of the correct user interaction workflows as demonstrated by the Playwright E2E tests in the PeeGeeQ Management UI project.

## Test Infrastructure

### Global Setup Flow
1. **TestContainers PostgreSQL** - Starts a reusable PostgreSQL container
   - Image: `postgres:15.13-alpine3.20`
   - Credentials: `peegeeq/peegeeq`
   - Uses `.withReuse()` to persist across test runs
   - Exports connection details to `testcontainers-db.json`

2. **Backend Health Check** - Verifies backend is running at `http://localhost:8080`
   - Checks `/health` endpoint
   - Fails fast if backend is not available

3. **Database Cleanup** - Cleans state via API before tests
   - Fetches all setups: `GET /api/v1/setups`
   - Deletes each setup: `DELETE /api/v1/setups/{setupId}`
   - Ensures clean state for tests

### Test Execution Order
Tests are organized in a specific dependency order:
1. **database-setup.spec.ts** (MUST run first, serial mode)
2. **queues-management.spec.ts** (depends on 'default' setup)
3. **system-integration.spec.ts** (general UI tests)

## Page Object Pattern

### BasePage (Common Functionality)
All page objects extend `BasePage` which provides:

**Navigation:**
- `navigateTo(pageName)` - Click sidebar navigation items
- Uses test IDs: `nav-overview`, `nav-queues`, `nav-consumer-groups`, etc.

**Modal Interactions:**
- `waitForModal(title?)` - Wait for Ant Design modal to appear
- `clickModalButton(buttonText)` - Click modal footer buttons (OK, Cancel, etc.)

**Form Interactions:**
- `fillInput(testId, value)` - Fill Ant Design Input fields
- `selectOption(testId, optionText)` - Select from Ant Design Select dropdowns
  - Clicks the select to open dropdown
  - Clicks option by text content

**Table Operations:**
- `waitForTableLoad(testId)` - Wait for table and loading spinner
- `getTableRowCount(testId)` - Count rows in table

**Notifications:**
- `waitForSuccessMessage(messageText?)` - Wait for success notification
- `waitForErrorMessage(messageText?)` - Wait for error notification

## Critical User Workflows

### 1. Database Setup Creation (Foundation Workflow)

**File:** `database-setup.spec.ts`
**Mode:** Serial (tests run in order)
**Dependency:** None (runs first)

**Workflow:**
```typescript
// Step 1: Navigate to Database Setups page
await page.goto('/')
await databaseSetupsPage.goto()  // Clicks nav-database-setups

// Step 2: Verify clean state
await expect(page.locator('.ant-alert')
  .filter({ hasText: 'No Database Setups Found' })).toBeVisible()

// Step 3: Open create modal
await databaseSetupsPage.clickCreate()
// - Clicks button with text "Create Setup"
// - Waits for modal with title "Create Database Setup"

// Step 4: Fill form using labels
await page.getByLabel('Setup ID').fill('default')
await page.getByLabel('Host').fill('localhost')
await page.getByLabel('Port').fill('5432')
await page.getByLabel('Database Name').fill('e2e_test_db_123')
await page.getByLabel('Username').fill('peegeeq')
await page.getByLabel('Password').fill('peegeeq')
await page.getByLabel('Schema').fill('public')

// Step 5: Submit form
await clickModalButton('Create Setup')

// Step 6: Wait for success
await waitForSuccessMessage()
await page.locator('.ant-modal').waitFor({ state: 'hidden' })

// Step 7: Verify in table
const exists = await databaseSetupsPage.setupExists('default')
expect(exists).toBeTruthy()
```

**Key Insights:**
- Uses `getByLabel()` to find form fields (accessibility-first)
- Waits for modal to close before proceeding
- Verifies success message appears
- Checks table for created setup

### 2. Queue Creation Workflow

**File:** `queues-management.spec.ts`
**Dependency:** Requires 'default' setup from database-setup.spec.ts

**Workflow:**
```typescript
// Step 1: Navigate to Queues page
await queuesPage.goto()  // Clicks nav-queues

// Step 2: Open create modal
await queuesPage.clickCreate()
// - Clicks button with test ID 'create-queue-btn'
// - Waits for modal with title "Create Queue"

// Step 3: Fill form using test IDs
await page.getByTestId('queue-name-input').fill('test-queue-123')
await queuesPage.selectOption('queue-setup-select', 'default')
// - Clicks select with test ID 'queue-setup-select'
// - Clicks option with text content 'default'

// Step 4: Submit
await queuesPage.clickModalButton('OK')

// Step 5: Wait for completion
await page.waitForTimeout(2000)
await queuesPage.clickRefresh()

// Step 6: Verify queue exists
const exists = await queuesPage.queueExists('test-queue-123')
expect(exists).toBeTruthy()
```

**Key Insights:**
- Uses test IDs for form fields (`queue-name-input`, `queue-setup-select`)
- Requires manual refresh after creation
- Uses timeout to wait for async operation
- Cleanup via API: `DELETE /api/v1/setups/{setupId}/queues/{queueName}`

### 3. Queue Details Navigation

**Workflow:**
```typescript
// Step 1: Navigate to queues page
await queuesPage.goto()

// Step 2: Click queue name link
await queuesPage.viewQueueDetails('test-queue')
// - Finds row with text 'test-queue'
// - Clicks first <a> link in that row

// Step 3: Verify navigation
expect(page.url()).toContain('/queues/')
expect(page.url()).toContain('test-queue')

// Step 4: Verify details page loaded
await expect(page.getByTestId('queue-details-tabs')).toBeVisible()
await expect(page.locator('.ant-statistic').first()).toBeVisible()
```

### 4. Search and Filter Workflow

**Search:**
```typescript
// Find search input by placeholder
const searchInput = page.locator('input[placeholder*="Search"]')
await searchInput.fill('test-queue')
await page.waitForTimeout(500)  // Debounce delay
```

**Filter by Type:**
```typescript
// Click first select (type filter)
const typeFilter = page.locator('.ant-select').first()
await typeFilter.click()

// Select multiple options
await page.locator('.ant-select-item-option-content:has-text("Type1")').click()
await page.locator('.ant-select-item-option-content:has-text("Type2")').click()

// Close dropdown
await page.keyboard.press('Escape')
```

### 5. System Integration Workflows

**Application Bootstrap:**
```typescript
// Step 1: Load app
await page.goto('/')
await page.waitForLoadState('networkidle')

// Step 2: Verify layout components
await expect(page.getByTestId('app-layout')).toBeVisible()
await expect(page.getByTestId('app-sidebar')).toBeVisible()
await expect(page.getByTestId('app-header')).toBeVisible()
await expect(page.getByTestId('app-content')).toBeVisible()

// Step 3: Verify header elements
await expect(page.getByTestId('app-logo')).toBeVisible()
await expect(page.getByTestId('page-title')).toContainText('Overview')

// Step 4: Verify navigation menu
await expect(page.getByTestId('app-nav-menu')).toBeVisible()
await expect(page.getByTestId('nav-overview')).toBeVisible()
await expect(page.getByTestId('nav-queues')).toBeVisible()

// Step 5: Verify connection status
await expect(page.getByTestId('connection-status')).toBeVisible()
await expect(page.getByTestId('connection-status')).toContainText('Online')
```

**Navigation Between Pages:**
```typescript
// Navigate using sidebar
await basePage.navigateTo('queues')
await expect(basePage.getPageTitle()).toContainText('Queues')
expect(page.url()).toContain('/queues')

await basePage.navigateTo('consumer-groups')
await expect(basePage.getPageTitle()).toContainText('Consumer Groups')
expect(page.url()).toContain('/consumer-groups')
```

**User Menu Interaction:**
```typescript
// Click user menu button
await page.getByTestId('user-menu-btn').click()

// Dropdown appears
await expect(page.locator('.ant-dropdown-menu')).toBeVisible()
```

## Test ID Conventions

### Layout Components
- `app-layout` - Main layout container
- `app-sidebar` - Sidebar navigation
- `app-header` - Header bar
- `app-content` - Main content area
- `app-logo` - Application logo
- `app-nav-menu` - Navigation menu container

### Navigation Items
- `nav-overview` - Overview page link
- `nav-queues` - Queues page link
- `nav-consumer-groups` - Consumer Groups page link
- `nav-event-stores` - Event Stores page link
- `nav-messages` - Message Browser page link
- `nav-database-setups` - Database Setups page link

### Page Elements
- `page-title` - Current page title in header
- `connection-status` - Backend connection status indicator
- `user-menu-btn` - User menu dropdown button
- `notifications-btn` - Notifications button

### Queue Page Elements
- `queues-table` - Main queues table
- `create-queue-btn` - Create queue button
- `refresh-queues-btn` - Refresh queues button
- `queue-name-input` - Queue name input field (in modal)
- `queue-setup-select` - Setup selection dropdown (in modal)
- `queue-details-tabs` - Tabs on queue details page
- `queue-actions-btn` - Queue actions dropdown button

## Ant Design Component Patterns

### Modal Interactions
```typescript
// Wait for modal
await page.locator('.ant-modal').waitFor({ state: 'visible' })

// Check modal title
await page.locator('.ant-modal-title:has-text("Create Queue")').waitFor()

// Click modal button
await page.locator('.ant-modal-footer button:has-text("OK")').click()

// Wait for modal to close
await page.locator('.ant-modal').waitFor({ state: 'hidden' })
```

### Select Dropdown
```typescript
// Open dropdown
await page.getByTestId('my-select').click()

// Select option by text
await page.locator('.ant-select-item-option-content:has-text("Option 1")').click()
```

### Table Interactions
```typescript
// Find row by text
const row = page.locator('tr:has-text("queue-name")')

// Click link in row
await row.locator('a').first().click()

// Count rows
const rows = table.locator('tbody tr')
const count = await rows.count()
```

### Notifications
```typescript
// Success message
await page.locator('.ant-message-success').waitFor({ state: 'visible' })

// Error message
await page.locator('.ant-message-error').waitFor({ state: 'visible' })
```

### Form Validation
```typescript
// Validation error
await expect(page.locator('.ant-form-item-explain-error').first()).toBeVisible()
```

### Alerts
```typescript
// Alert message
await expect(page.locator('.ant-alert')
  .filter({ hasText: 'No Database Setups Found' })).toBeVisible()
```

## Best Practices from Tests

1. **Always wait for networkidle** after navigation
2. **Use test IDs** for reliable element selection
3. **Use getByLabel()** for form fields (accessibility)
4. **Wait for modals** to fully open/close
5. **Refresh after mutations** when needed
6. **Add debounce delays** for search inputs (500ms)
7. **Use serial mode** for dependent tests
8. **Clean up via API** after test data creation
9. **Verify success messages** after mutations
10. **Check URL changes** after navigation

## Common Wait Patterns

```typescript
// Wait for page load
await page.waitForLoadState('networkidle')

// Wait for element
await element.waitFor({ state: 'visible' })

// Wait for loading spinner to disappear
await page.locator('.ant-spin').waitFor({ state: 'hidden', timeout: 10000 })

// Manual timeout for async operations
await page.waitForTimeout(2000)
```


