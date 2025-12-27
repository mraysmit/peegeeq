# Database Setup Management UI - Proposal

## Problem Statement

Currently, creating database setups requires:
1. Manual API calls via curl/PowerShell
2. Knowledge of exact JSON structure
3. Understanding of database permissions
4. No visibility into existing setups
5. No way to manage setups through the UI

**This is a critical gap** - users should be able to manage database setups through the UI, not command-line tools.

---

## Proposed Solution

### 1. Database Setups Page

**Location:** New menu item "Database Setups" or enhance existing "Settings" page

**Features:**
- List all existing setups
- View setup details (queues, event stores, status)
- Create new setups
- Delete setups
- Test database connections

### 2. Create Setup Wizard

**Multi-step form:**

#### Step 1: Setup Information
- Setup ID (unique identifier)
- Description (optional)
- Environment (production/staging/development)

#### Step 2: Database Configuration
- **Connection Details:**
  - Host (default: localhost)
  - Port (default: 5432)
  - Database Name
  - Username
  - Password (masked input)
  - Schema (default: public)
  
- **Advanced Options:**
  - SSL Enabled (checkbox)
  - Template Database (default: template0)
  - Encoding (default: UTF8)
  
- **Connection Pool:**
  - Min Size (default: 8)
  - Max Size (default: 32)
  - Connection Timeout (ms)
  - Idle Timeout (ms)

- **Test Connection Button:**
  - Validates credentials before proceeding
  - Shows success/error message
  - Checks if database exists or needs to be created

#### Step 3: Initial Queues (Optional)
- Add queues to create with the setup
- Queue name, batch size, polling interval
- Can skip and add queues later

#### Step 4: Initial Event Stores (Optional)
- Add event stores to create with the setup
- Event store name, table name, aggregate type
- Can skip and add event stores later

#### Step 5: Review & Create
- Summary of all settings
- Warning if database will be created
- Confirmation checkbox
- Create button

### 3. Setup Management Features

**Setup List View:**
```
┌─────────────────────────────────────────────────────────────┐
│ Database Setups                          [+ Create Setup]   │
├─────────────────────────────────────────────────────────────┤
│ Setup ID       │ Database      │ Queues │ Status  │ Actions│
├─────────────────────────────────────────────────────────────┤
│ production     │ peegeeq_prod  │   12   │ Active  │ ⋮      │
│ staging        │ peegeeq_stage │    8   │ Active  │ ⋮      │
│ e2e-test-setup │ peegeeq_e2e   │    2   │ Active  │ ⋮      │
└─────────────────────────────────────────────────────────────┘
```

**Actions Menu:**
- View Details
- Test Connection
- Add Queue
- Add Event Store
- Delete Setup (with confirmation)

**Setup Details Modal:**
- Connection information (password hidden)
- List of queues in this setup
- List of event stores in this setup
- Status and health information
- Created date, last modified

---

## Implementation Plan

### Phase 1: Basic Setup Management (Week 1)

**Backend (Already Exists):**
- ✅ `POST /api/v1/database-setup/create`
- ✅ `GET /api/v1/setups`
- ✅ `GET /api/v1/setups/:setupId`
- ✅ `DELETE /api/v1/setups/:setupId`

**Frontend (New):**
1. Create `DatabaseSetups.tsx` page
2. Add menu item for "Database Setups"
3. Implement setup list view
4. Implement basic create setup form
5. Implement delete setup with confirmation

### Phase 2: Enhanced Features (Week 2)

1. Multi-step wizard for setup creation
2. Connection testing before creation
3. Setup details modal
4. Edit setup configuration
5. Health status indicators

### Phase 3: Advanced Features (Week 3)

1. Import/export setup configurations
2. Clone setup (copy configuration)
3. Setup templates (pre-configured setups)
4. Migration tools (move queues between setups)
5. Backup/restore functionality

---

## User Workflows

### Workflow 1: First-Time Setup

1. User opens PeeGeeQ Management UI
2. Sees "No setups found" message
3. Clicks "Create Your First Setup" button
4. Wizard guides through:
   - Naming the setup
   - Entering database credentials
   - Testing connection
   - Optionally adding initial queues
