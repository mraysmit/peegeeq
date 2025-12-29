# UI Workflow Reference - Test Creation Guide

## Purpose

This document captures the EXACT user interface interactions and workflows for creating Playwright tests. It serves as the single source of truth for:
- What elements exist on each page
- How users interact with those elements
- The expected sequence of actions
- What feedback the UI provides

## Best Practices for Documenting UI Workflows

### 1. Use Playwright Codegen to Record Workflows

**The most accurate way** to capture UI interactions is to use Playwright's built-in code generator:

```bash
# Start codegen with your app
npx playwright codegen http://localhost:5173

# Or with specific browser
npx playwright codegen --browser=chromium http://localhost:5173
```

**What this does:**
- Opens a browser window
- Records every click, type, navigation
- Generates Playwright code in real-time
- Shows you the exact selectors being used
- Captures the actual user workflow

**Workflow:**
1. Start codegen
2. Perform the workflow manually in the browser
3. Copy the generated code
4. Refine it into a page object method
5. Document the workflow below

### 2. Document Each Workflow with Screenshots

For each major workflow, capture:
- **Starting state** - What the page looks like before
- **Each interaction** - Click, type, select
- **Intermediate states** - What happens after each action
- **End state** - What success looks like

### 3. Include Selector Strategy

Document WHY you chose each selector:
- ✅ `getByRole('button', { name: 'Create Setup' })` - Accessible, semantic
- ✅ `getByLabel('Username')` - Form fields by label
- ✅ `getByTestId('setup-table')` - Custom test IDs for complex elements
- ❌ `.ant-btn-primary` - Fragile, implementation detail
- ❌ `text=Create` - Breaks when copy changes

## Workflow 1: Create Database Setup

### User Story
As a user, I want to create a new database setup so that I can connect to a PostgreSQL database.

### Prerequisites
- User is on the Database Setups page (`/database-setups`)
- No existing setup with the same ID exists

### Step-by-Step Workflow

**Step 1: Navigate to Database Setups**
```typescript
await page.goto('/')
await page.getByRole('link', { name: 'Database Setups' }).click()
```
- **Expected**: URL changes to `/database-setups`
- **Expected**: Page title shows "Database Setups"

**Step 2: Click Create Setup Button**
```typescript
await page.getByRole('button', { name: /create setup/i }).click()
```
- **Expected**: Modal dialog opens with title "Create Database Setup"
- **Expected**: Form is empty and ready for input

**Step 3: Fill in Setup Details**
```typescript
await page.getByLabel('Setup ID').fill('my-setup')
await page.getByLabel('Host').fill('localhost')
await page.getByLabel('Port').fill('5432')
await page.getByLabel('Database Name').fill('mydb')
await page.getByLabel('Username').fill('postgres')
await page.getByLabel('Password').fill('password')
await page.getByLabel('Schema').fill('public')
```
- **Expected**: Each field shows the typed value
- **Expected**: No validation errors (if inputs are valid)

**Step 4: Submit the Form**
```typescript
await page.getByRole('button', { name: 'Create Setup' }).click()
```
- **Expected**: Success message appears (e.g., "Database setup created successfully")
- **Expected**: Modal closes
- **Expected**: New setup appears in the table

**Step 5: Verify Setup in Table**
```typescript
const row = page.locator('tr:has-text("my-setup")')
await expect(row).toBeVisible()
```
- **Expected**: Table row contains the setup ID
- **Expected**: Row shows connection details (host, port, database)

### Error Cases

**Duplicate Setup ID:**
- Fill form with existing setup ID
- Click "Create Setup"
- **Expected**: Error message "Setup ID already exists"
- **Expected**: Modal stays open
- **Expected**: Form retains entered values

**Invalid Port:**
- Fill form with port "abc" (non-numeric)
- Click "Create Setup"
- **Expected**: Validation error "Port must be a number"
- **Expected**: Submit button disabled or error shown

### Visual Reference

