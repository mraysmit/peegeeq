# Test-Driven Development for UI Workflows

## The Problem with Traditional UI Testing

Traditional approach:
1. Build the UI
2. Manually test it
3. Write automated tests
4. Tests break when UI changes

**This is backwards!** Tests should drive the UI design, not follow it.

## TDD Approach for UI Development

### The Red-Green-Refactor Cycle for UI

1. **RED** - Write a failing test that describes the desired user workflow
2. **GREEN** - Build the minimal UI to make the test pass
3. **REFACTOR** - Improve the UI without breaking the test

### Key Principle: Design Contracts First

Before writing ANY code (UI or tests), define:
1. **User workflow** - What the user wants to accomplish
2. **UI contract** - What elements will exist and how they behave
3. **API contract** - What backend endpoints are needed
4. **Test contract** - What the test will verify

## Step-by-Step TDD Workflow

### Phase 1: Define the Workflow Contract

**Document the workflow BEFORE building anything:**

```markdown
## Workflow: Create Database Setup

### User Story
As a user, I want to create a database setup so I can connect to PostgreSQL.

### UI Contract
- Page: `/database-setups`
- Button: "Create Setup" (opens modal)
- Modal: "Create Database Setup"
- Form fields:
  - Setup ID (text input, required, unique)
  - Host (text input, required)
  - Port (number input, required, default: 5432)
  - Database Name (text input, required)
  - Username (text input, required)
  - Password (password input, required)
  - Schema (text input, optional, default: "public")
- Submit button: "Create Setup"
- Success: Modal closes, success message, setup appears in table
- Error: Modal stays open, error message shown

### API Contract
POST /api/v1/setups
Request: { setupId, host, port, databaseName, username, password, schema }
Response: 201 Created or 400 Bad Request

### Test Contract
1. Navigate to /database-setups
2. Click "Create Setup" button
3. Fill form with valid data
4. Submit form
5. Verify success message
6. Verify setup in table
```

### Phase 2: Write the Failing Test

**Write the test based on the contract (UI doesn't exist yet):**

```typescript
// database-setup.spec.ts
import { test, expect } from '@playwright/test'

test.describe('Database Setup - TDD', () => {
  test('should create database setup through UI', async ({ page }) => {
    // Step 1: Navigate to page
    await page.goto('/database-setups')

    // Step 2: Click create button (DOESN'T EXIST YET - TEST WILL FAIL)
    await page.getByRole('button', { name: /create setup/i }).click()

    // Step 3: Verify modal opened (DOESN'T EXIST YET)
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Create Database Setup' })).toBeVisible()

    // Step 4: Fill form (FIELDS DON'T EXIST YET)
    await page.getByLabel('Setup ID').fill('test-setup')
    await page.getByLabel('Host').fill('localhost')
    await page.getByLabel('Port').fill('5432')
    await page.getByLabel('Database Name').fill('testdb')
    await page.getByLabel('Username').fill('postgres')
    await page.getByLabel('Password').fill('password')

    // Step 5: Submit (BUTTON DOESN'T EXIST YET)
    await page.getByRole('button', { name: 'Create Setup' }).click()

    // Step 6: Verify success (SUCCESS MESSAGE DOESN'T EXIST YET)
    await expect(page.getByText(/setup created successfully/i)).toBeVisible()

    // Step 7: Verify in table (TABLE DOESN'T EXIST YET)
    await expect(page.getByRole('row', { name: /test-setup/i })).toBeVisible()
  })
})
```

**Run the test - it WILL FAIL:**
```bash
npx playwright test database-setup.spec.ts
# ❌ Error: locator.click: Target closed
# ❌ Button "Create Setup" not found
```

### Phase 3: Build Minimal UI to Pass Test

**Now build the UI to satisfy the test contract:**

```tsx
// DatabaseSetupsPage.tsx
export function DatabaseSetupsPage() {
  const [isModalOpen, setIsModalOpen] = useState(false)
  const [setups, setSetups] = useState([])

  const handleCreate = async (values) => {
    // Call API
    await fetch('/api/v1/setups', {
      method: 'POST',
      body: JSON.stringify(values)
    })

    // Close modal
    setIsModalOpen(false)

    // Show success message
    message.success('Setup created successfully')

    // Refresh table
    loadSetups()
  }

  return (
    <div>
      <Button onClick={() => setIsModalOpen(true)}>
        Create Setup
      </Button>



## Design Patterns for TDD UI Development

### Pattern 1: Contract-First Design

**Before writing any code, create a contract document:**

```markdown
# Feature: Queue Management

## Workflow Contract

### Create Queue
- **Trigger**: Click "Create Queue" button
- **Modal**: "Create Queue"
- **Fields**:
  - Queue Name (text, required, unique, lowercase, no spaces)
  - Database Setup (dropdown, required, populated from existing setups)
  - Description (textarea, optional)
- **Validation**:
  - Queue name must match pattern: ^[a-z0-9-]+$
  - Database setup must exist
- **Success**: Modal closes, message "Queue created", queue in table
- **Error**: Modal open, error message, form retains values

### API Contract
POST /api/v1/queues
Request: { queueName, setupId, description }
Response: 201 Created { queueName, setupId, description, createdAt }
Error: 400 { error: "Queue already exists" }

### Test Contract
1. Navigate to /queues
2. Click "Create Queue"
3. Fill valid data
4. Submit
5. Verify success
6. Verify in table
```

**This contract becomes your specification for:**
- Writing tests
- Building UI components
- Implementing API endpoints
- Reviewing with stakeholders

### Pattern 2: Mock Backend First

**Don't wait for the real backend - mock it:**

```typescript
// playwright.config.ts
export default defineConfig({
  use: {
    // Mock API responses during development
    baseURL: 'http://localhost:5173',
  },
})

// In your test
test('create queue with mocked backend', async ({ page }) => {
  // Mock the API response
  await page.route('**/api/v1/queues', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          queueName: 'test-queue',
          setupId: 'default',
          description: 'Test queue',
          createdAt: new Date().toISOString()
        })
      })
    }
  })

  // Now test the UI workflow
  await page.goto('/queues')
  await page.getByRole('button', { name: /create queue/i }).click()
  await page.getByLabel('Queue Name').fill('test-queue')
  await page.getByLabel('Database Setup').selectOption('default')
  await page.getByRole('button', { name: 'Create Queue' }).click()

  // Verify UI behavior (not backend)
  await expect(page.getByText(/queue created/i)).toBeVisible()
})
```

**Benefits:**
- Test UI independently of backend
- Fast feedback loop
- Parallel development (UI and backend teams)
- Test error scenarios easily

### Pattern 3: Page Object from Contract

**Generate page object skeleton from contract:**

```typescript
// QueuesPage.ts - Generated from contract
export class QueuesPage extends BasePage {
  // Navigation (from contract)
  async goto() {
    await this.navigateTo('queues')
  }

