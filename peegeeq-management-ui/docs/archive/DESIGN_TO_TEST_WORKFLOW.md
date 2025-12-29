# From Design to Tests - The Complete Workflow

## The Correct Order of Operations

Most projects get this backwards. Here's the RIGHT order:

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

## Phase 1: UI/UX Design - The Source of Truth

### What Designers Should Deliver

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

### Example: Design Deliverable for "Create Database Setup"

**Visual Design (Figma)**
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
  * Pattern: ^[a-z0-9-]+$ (lowercase, numbers, hyphens only)
  * Min length: 3, Max length: 50
  * Real-time validation on blur
  * Error: "Setup ID must contain only lowercase letters, numbers, and hyphens"

- Host:
  * Required
  * Valid hostname or IP address
  * Error: "Please enter a valid hostname or IP address"

- Port:
  * Required
  * Number between 1-65535
  * Error: "Port must be between 1 and 65535"

- Database Name:
  * Required
  * Pattern: ^[a-zA-Z0-9_]+$
  * Error: "Database name must contain only letters, numbers, and underscores"

- Username:
  * Required
  * Min length: 1
  * Error: "Username is required"

- Password:
  * Required
  * Min length: 1
  * Show/hide toggle button
  * Error: "Password is required"

- Schema:
  * Optional
  * Default: "public"
  * Pattern: ^[a-zA-Z0-9_]+$

### Submit Button State
- Disabled when:
  * Any required field is empty
  * Any field has validation error
  * Form is submitting (loading state)
- Enabled when:
  * All required fields valid
  * No validation errors

### Loading State
- Submit button shows spinner
- Button text: "Creating..."
- All form fields disabled


## Phase 3: From Test Contract to Automated Tests

