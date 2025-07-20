# PeeGeeQ Management UI - Testing Guide

This document provides comprehensive testing strategies and instructions for the PeeGeeQ Management UI.

## 🧪 Testing Levels

### 1. Manual Integration Testing (Immediate)

**Prerequisites:**
- Backend REST API running on http://localhost:8080
- Frontend dev server running on http://localhost:3000

**Quick Start:**
```bash
# Terminal 1: Start Backend
cd peegeeq-rest
mvn exec:java -Dexec.mainClass="dev.mars.peegeeq.rest.PeeGeeQRestServer" -Dexec.args="8080"

# Terminal 2: Start Frontend
cd peegeeq-management-ui
npm run dev

# Terminal 3: Test API
curl http://localhost:8080/health
curl http://localhost:8080/api/v1/health
```

**Manual Test Checklist:**
- [ ] Backend health endpoints respond correctly
- [ ] Frontend loads at http://localhost:3000
- [ ] Navigation between pages works
- [ ] API calls from frontend to backend succeed
- [ ] CORS is properly configured
- [ ] Error states are handled gracefully
- [ ] Loading states are displayed
- [ ] Data is displayed correctly in tables and charts

### 2. Automated Integration Testing

**Run Integration Tests:**
```bash
npm run test:integration
```

**What it tests:**
- ✅ Backend API connectivity
- ✅ CORS configuration
- ✅ API response formats
- ✅ Error handling
- ✅ Performance (response times)
- ✅ Data validation
- ✅ Concurrent request handling

**Current Status:** ✅ 15/15 tests passing

### 3. End-to-End Testing

**Run E2E Tests:**
```bash
# Install Playwright browsers (first time only)
npx playwright install

# Run E2E tests
npm run test:e2e

# Run with UI (interactive)
npm run test:e2e:ui

# Debug mode
npm run test:e2e:debug
```

**What it tests:**
- 🌐 Complete user workflows
- 🖱️ UI interactions and navigation
- 📱 Responsive design (mobile/tablet)
- ♿ Accessibility features
- 🔄 Loading and error states
- 🎯 Cross-browser compatibility

## 📋 Testing Scenarios

### Core Functionality Tests

#### 1. System Overview Dashboard
- [ ] Statistics cards display correct data
- [ ] Charts render properly (when recharts is installed)
- [ ] Recent activity table shows data
- [ ] Connection status indicator works
- [ ] Auto-refresh functionality

#### 2. Queue Management
- [ ] Queue list loads and displays
- [ ] Create queue functionality
- [ ] Queue filtering and search
- [ ] Queue statistics are accurate
- [ ] Queue actions (pause, resume, delete)

#### 3. Consumer Groups
- [ ] Consumer group list displays
- [ ] Group member information
- [ ] Rebalancing operations
- [ ] Group creation and management
- [ ] Load balancing strategy configuration

#### 4. Event Stores
- [ ] Event store list and details
- [ ] Event querying with filters
- [ ] Bi-temporal event browsing
- [ ] Event metadata display
- [ ] Aggregate type filtering

#### 5. Message Browser
- [ ] Message list with pagination
- [ ] Message search and filtering
- [ ] Message detail inspection
- [ ] Advanced search functionality
- [ ] Message acknowledgment

### Error Handling Tests

#### Network Errors
- [ ] Backend unavailable
- [ ] Timeout handling
- [ ] Partial API failures
- [ ] Connection recovery

#### Data Validation
- [ ] Invalid API responses
- [ ] Missing required fields
- [ ] Type mismatches
- [ ] Empty states

### Performance Tests

#### Load Testing
- [ ] Large dataset handling
- [ ] Table pagination performance
- [ ] Real-time updates
- [ ] Memory usage optimization

#### Response Times
- [ ] Initial page load < 2s
- [ ] API calls < 500ms
- [ ] Navigation < 100ms
- [ ] Search results < 1s

### Accessibility Tests

#### Keyboard Navigation
- [ ] Tab order is logical
- [ ] All interactive elements accessible
- [ ] Focus indicators visible
- [ ] Keyboard shortcuts work