  // Actions (from contract)
  async clickCreateQueue() {
    await this.page.getByRole('button', { name: /create queue/i }).click()
    await this.waitForModal('Create Queue')
  }

  async fillQueueForm(data: {
    queueName: string
    setupId: string
    description?: string
  }) {
    await this.page.getByLabel('Queue Name').fill(data.queueName)
    await this.page.getByLabel('Database Setup').selectOption(data.setupId)
    if (data.description) {
      await this.page.getByLabel('Description').fill(data.description)
    }
  }

  async submitQueueForm() {
    await this.page.getByRole('button', { name: 'Create Queue' }).click()
  }

  // Verifications (from contract)
  async verifyQueueCreated(queueName: string) {
    await this.waitForSuccessMessage(/queue created/i)
    await expect(this.page.getByRole('row', { name: new RegExp(queueName) })).toBeVisible()
  }

  // High-level workflow (from contract)
  async createQueue(data: {
    queueName: string
    setupId: string
    description?: string
  }) {
    await this.clickCreateQueue()
    await this.fillQueueForm(data)
    await this.submitQueueForm()
    await this.verifyQueueCreated(data.queueName)
  }
}
```

**Now your test is simple:**

```typescript
test('create queue', async ({ queuesPage }) => {
  await queuesPage.goto()
  await queuesPage.createQueue({
    queueName: 'test-queue',
    setupId: 'default',
    description: 'Test queue'
  })
})
```

### Pattern 4: Visual Regression Testing

**Capture UI states during TDD:**

```typescript
test('queue creation workflow - visual regression', async ({ page }) => {
  // Initial state
  await page.goto('/queues')
  await expect(page).toHaveScreenshot('queues-empty.png')

  // Modal open
  await page.getByRole('button', { name: /create queue/i }).click()
  await expect(page).toHaveScreenshot('queues-create-modal.png')

  // Form filled
  await page.getByLabel('Queue Name').fill('test-queue')
  await page.getByLabel('Database Setup').selectOption('default')
  await expect(page).toHaveScreenshot('queues-form-filled.png')

  // Success state
  await page.getByRole('button', { name: 'Create Queue' }).click()
  await page.waitForSelector('.ant-message-success')
  await expect(page).toHaveScreenshot('queues-success.png')
})
```

**Benefits:**
- Catch unintended visual changes
- Document expected UI states
- Review UI with stakeholders
- Prevent CSS regressions

## TDD Workflow Example: Adding "Edit Queue" Feature

### Step 1: Write the Contract

```markdown
## Edit Queue Workflow