```
┌─────────────────────────────────────────────────────────┐
│ Database Setups                          [Create Setup] │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌────────────────────────────────────────────────┐    │
│  │ No Database Setups Found                       │    │
│  │ Click "Create Setup" to add your first setup   │    │
│  └────────────────────────────────────────────────┘    │
│                                                          │
└─────────────────────────────────────────────────────────┘

After clicking "Create Setup":

┌─────────────────────────────────────────────────────────┐
│ Create Database Setup                            [X]    │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  Setup ID:       [________________]                     │
│  Host:           [________________]                     │
│  Port:           [________________]                     │
│  Database Name:  [________________]                     │
│  Username:       [________________]                     │


## Workflow 2: Create Queue

### User Story
As a user, I want to create a new queue so that I can send and receive messages.

### Prerequisites
- User is on the Queues page (`/queues`)
- At least one database setup exists (e.g., 'default')

### Step-by-Step Workflow

**Step 1: Navigate to Queues**
```typescript
await page.goto('/')
await page.getByRole('link', { name: 'Queues' }).click()
```

**Step 2: Click Create Queue Button**
```typescript
await page.getByRole('button', { name: /create queue/i }).click()
```

**Step 3: Fill in Queue Details**
```typescript
await page.getByLabel('Queue Name').fill('my-queue')
await page.getByLabel('Database Setup').selectOption('default')
await page.getByLabel('Description').fill('My test queue')
```

**Step 4: Submit**
```typescript
await page.getByRole('button', { name: 'Create Queue' }).click()
```

**Step 5: Verify**
```typescript
const row = page.locator('tr:has-text("my-queue")')
await expect(row).toBeVisible()
```

## Recording Workflows with Playwright Codegen

### Best Practice Workflow

1. **Start the backend and UI**
   ```bash
   # Terminal 1
   cd scripts
   ./start-backend-with-testcontainers.sh

   # Terminal 2
   npm run dev
   ```

2. **Start Playwright Codegen**
   ```bash
   npx playwright codegen http://localhost:5173
   ```

3. **Perform the workflow manually**
   - Click through the UI exactly as a user would
   - Fill in forms with realistic data
   - Wait for success messages
   - Verify the result

4. **Copy the generated code**
   - Codegen shows the code in real-time
   - Copy the relevant parts
   - Ignore navigation if using page objects

5. **Refine into page object method**
   - Extract the core interactions
   - Add proper waits and assertions
   - Use semantic selectors (getByRole, getByLabel)
   - Add error handling

6. **Document the workflow here**
   - Add the workflow to this document
   - Include screenshots or ASCII diagrams
   - Document expected states
   - Note any edge cases

### Example: Recording "Create Database Setup"

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

**What you refine into page object:**
```typescript
async createSetup(config: SetupConfig) {
  await this.getCreateButton().click()
  await this.fillSetupForm(config)
  await this.submitForm()
  await this.waitForSuccess()
}
```

## Capturing Screenshots for Documentation

### Using Playwright to Capture States

```typescript
// In your test or codegen session
await page.screenshot({ path: 'docs/screenshots/database-setups-empty.png' })
await page.getByRole('button', { name: 'Create Setup' }).click()
await page.screenshot({ path: 'docs/screenshots/database-setups-modal.png' })
```

### Recommended Screenshots for Each Workflow

1. **Initial state** - Page before any interaction
2. **Modal/Dialog open** - Form ready for input
3. **Form filled** - All fields populated
4. **Success state** - Success message visible
5. **Final state** - Result visible in UI (e.g., item in table)

## Selector Strategy Reference

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

## Maintaining This Document

### When to Update

Update this document when:
- ✅ New workflow is added to the UI
- ✅ Existing workflow changes (new fields, different steps)
- ✅ Selectors change (component refactoring)
- ✅ Error handling changes
- ✅ Success messages change

### How to Update

1. **Use Codegen to re-record the workflow**
2. **Update the step-by-step section**
3. **Update the page object implementation**
4. **Update screenshots if visual changed**
5. **Update tests to match new workflow**

### Review Checklist

Before considering a workflow documented:
- [ ] Codegen recording completed
- [ ] Step-by-step workflow documented
- [ ] Expected states documented
- [ ] Error cases documented
- [ ] Page object method implemented
- [ ] Visual reference included (ASCII or screenshot)
- [ ] Selectors use best practices (role/label first)
- [ ] Tests updated and passing

## Summary

**Best practices for capturing UI workflows:**

1. **Use Playwright Codegen** - Most accurate way to capture interactions
2. **Document immediately** - Don't wait, document while implementing
3. **Include visuals** - Screenshots or ASCII diagrams
4. **Use semantic selectors** - Role and label selectors are most resilient
5. **Document error cases** - Not just happy path
6. **Keep it updated** - Review when UI changes
7. **Test the documentation** - New team members should be able to write tests from this doc