#### Screen Reader Support
- [ ] Proper ARIA labels
- [ ] Semantic HTML structure
- [ ] Alternative text for images
- [ ] Table headers properly associated

### Cross-Browser Tests

#### Desktop Browsers
- [ ] Chrome (latest)
- [ ] Firefox (latest)
- [ ] Safari (latest)
- [ ] Edge (latest)

#### Mobile Browsers
- [ ] Mobile Chrome
- [ ] Mobile Safari
- [ ] Mobile Firefox

## 🚀 Advanced Testing

### Load Testing with Artillery

```bash
# Install Artillery
npm install -g artillery

# Create load test config
cat > load-test.yml << EOF
config:
  target: 'http://localhost:8080'
  phases:
    - duration: 60
      arrivalRate: 10
scenarios:
  - name: "API Load Test"
    requests:
      - get:
          url: "/health"
      - get:
          url: "/api/v1/health"
      - get:
          url: "/api/v1/management/overview"
EOF

# Run load test
artillery run load-test.yml
```

### Security Testing

```bash
# Test CORS configuration
curl -H "Origin: http://malicious-site.com" \
     -H "Access-Control-Request-Method: GET" \
     -H "Access-Control-Request-Headers: X-Requested-With" \
     -X OPTIONS \
     http://localhost:8080/api/v1/health

# Test for common vulnerabilities
curl -X POST http://localhost:8080/api/v1/health \
     -H "Content-Type: application/json" \
     -d '{"test": "<script>alert(1)</script>"}'
```

### API Contract Testing

```bash
# Test API response schemas
curl http://localhost:8080/api/v1/management/overview | jq '.'
curl http://localhost:8080/api/v1/management/queues | jq '.'
curl http://localhost:8080/metrics
```

## 📊 Test Coverage

### Current Coverage
- **Integration Tests:** ✅ 15/15 passing
- **E2E Tests:** 🔄 Ready to run
- **Manual Tests:** 📋 Checklist provided
- **Performance Tests:** 🎯 Guidelines provided

### Coverage Goals
- **Unit Tests:** 80%+ code coverage
- **Integration Tests:** 100% API endpoints
- **E2E Tests:** 100% user workflows
- **Cross-browser:** 95%+ compatibility

## 🔧 Test Environment Setup

### Local Development
```bash
# Start all services
docker-compose up -d postgres  # If using Docker
mvn exec:java -pl peegeeq-rest -Dexec.mainClass="dev.mars.peegeeq.rest.PeeGeeQRestServer"
npm run dev
```

### CI/CD Pipeline
```yaml
# Example GitHub Actions workflow
name: Test Suite
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-node@v3
      - run: npm ci
      - run: npm run test:integration
      - run: npm run test:e2e
```

## 🐛 Debugging Tests

### Common Issues
1. **CORS Errors:** Check backend CORS configuration
2. **Timeout Issues:** Increase test timeouts
3. **Flaky Tests:** Add proper wait conditions
4. **Data Dependencies:** Use mock data or test fixtures

### Debug Commands
```bash
# Debug integration tests
npm run test:integration -- --reporter=verbose

# Debug E2E tests
npm run test:e2e:debug

# Check test coverage
npm run test:coverage
```

## 📈 Continuous Improvement

### Metrics to Track
- Test execution time
- Test failure rate
- Code coverage percentage
- Performance regression detection

### Regular Tasks
- [ ] Update test data monthly
- [ ] Review and update test scenarios
- [ ] Performance baseline updates
- [ ] Cross-browser compatibility checks

---

## 🎯 Quick Testing Commands

```bash
# Full test suite
npm run test:run && npm run test:integration && npm run test:e2e

# Development testing
npm run test:integration  # Fast feedback
npm run dev               # Manual testing

# Production readiness
npm run build && npm run test:e2e
```

This comprehensive testing strategy ensures the PeeGeeQ Management UI is robust, reliable, and ready for production use! 🚀
