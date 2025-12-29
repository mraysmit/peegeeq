# UI Testing Complete Guide - From Design to Implementation

## Overview

This guide covers the complete workflow for UI testing in the PeeGeeQ Management UI, from initial design through implementation and validation. It addresses three scenarios:

1. **With Designer** - Starting from Figma mockups and design specifications
2. **Without Designer** - Test-driven development approach
3. **Existing UI** - Documenting and testing already-built interfaces

## Table of Contents

- [The Correct Order of Operations](#the-correct-order-of-operations)
- [Scenario 1: With Designer (Design-First)](#scenario-1-with-designer-design-first)
- [Scenario 2: Without Designer (TDD)](#scenario-2-without-designer-tdd)
- [Scenario 3: Existing UI (Documentation)](#scenario-3-existing-ui-documentation)
- [Design Handoff Checklist](#design-handoff-checklist)
- [Test Writing Patterns](#test-writing-patterns)
- [Selector Strategy](#selector-strategy)
- [Tools and Integration](#tools-and-integration)

## The Correct Order of Operations

### With Designer (Ideal)

```
1. UI/UX Design (Figma, Sketch, wireframes)
   ↓
2. Design Specifications (interaction patterns, states, flows)
   ↓
3. Test Contracts (derived from design specs)
   ↓
4. Automated Tests (based on contracts)
   ↓
5. Implementation (guided by tests)
   ↓
6. Validation (tests verify implementation matches design)
```

### Without Designer (TDD)

```
1. Write Workflow Contract (define desired behavior)
   ↓
2. Write Failing Test (based on contract)
   ↓
3. Build Minimal UI (just enough to pass test)
   ↓
4. Run Test (verify GREEN)
   ↓
5. Refactor (improve without breaking test)
```

### Existing UI (Documentation)

```
1. Use Playwright Codegen (record interactions)
   ↓
2. Document Workflow (capture steps and states)
   ↓
3. Create Page Objects (from recorded interactions)
   ↓
4. Write Tests (validate existing behavior)
```

## Scenario 1: With Designer (Design-First)

### Phase 1: What Designers Should Deliver

**1. Visual Designs (Figma/Sketch/Adobe XD)**
- High-fidelity mockups of each screen
- Component library (buttons, forms, tables, modals)
- Color palette, typography, spacing system
- Responsive breakpoints

**2. Interaction Specifications**
- Click flows (what happens when user clicks X)
- Form validation rules
- Error states and messages
- Loading states
- Success states
- Empty states

**3. User Flows**
- Step-by-step workflows for each feature
- Decision points (if/else scenarios)
- Error recovery paths
- Happy path vs edge cases

**4. Accessibility Requirements**
- Keyboard navigation
- Screen reader labels
- Focus states
- ARIA attributes
- Color contrast ratios

### Phase 2: Example Design Deliverable

**Feature: Create Database Setup**

**Visual Design (Figma Frames)**

```
Frame 1: Database Setups Page - Empty State
- Header: "Database Setups"
- Button: "Create Setup" (primary, top-right)
- Empty state card: "No Database Setups Found"
- Subtext: "Click 'Create Setup' to add your first database connection"

Frame 2: Create Setup Modal
- Modal title: "Create Database Setup"
- Form fields (vertical layout, 16px spacing):
  * Setup ID (text input, required, placeholder: "e.g., production-db")
  * Host (text input, required, placeholder: "localhost")
  * Port (number input, required, default: 5432)
  * Database Name (text input, required, placeholder: "mydb")
  * Username (text input, required)
  * Password (password input, required, show/hide toggle)
  * Schema (text input, optional, default: "public")
- Buttons: "Cancel" (secondary), "Create Setup" (primary, disabled until valid)

Frame 3: Success State
- Modal closed
- Toast message: "Database setup created successfully" (green, 3s duration)
- Table showing new setup with columns: Setup ID, Host, Port, Database, Actions

Frame 4: Error State
- Modal remains open
- Error message below submit button: "Setup ID already exists" (red)
- Form retains entered values
- Submit button re-enabled
```

**Interaction Specification**

```markdown
## Create Database Setup - Interaction Flow

### Trigger
- User clicks "Create Setup" button

### Modal Behavior
- Opens with fade-in animation (200ms)
- Focus moves to "Setup ID" field
- Backdrop click closes modal (with confirmation if form dirty)
- ESC key closes modal (with confirmation if form dirty)

### Form Validation
- Setup ID:
  * Required


### Phase 3: Translate Design to Test Contract

**From Design Spec to TypeScript Contract:**

```typescript
/**
 * Test Contract: Create Database Setup
 * Source: Figma Frame 2 - Create Setup Modal
 * Designer: [Name]
 * Date: 2025-12-29
 */

interface CreateSetupTestContract {
  // Page elements (from Frame 1)
  page: {
    url: '/database-setups'
    title: 'Database Setups'
    createButton: { role: 'button', name: 'Create Setup' }
    emptyState: { text: 'No Database Setups Found' }
  }

  // Modal elements (from Frame 2)
  modal: {
    title: 'Create Database Setup'
    fields: {
      setupId: { label: 'Setup ID', type: 'text', required: true }
      host: { label: 'Host', type: 'text', required: true }
      port: { label: 'Port', type: 'number', required: true, default: 5432 }
      databaseName: { label: 'Database Name', type: 'text', required: true }
      username: { label: 'Username', type: 'text', required: true }
      password: { label: 'Password', type: 'password', required: true }
      schema: { label: 'Schema', type: 'text', required: false, default: 'public' }
    }
    buttons: {
      cancel: { role: 'button', name: 'Cancel' }
      submit: { role: 'button', name: 'Create Setup' }
    }
  }

  // Interactions (from Interaction Spec)
  interactions: {
    openModal: 'click createButton'
    fillForm: 'fill all required fields'
    submit: 'click submit button'
    cancel: 'click cancel button OR press ESC'
  }

  // Expected outcomes (from Frame 3)
  success: {
    modalClosed: true
    toastMessage: 'Database setup created successfully'
    toastDuration: 3000
    tableUpdated: true
    rowHighlighted: true
  }

  // Error scenarios (from Frame 4)
  errors: {
    duplicateId: {
      message: 'Setup ID already exists'
      modalOpen: true
      formRetained: true
    }
  }
}
```

### Phase 4: Generate Tests from Contract

```typescript
// database-setup.spec.ts
// Generated from: Figma "Create Database Setup" flow
// Design spec: docs/design-specs/database-setup.md
// Test contract: CreateSetupTestContract

import { test, expect } from '@playwright/test'

test.describe('Create Database Setup - Design Validation', () => {
  test('should match design spec for empty state', async ({ page }) => {
    // Frame 1: Empty State
    await page.goto('/database-setups')

    // Verify page title (from design)
    await expect(page.getByRole('heading', { name: 'Database Setups' })).toBeVisible()

    // Verify create button (from design)
    const createButton = page.getByRole('button', { name: 'Create Setup' })
    await expect(createButton).toBeVisible()

    // Verify empty state (from design)
    await expect(page.getByText('No Database Setups Found')).toBeVisible()
  })

  test('should match design spec for modal layout', async ({ page }) => {
    // Frame 2: Modal Layout
    await page.goto('/database-setups')
    await page.getByRole('button', { name: 'Create Setup' }).click()

    // Verify modal title (from design)
    await expect(page.getByRole('heading', { name: 'Create Database Setup' })).toBeVisible()

    // Verify all form fields exist (from design)
    await expect(page.getByLabel('Setup ID')).toBeVisible()
    await expect(page.getByLabel('Host')).toBeVisible()
    await expect(page.getByLabel('Port')).toBeVisible()
    await expect(page.getByLabel('Database Name')).toBeVisible()
    await expect(page.getByLabel('Username')).toBeVisible()
    await expect(page.getByLabel('Password')).toBeVisible()
    await expect(page.getByLabel('Schema')).toBeVisible()

    // Verify default values (from design)
    await expect(page.getByLabel('Port')).toHaveValue('5432')
    await expect(page.getByLabel('Schema')).toHaveValue('public')
  })

  test('should follow interaction spec for form validation', async ({ page }) => {
    // Interaction Spec: Validation Rules
    await page.goto('/database-setups')
    await page.getByRole('button', { name: 'Create Setup' }).click()

    // Test Setup ID validation (from interaction spec)
    await page.getByLabel('Setup ID').fill('INVALID_ID')
    await page.getByLabel('Setup ID').blur()
    await expect(page.getByText('Setup ID must contain only lowercase letters, numbers, and hyphens'))
      .toBeVisible()

    // Submit button should be disabled (from interaction spec)
    await expect(page.getByRole('button', { name: 'Create Setup' })).toBeDisabled()
  })

  test('should follow success flow from design spec', async ({ page }) => {
    // Frame 3: Success State
    await page.goto('/database-setups')
    await page.getByRole('button', { name: 'Create Setup' }).click()

    // Fill form with valid data
    await page.getByLabel('Setup ID').fill('test-setup')
    await page.getByLabel('Host').fill('localhost')
    await page.getByLabel('Port').fill('5432')
    await page.getByLabel('Database Name').fill('testdb')
    await page.getByLabel('Username').fill('postgres')
    await page.getByLabel('Password').fill('password')

    // Submit
    await page.getByRole('button', { name: 'Create Setup' }).click()

    // Verify success toast (from design Frame 3)
    await expect(page.getByText('Database setup created successfully')).toBeVisible()

    // Verify modal closed (from design Frame 3)
    await expect(page.getByRole('dialog')).not.toBeVisible()

    // Verify table updated (from design Frame 3)
    await expect(page.getByRole('row', { name: /test-setup/i })).toBeVisible()
  })
})
```

### Phase 5: Implement UI

Now build the React components to satisfy the test contract:

```tsx
// DatabaseSetupsPage.tsx
export function DatabaseSetupsPage() {
  const [isModalOpen, setIsModalOpen] = useState(false)
  const [setups, setSetups] = useState([])

  const handleCreate = async (values) => {
    await fetch('/api/v1/setups', {
      method: 'POST',
      body: JSON.stringify(values)
    })

    setIsModalOpen(false)
    message.success('Database setup created successfully')
    loadSetups()
  }

  return (
    <div>
      <h1>Database Setups</h1>
      <Button onClick={() => setIsModalOpen(true)}>Create Setup</Button>

      {setups.length === 0 && (
        <Alert message="No Database Setups Found"
               description="Click 'Create Setup' to add your first database connection" />
      )}

      <Table dataSource={setups} />

      <Modal
        open={isModalOpen}
        title="Create Database Setup"
        onCancel={() => setIsModalOpen(false)}
      >
        <Form onFinish={handleCreate} initialValues={{ port: 5432, schema: 'public' }}>
          <Form.Item label="Setup ID" name="setupId" required>
            <Input />
          </Form.Item>
          <Form.Item label="Host" name="host" required>
            <Input />
          </Form.Item>
          <Form.Item label="Port" name="port" required>
            <InputNumber />
          </Form.Item>
          <Form.Item label="Database Name" name="databaseName" required>
            <Input />
          </Form.Item>
          <Form.Item label="Username" name="username" required>
            <Input />
          </Form.Item>
          <Form.Item label="Password" name="password" required>
            <Input.Password />
          </Form.Item>
          <Form.Item label="Schema" name="schema">
            <Input />
          </Form.Item>

          <Button htmlType="submit">Create Setup</Button>
        </Form>
      </Modal>
    </div>
  )
}
```

## Scenario 2: Without Designer (TDD)

When you don't have formal design deliverables, use test-driven development to build UI features.

### The Red-Green-Refactor Cycle for UI

1. **RED** - Write a failing test that describes the desired user workflow
2. **GREEN** - Build the minimal UI to make the test pass
3. **REFACTOR** - Improve the UI without breaking the test

### Step 1: Write the Workflow Contract

Before writing ANY code, define the contract:

```markdown
## Workflow: Create Queue

### User Story
As a user, I want to create a queue so I can send and receive messages.

### UI Contract
- Page: `/queues`
- Button: "Create Queue" (opens modal)
- Modal: "Create Queue"
- Form fields:
  - Queue Name (text input, required, unique, lowercase, no spaces)
  - Database Setup (dropdown, required, populated from existing setups)
  - Description (textarea, optional)
- Submit button: "Create Queue"
- Success: Modal closes, success message, queue appears in table
- Error: Modal stays open, error message shown

### API Contract
POST /api/v1/queues
Request: { queueName, setupId, description }
Response: 201 Created or 400 Bad Request

### Test Contract
1. Navigate to /queues
2. Click "Create Queue" button
3. Fill form with valid data
4. Submit form
5. Verify success message
6. Verify queue in table
```

### Step 2: Write the Failing Test

```typescript
// queue-operations.spec.ts
import { test, expect } from '@playwright/test'

test.describe('Create Queue - TDD', () => {
  test('should create queue through UI', async ({ page }) => {
    // Step 1: Navigate to page (DOESN'T EXIST YET - TEST WILL FAIL)
    await page.goto('/queues')

    // Step 2: Click create button (DOESN'T EXIST YET)
    await page.getByRole('button', { name: /create queue/i }).click()

    // Step 3: Verify modal opened (DOESN'T EXIST YET)
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Create Queue' })).toBeVisible()

    // Step 4: Fill form (FIELDS DON'T EXIST YET)
    await page.getByLabel('Queue Name').fill('test-queue')
    await page.getByLabel('Database Setup').selectOption('default')
    await page.getByLabel('Description').fill('Test queue')

    // Step 5: Submit (BUTTON DOESN'T EXIST YET)
    await page.getByRole('button', { name: 'Create Queue' }).click()

    // Step 6: Verify success (SUCCESS MESSAGE DOESN'T EXIST YET)
    await expect(page.getByText(/queue created/i)).toBeVisible()

    // Step 7: Verify in table (TABLE DOESN'T EXIST YET)
    await expect(page.getByRole('row', { name: /test-queue/i })).toBeVisible()
  })
})
```

### Step 3: Run Test (RED)

```bash
npx playwright test queue-operations.spec.ts
# ❌ Error: locator.click: Target closed
# ❌ Button "Create Queue" not found
```

### Step 4: Build Minimal UI (GREEN)

```tsx
// QueuesPage.tsx
export function QueuesPage() {
  const [isModalOpen, setIsModalOpen] = useState(false)
  const [queues, setQueues] = useState([])

  const handleCreate = async (values) => {
    await fetch('/api/v1/queues', {
      method: 'POST',
      body: JSON.stringify(values)
    })

    setIsModalOpen(false)
    message.success('Queue created successfully')
    loadQueues()
  }

  return (
    <div>
      <Button onClick={() => setIsModalOpen(true)}>Create Queue</Button>
      <Table dataSource={queues} />

      <Modal open={isModalOpen} title="Create Queue" onCancel={() => setIsModalOpen(false)}>
        <Form onFinish={handleCreate}>
          <Form.Item label="Queue Name" name="queueName" required>
            <Input />
          </Form.Item>
          <Form.Item label="Database Setup" name="setupId" required>
            <Select>
              <Select.Option value="default">default</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item label="Description" name="description">
            <Input.TextArea />
          </Form.Item>
          <Button htmlType="submit">Create Queue</Button>
        </Form>
      </Modal>
    </div>
  )
}
```

### Step 5: Run Test (GREEN)

```bash
npx playwright test queue-operations.spec.ts
# ✅ Test passes!
```

### Step 6: Refactor

Now improve the UI without breaking the test:
- Add validation
- Improve styling
- Add loading states
- Add error handling

**The test ensures you don't break the core workflow.**

### Pattern: Mock Backend First

Don't wait for the real backend - mock it in your tests:

```typescript
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


## Scenario 3: Existing UI (Documentation)

When you have an existing UI that needs tests, use Playwright Codegen to capture workflows.

### Step 1: Start Playwright Codegen

```bash
# Start the backend and UI first
# Terminal 1
cd scripts
./start-backend-with-testcontainers.sh

# Terminal 2
npm run dev

# Terminal 3 - Start Codegen
npx playwright codegen http://localhost:5173
```

### Step 2: Perform the Workflow Manually

**What you do in the browser:**
1. Click "Database Setups" in sidebar
2. Click "Create Setup" button
3. Fill in form fields
4. Click "Create Setup" button in modal
5. See success message
6. See new setup in table

**What Codegen generates:**
```typescript
await page.getByRole('link', { name: 'Database Setups' }).click();
await page.getByRole('button', { name: 'Create Setup' }).click();
await page.getByLabel('Setup ID').click();
await page.getByLabel('Setup ID').fill('my-setup');
await page.getByLabel('Host').click();
await page.getByLabel('Host').fill('localhost');
// ... etc
```

### Step 3: Refine into Page Object

**Extract the core interactions:**

```typescript
// DatabaseSetupsPage.ts - Generated from codegen
export class DatabaseSetupsPage extends BasePage {
  async goto() {
    await this.navigateTo('database-setups')
  }

  async clickCreateSetup() {
    await this.page.getByRole('button', { name: /create setup/i }).click()
    await this.waitForModal('Create Database Setup')
  }

  async fillSetupForm(config: {
    setupId: string
    host: string
    port: number
    databaseName: string
    username: string
    password: string
    schema?: string
  }) {
    await this.page.getByLabel('Setup ID').fill(config.setupId)
    await this.page.getByLabel('Host').fill(config.host)
    await this.page.getByLabel('Port').fill(config.port.toString())
    await this.page.getByLabel('Database Name').fill(config.databaseName)
    await this.page.getByLabel('Username').fill(config.username)
    await this.page.getByLabel('Password').fill(config.password)

    if (config.schema) {
      await this.page.getByLabel('Schema').fill(config.schema)
    }
  }

  async submitSetupForm() {
    await this.page.getByRole('button', { name: 'Create Setup' }).click()
  }

  async verifySetupCreated(setupId: string) {
    await this.waitForSuccessMessage(/setup created/i)
    await expect(this.page.getByRole('row', { name: new RegExp(setupId) })).toBeVisible()
  }

  // High-level workflow
  async createSetup(config: SetupConfig) {
    await this.clickCreateSetup()
    await this.fillSetupForm(config)
    await this.submitSetupForm()
    await this.verifySetupCreated(config.setupId)
  }
}
```

### Step 4: Write Tests Using Page Object

```typescript
test('create database setup', async ({ databaseSetupsPage }) => {
  await databaseSetupsPage.goto()
  await databaseSetupsPage.createSetup({
    setupId: 'test-setup',
    host: 'localhost',
    port: 5432,
    databaseName: 'testdb',
    username: 'postgres',
    password: 'password',
    schema: 'public'
  })
})
```

### Step 5: Document the Workflow

Add to workflow reference documentation:

```markdown
## Workflow: Create Database Setup

### Prerequisites
- User is on the Database Setups page (`/database-setups`)
- No existing setup with the same ID exists

### Step-by-Step Workflow

**Step 1: Navigate to Database Setups**
```typescript
await page.goto('/')
await page.getByRole('link', { name: 'Database Setups' }).click()
```

**Step 2: Click Create Setup Button**
```typescript
await page.getByRole('button', { name: /create setup/i }).click()
```

**Step 3: Fill in Setup Details**
```typescript
await page.getByLabel('Setup ID').fill('my-setup')
await page.getByLabel('Host').fill('localhost')
// ... etc
```

**Step 4: Submit the Form**
```typescript
await page.getByRole('button', { name: 'Create Setup' }).click()
```

**Step 5: Verify Setup in Table**
```typescript
const row = page.locator('tr:has-text("my-setup")')
await expect(row).toBeVisible()
```
```

### Capturing Screenshots for Documentation

```typescript
// In your test or codegen session
await page.screenshot({ path: 'docs/screenshots/database-setups-empty.png' })
await page.getByRole('button', { name: 'Create Setup' }).click()
await page.screenshot({ path: 'docs/screenshots/database-setups-modal.png' })
```

**Recommended Screenshots:**
1. **Initial state** - Page before any interaction
2. **Modal/Dialog open** - Form ready for input
3. **Form filled** - All fields populated
4. **Success state** - Success message visible
5. **Final state** - Result visible in UI (e.g., item in table)

## Design Handoff Checklist

### What Developers Need from Designers

**Essential Deliverables:**
- [ ] High-fidelity mockups (Figma/Sketch with developer handoff enabled)
- [ ] Component specifications (spacing, colors, typography)
- [ ] Interaction specifications (click flows, states, animations)
- [ ] User flow diagrams (happy path + error paths)
- [ ] Validation rules (field-by-field)
- [ ] Error messages (exact copy)
- [ ] Success messages (exact copy)
- [ ] Loading states (spinners, skeleton screens)
- [ ] Empty states (no data scenarios)
- [ ] Accessibility requirements (keyboard nav, ARIA labels)

**Nice to Have:**
- [ ] Prototype (clickable Figma prototype)
- [ ] Animation specs (duration, easing)
- [ ] Responsive breakpoints (mobile, tablet, desktop)
- [ ] Dark mode variants
- [ ] Micro-interactions (hover, focus, active states)

### Design Specification Template

```markdown
# Feature: [Feature Name]

## Design Files
- Figma: [Link to Figma file]
- Prototype: [Link to clickable prototype]
- Design System: [Link to component library]

## User Story
As a [user type], I want to [action] so that [benefit].

## Visual Design

### Frame 1: [State Name]
- Screenshot: ![Frame 1](./screenshots/frame-1.png)
- Description: [What user sees]
- Elements:
  * Element 1: [Description, position, styling]
  * Element 2: [Description, position, styling]

## Interaction Specification

### Trigger
[What initiates this workflow]

### Step-by-Step Flow
1. User [action]
2. System [response]
3. User [action]
4. System [response]

### Validation Rules
- Field 1:
  * Required: Yes/No
  * Pattern: [Regex or description]
  * Min/Max length: [Numbers]
  * Error message: "[Exact copy]"

### States
- **Default**: [Description]
- **Hover**: [Description]
- **Focus**: [Description]
- **Disabled**: [Description]
- **Loading**: [Description]
- **Error**: [Description]
- **Success**: [Description]

## Accessibility

### Keyboard Navigation
- Tab: [Behavior]
- Enter: [Behavior]
- Escape: [Behavior]

### Screen Reader
- Modal title: [ARIA label]
- Form fields: [ARIA labels]
- Error messages: [ARIA live region]

## API Contract

### Endpoint
[Method] [URL]

### Request
```json
{
  "field1": "value"
}
```

### Response - Success
```json
{
  "field1": "value"
}
```

### Response - Error
```json
{
  "error": "Error message"
}
```

## Test Scenarios

### Happy Path
1. [Step]
2. [Expected result]

### Error Scenarios
1. **Scenario**: [Description]
   - Steps: [Steps to reproduce]
   - Expected: [Error message and behavior]
```


## Test Writing Patterns

### Pattern 1: Page Object from Contract

Generate page object skeleton from design contract or workflow contract:

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

### Pattern 2: Visual Regression Testing

Capture UI states during development:

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

### Pattern 3: Serial Mode for Dependent Tests

Database setup tests MUST run in order:

```typescript
import { test, expect } from '@playwright/test'

// CRITICAL: Serial mode ensures tests run in order
test.describe.configure({ mode: 'serial' })

test.describe('Database Setup', () => {
  test('should create database setup through UI', async ({ page, databaseSetupsPage }) => {
    await databaseSetupsPage.goto()

    // Should show "No Database Setups Found" (clean database)
    await expect(page.locator('.ant-alert')
      .filter({ hasText: 'No Database Setups Found' })).toBeVisible()

    // Create setup through UI
    await databaseSetupsPage.createSetup({
      setupId: 'default',
      host: 'localhost',
      port: 5432,
      databaseName: 'testdb',
      username: 'postgres',
      password: 'password',
      schema: 'public'
    })

    // Verify setup was created
    const exists = await databaseSetupsPage.setupExists('default')
    expect(exists).toBeTruthy()
  })

  test('should display created setup in table', async ({ databaseSetupsPage }) => {
    await databaseSetupsPage.goto()

    // Setup exists from previous test (serial mode ensures order)
    const exists = await databaseSetupsPage.setupExists('default')
    expect(exists).toBeTruthy()
  })
})
```

## Selector Strategy

### Priority Order (Best to Worst)

1. **Role-based selectors** (Most resilient)
   ```typescript
   page.getByRole('button', { name: 'Create Setup' })
   page.getByRole('link', { name: 'Database Setups' })
   page.getByRole('textbox', { name: 'Username' })
   ```

2. **Label-based selectors** (Good for forms)
   ```typescript
   page.getByLabel('Setup ID')
   page.getByLabel('Password')
   ```

3. **Test ID selectors** (For complex elements)
   ```typescript
   page.getByTestId('setup-table')
   page.getByTestId('queue-row-my-queue')
   ```

4. **Text selectors** (Use sparingly, breaks with copy changes)
   ```typescript
   page.getByText('No Database Setups Found')
   ```

5. **CSS selectors** (Last resort, fragile)
   ```typescript
   page.locator('.ant-table tbody tr')  // Only when no better option
   ```

### When to Add data-testid Attributes

Add `data-testid` when:
- Element has no semantic role
- Element has no associated label
- Multiple similar elements exist
- Element is critical for testing

**Example:**
```tsx
// In React component
<div className="setup-card" data-testid={`setup-card-${setupId}`}>
  <h3>{setupId}</h3>
  <p>{host}:{port}</p>
</div>
```

```typescript
// In test
await page.getByTestId('setup-card-default').click()
```

## Tools and Integration

### Figma to Code

**Figma Plugins:**
- **Figma to Code** - Export components to React/Vue/HTML
- **Anima** - Generate React code from designs
- **Quest** - AI-powered Figma to React

**Manual Approach (Recommended):**
1. Enable "Dev Mode" in Figma
2. Inspect spacing, colors, typography
3. Copy CSS values
4. Implement with design system components

### Design Specification Tools

- **Zeplin** - Design handoff with specs
- **Avocode** - Design to code collaboration
- **Figma Dev Mode** - Built-in developer handoff
- **Storybook** - Component documentation and testing

### Automated Design Testing

- **Percy** - Visual regression testing
- **Chromatic** - Visual testing for Storybook
- **Playwright Screenshots** - Built-in visual comparison
- **Applitools** - AI-powered visual testing

## Summary

### The Complete Workflow

**With Designer (Ideal):**
1. Designer creates Figma mockups + interaction specs
2. Developer translates to test contract
3. Developer writes tests from contract
4. Developer implements UI
5. Tests validate implementation matches design

**Without Designer (TDD):**
1. Write workflow contract
2. Write failing test
3. Build minimal UI
4. Run test (GREEN)
5. Refactor

**Existing UI (Documentation):**
1. Use Playwright Codegen
2. Document workflow
3. Create page objects
4. Write tests

### Key Principles

1. **Design is the source of truth** - Tests validate implementation matches design
2. **Contract-first** - Define behavior before coding
3. **Test-first** - Write tests before implementation
4. **Page objects** - Encapsulate UI interactions
5. **Semantic selectors** - Use role and label selectors
6. **Visual regression** - Capture UI states
7. **Serial mode** - For dependent tests
8. **Mock backend** - Test UI independently

### Benefits

**For Designers:**
- ✅ Implementation matches design exactly
- ✅ All states and interactions are tested
- ✅ Validation rules are enforced
- ✅ Accessibility requirements are met

**For Developers:**
- ✅ Clear specification to implement
- ✅ Tests written before code
- ✅ Confidence in refactoring
- ✅ Automated design validation

**For QA:**
- ✅ Automated regression testing
- ✅ Design specs are testable
- ✅ Edge cases documented
- ✅ Consistent behavior

**For Product:**
- ✅ Features match requirements
- ✅ User flows are validated
- ✅ Error handling is complete
- ✅ Accessibility is built-in
  * Any required field is empty
  * Any field has validation error
  * Form is submitting (loading state)
- Enabled when:
  * All required fields valid
  * No validation errors
```