### Trigger
- Click "Edit" button in queue table row

### Modal
- Title: "Edit Queue"
- Pre-filled with current values
- Fields: Description (editable), Queue Name (read-only), Setup ID (read-only)

### API
PUT /api/v1/queues/{queueName}
Request: { description }
Response: 200 OK

### Success
- Modal closes
- Message: "Queue updated successfully"
- Table shows updated description
```

### Step 2: Write Failing Test

```typescript
test('should edit queue description', async ({ page, queuesPage }) => {
  // Setup: Create a queue first
  await queuesPage.goto()
  await queuesPage.createQueue({
    queueName: 'edit-test',
    setupId: 'default',
    description: 'Original description'
  })

  // Test: Edit the queue (WILL FAIL - edit button doesn't exist)
  await page.getByRole('row', { name: /edit-test/i })
    .getByRole('button', { name: /edit/i })
    .click()

  // Verify modal opened with pre-filled data
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByLabel('Queue Name')).toHaveValue('edit-test')
  await expect(page.getByLabel('Queue Name')).toBeDisabled()
  await expect(page.getByLabel('Description')).toHaveValue('Original description')

  // Change description
  await page.getByLabel('Description').clear()
  await page.getByLabel('Description').fill('Updated description')

  // Submit
  await page.getByRole('button', { name: 'Update Queue' }).click()

  // Verify success
  await expect(page.getByText(/queue updated successfully/i)).toBeVisible()
  await expect(page.getByRole('row', { name: /edit-test/i }))
    .toContainText('Updated description')
})
```

### Step 3: Run Test (RED)

```bash
npx playwright test edit-queue.spec.ts
# ❌ Error: Button "Edit" not found
```

### Step 4: Build UI (GREEN)

```tsx
// Add edit button to table
<Table
  columns={[
    { title: 'Queue Name', dataIndex: 'queueName' },
    { title: 'Description', dataIndex: 'description' },
    {
      title: 'Actions',
      render: (_, record) => (
        <Button onClick={() => handleEdit(record)}>Edit</Button>
      )
    }
  ]}
/>

// Add edit modal
<Modal
  open={isEditModalOpen}
  title="Edit Queue"
  onCancel={() => setIsEditModalOpen(false)}
>
  <Form initialValues={selectedQueue}>
    <Form.Item label="Queue Name" name="queueName">
      <Input disabled />
    </Form.Item>
    <Form.Item label="Description" name="description">
      <Input.TextArea />
    </Form.Item>
    <Button htmlType="submit">Update Queue</Button>
  </Form>
</Modal>
```

### Step 5: Run Test (GREEN)

```bash
npx playwright test edit-queue.spec.ts
# ✅ Test passes!
```

### Step 6: Refactor

- Add loading state during update
- Add optimistic UI updates
- Add error handling
- Improve accessibility

**Tests ensure refactoring doesn't break functionality.**

## Practical TDD Workflow Template

### For Each New Feature

1. **Write Contract** (5-10 minutes)
   - User story
   - UI elements and behavior
   - API contract
   - Test steps

2. **Write Failing Test** (10-15 minutes)
   - Based on contract
   - Use semantic selectors
   - Mock backend if needed

3. **Run Test - Verify RED** (1 minute)
   - Test MUST fail
   - Failure should be expected (element not found)

4. **Build Minimal UI** (30-60 minutes)
   - Just enough to pass test
   - Don't add extra features
   - Focus on contract

5. **Run Test - Verify GREEN** (1 minute)
   - Test MUST pass
   - If not, fix UI or test

6. **Refactor** (15-30 minutes)
   - Improve code quality
   - Add styling
   - Add error handling
   - Run test after each change

7. **Update Documentation** (5 minutes)
   - Add workflow to UI_WORKFLOW_REFERENCE.md
   - Update page object
   - Commit changes

## Summary

**TDD for UI development:**

1. **Contract-first** - Define workflow before coding
2. **Test-first** - Write failing test based on contract
3. **Minimal UI** - Build just enough to pass test
4. **Refactor** - Improve without breaking test
5. **Mock backend** - Don't wait for real API
6. **Page objects** - Generate from contract
7. **Visual regression** - Capture UI states
8. **Document** - Keep workflow reference updated

**Benefits:**
- ✅ Clear requirements before coding
- ✅ Parallel UI/backend development
- ✅ Confidence in refactoring
- ✅ Living documentation
- ✅ Fewer bugs
- ✅ Faster development (long-term)