### Generate Test from Contract

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
    await expect(page.getByText('Click \'Create Setup\' to add your first database connection')).toBeVisible()
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

    // Verify buttons (from design)
    await expect(page.getByRole('button', { name: 'Cancel' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Create Setup' })).toBeVisible()
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

    // Test Port validation (from interaction spec)
    await page.getByLabel('Port').fill('99999')
    await page.getByLabel('Port').blur()
    await expect(page.getByText('Port must be between 1 and 65535')).toBeVisible()

    // Submit button should be disabled (from interaction spec)
    await expect(page.getByRole('button', { name: 'Create Setup' })).toBeDisabled()
  })

  test('should follow success flow from design spec', async ({ page }) => {
    // Frame 3: Success State
    await page.goto('/database-setups')
    await page.getByRole('button', { name: 'Create Setup' }).click()

    // Fill form with valid data (from interaction spec)
    await page.getByLabel('Setup ID').fill('test-setup')
    await page.getByLabel('Host').fill('localhost')
    await page.getByLabel('Port').fill('5432')
    await page.getByLabel('Database Name').fill('testdb')
    await page.getByLabel('Username').fill('postgres')
    await page.getByLabel('Password').fill('password')

    // Submit button should be enabled (from interaction spec)
    const submitButton = page.getByRole('button', { name: 'Create Setup' })
    await expect(submitButton).toBeEnabled()

    // Submit form
    await submitButton.click()

    // Verify loading state (from interaction spec)
    await expect(page.getByRole('button', { name: 'Creating...' })).toBeVisible()

    // Verify success toast (from design Frame 3)
    await expect(page.getByText('Database setup created successfully')).toBeVisible()

    // Verify modal closed (from design Frame 3)
    await expect(page.getByRole('dialog')).not.toBeVisible()

    // Verify table updated (from design Frame 3)
    await expect(page.getByRole('row', { name: /test-setup/i })).toBeVisible()
  })

  test('should follow error flow from design spec', async ({ page }) => {
    // Frame 4: Error State
    // Setup: Create a setup first to trigger duplicate error
    await page.goto('/database-setups')
    // ... create first setup ...

    // Try to create duplicate
    await page.getByRole('button', { name: 'Create Setup' }).click()
    await page.getByLabel('Setup ID').fill('test-setup')
    // ... fill other fields ...
    await page.getByRole('button', { name: 'Create Setup' }).click()

    // Verify error message (from design Frame 4)
    await expect(page.getByText('Setup ID already exists')).toBeVisible()

    // Verify modal remains open (from design Frame 4)
    await expect(page.getByRole('dialog')).toBeVisible()

    // Verify form retains values (from design Frame 4)
    await expect(page.getByLabel('Setup ID')).toHaveValue('test-setup')
  })

  test('should support keyboard navigation from accessibility spec', async ({ page }) => {
    // Accessibility Requirements
    await page.goto('/database-setups')
    await page.getByRole('button', { name: 'Create Setup' }).click()

    // Tab through fields (from accessibility spec)
    await page.keyboard.press('Tab')
    await expect(page.getByLabel('Setup ID')).toBeFocused()

    await page.keyboard.press('Tab')
    await expect(page.getByLabel('Host')).toBeFocused()

    // ESC closes modal (from interaction spec)
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).not.toBeVisible()
  })
})
```

## Phase 4: Design Handoff Checklist

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

### Frame 2: [State Name]
- Screenshot: ![Frame 2](./screenshots/frame-2.png)
- Description: [What user sees]
- Elements:
  * Element 1: [Description, position, styling]

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
- **Active**: [Description]
- **Disabled**: [Description]
- **Loading**: [Description]
- **Error**: [Description]
- **Success**: [Description]

### Animations
- Modal open: Fade in, 200ms, ease-out
- Toast: Slide in from top-right, 300ms, ease-out
- Row highlight: Yellow fade, 2s, linear

## Accessibility

### Keyboard Navigation
- Tab: [Behavior]
- Shift+Tab: [Behavior]
- Enter: [Behavior]
- Escape: [Behavior]
- Arrow keys: [Behavior]

### Screen Reader
- Modal title: [ARIA label]
- Form fields: [ARIA labels]
- Error messages: [ARIA live region]
- Success messages: [ARIA live region]

### Focus Management
- On modal open: Focus moves to [element]
- On modal close: Focus returns to [element]
- On error: Focus moves to [first invalid field]

## API Contract

### Endpoint
[Method] [URL]

### Request
```json
{
  "field1": "value",
  "field2": "value"
}
```

### Response - Success
```json
{
  "field1": "value",
  "field2": "value"
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
2. [Step]
3. [Expected result]

### Error Scenarios
1. **Scenario**: [Description]
   - Steps: [Steps to reproduce]
   - Expected: [Error message and behavior]

### Edge Cases
1. **Scenario**: [Description]
   - Steps: [Steps to reproduce]
   - Expected: [Behavior]
```

## Phase 5: Complete Workflow Summary

### The Correct Process

1. **Designer creates mockups and specs** (Figma + interaction docs)
2. **Developer translates to test contract** (TypeScript interface)
3. **Developer writes tests from contract** (Playwright tests)
4. **Developer implements UI** (React components)
5. **Tests validate implementation** (Matches design spec)

### Benefits of This Approach

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

**Zeplin** - Design handoff with specs
**Avocode** - Design to code collaboration
**Figma Dev Mode** - Built-in developer handoff
**Storybook** - Component documentation and testing

### Automated Design Testing

**Percy** - Visual regression testing
**Chromatic** - Visual testing for Storybook
**Playwright Screenshots** - Built-in visual comparison
**Applitools** - AI-powered visual testing

## Summary

**The correct workflow starts with design:**

1. **UI/UX Design** - Figma mockups, interaction specs, user flows
2. **Design Handoff** - Specifications, validation rules, error messages
3. **Test Contracts** - TypeScript interfaces from design specs
4. **Automated Tests** - Playwright tests from contracts
5. **Implementation** - React components guided by tests
6. **Validation** - Tests ensure implementation matches design

**Key Principle:** Design is the source of truth, tests validate implementation matches design.