5. Setup created successfully
6. User can now create queues and event stores

### Workflow 2: E2E Testing Setup

1. Developer opens "Database Setups" page
2. Clicks "Create Setup" button
3. Enters:
   - Setup ID: `e2e-test-setup`
   - Database Name: `peegeeq_e2e_test` (or auto-generated)
   - Uses default credentials
4. Clicks "Test Connection"
5. System shows: "Database doesn't exist - will be created"
6. Adds 2 test queues in wizard
7. Clicks "Create Setup"
8. System creates database, runs migrations, creates queues
9. Success! Ready for E2E testing

### Workflow 3: Production Setup

1. Admin opens "Database Setups" page
2. Clicks "Create Setup" button
3. Enters production database credentials
4. Enables SSL
5. Configures connection pool for high throughput
6. Tests connection - success
7. Skips initial queues (will add later)
8. Reviews configuration
9. Creates setup
10. Adds queues one by one through UI

---

## Benefits

### For Users
- ✅ No command-line knowledge required
- ✅ Visual feedback and validation
- ✅ Test connections before committing
- ✅ See all setups at a glance
- ✅ Manage everything in one place

### For Developers
- ✅ Easy E2E test setup
- ✅ Quick environment switching
- ✅ No manual SQL or scripts
- ✅ Reproducible configurations

### For Operations
- ✅ Audit trail of setup changes
- ✅ Health monitoring
- ✅ Centralized management
- ✅ Reduced errors

---

## Technical Considerations

### Database Permissions

**Current Issue:** Creating a setup requires `CREATEDB` permission

**UI Solutions:**

1. **Permission Check:**
   - Test connection shows if user has CREATEDB permission
   - If not, show clear error message with instructions
   - Provide SQL command to grant permission

2. **Two Modes:**
   - **Create New Database:** Requires CREATEDB permission
   - **Use Existing Database:** Only requires CONNECT permission
   - User selects mode in wizard

3. **Admin Assistance:**
   - "Request Admin Help" button
   - Generates email/ticket with required permissions
   - Shows exact SQL commands needed

### Security

1. **Password Storage:**
   - Never store passwords in browser
   - Send directly to backend
   - Backend stores encrypted

2. **Connection Testing:**
   - Test connection without creating database
   - Validate credentials
   - Check permissions

3. **Access Control:**
   - Only admins can create/delete setups
   - Regular users can view setups
   - Role-based permissions

### Error Handling

1. **Clear Error Messages:**
   - "Database already exists"
   - "Insufficient permissions - need CREATEDB"
   - "Connection failed - check credentials"
   - "Invalid database name"

2. **Helpful Suggestions:**
   - Show SQL commands to fix issues
   - Link to documentation
   - Provide troubleshooting steps

---

## Mockup (Text-Based)

```
┌────────────────────────────────────────────────────────────────┐
│ Create Database Setup                                    [X]   │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│ Step 1 of 5: Setup Information                                │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│                                                                │
│ Setup ID *                                                     │
│ ┌────────────────────────────────────────────────────────┐   │
│ │ e2e-test-setup                                         │   │
│ └────────────────────────────────────────────────────────┘   │
│                                                                │
│ Description                                                    │
│ ┌────────────────────────────────────────────────────────┐   │
│ │ Setup for E2E testing                                  │   │
│ └────────────────────────────────────────────────────────┘   │
│                                                                │
│ Environment                                                    │
│ ┌────────────────────────────────────────────────────────┐   │
│ │ Development ▼                                          │   │
│ └────────────────────────────────────────────────────────┘   │
│                                                                │
│                                    [Cancel]  [Next Step →]    │
└────────────────────────────────────────────────────────────────┘
```

---

## Recommendation

**Implement Phase 1 immediately** to unblock E2E testing and improve user experience.

This is a **critical feature** that should have been part of the initial UI. Without it, the system is not truly self-service and requires technical knowledge that most users don't have.

**Estimated Effort:**
- Phase 1: 2-3 days
- Phase 2: 3-4 days
- Phase 3: 5-7 days

**Priority: HIGH** - This directly impacts usability and E2E testing capability.

