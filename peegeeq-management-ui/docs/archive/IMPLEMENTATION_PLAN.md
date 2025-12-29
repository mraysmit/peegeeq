# PeeGeeQ Management UI - Implementation Plan

> **⚠️ ARCHIVED DOCUMENT**
>
> This document is archived as of 2025-12-28. It represents the original 14-19 week plan created in November 2025.
>
> **For current execution plan, see:** `docs-design/design/peegeeq-management-ui/EXECUTION_CHECKLIST.md`
>
> **Why archived:** Significant work has been completed (UI/UX, E2E tests, core features). The remaining work is 4-6 weeks, not 14-19 weeks. This plan is kept for historical reference and future enhancements.

## Document Overview

**Purpose**: Actionable implementation plan for building the PeeGeeQ Management UI
**Based On**: PEEGEEQ_MANAGEMENT_UI_COMPLETE_DESIGN.md v1.0
**Status**: ARCHIVED (see note above)
**Last Updated**: November 2025
**Archived**: December 28, 2025

---

## Executive Summary

This implementation plan breaks down the comprehensive design document into actionable phases, tasks, and deliverables. The plan follows a 4-phase approach over 14-19 weeks, prioritizing critical queue management features first, followed by consumer groups, event store browser, and advanced monitoring features.

### Timeline Overview

| Phase | Duration | Key Focus | Team Size |
|-------|----------|-----------|-----------|
| **Phase 1** | 4-6 weeks | Queue Management | 1 Backend + 1 Frontend + 0.5 Design |
| **Phase 2** | 3-4 weeks | Consumer Groups | 1 Backend + 2 Frontend + 0.5 Design + 1 QA |
| **Phase 3** | 4-5 weeks | Event Store Browser | 1 Backend + 2 Frontend + 0.5 Design + 1 QA |
| **Phase 4** | 3-4 weeks | Advanced Features | 1 Backend + 2 Frontend + 1 QA + 0.25 DevOps |
| **Total** | **14-19 weeks** | **Complete UI** | **Variable** |

---

## Phase 1: Queue Management Enhancement

**Duration**: 4-6 weeks  
**Priority**: Critical  
**Team**: 1 Backend Dev + 1 Frontend Dev + 0.5 UI/UX Designer

### Objectives

Enhance existing queue management to match RabbitMQ's queue management capabilities:
- Comprehensive queue listing and filtering
- Detailed queue statistics and monitoring
- Message browsing (non-destructive)
- Message publishing (test messages)
- Queue configuration management
- Queue operations (purge, delete, move messages)

### Week-by-Week Breakdown

#### Week 1: Foundation & API Design
**Backend Tasks**:
- [ ] Define OpenAPI specification for new queue endpoints
- [ ] Design enhanced queue response model with detailed statistics
- [ ] Implement `GET /api/management/queues` with filtering and sorting
- [ ] Implement `GET /api/management/queues/:setupId/:queueName` with enhanced stats
- [ ] Add database indexes for queue queries

**Frontend Tasks**:
- [ ] Setup Redux Toolkit with RTK Query
- [ ] Create TypeScript types for queue models
- [ ] Build base UI layout with navigation
- [ ] Create shared components: `StatCard`, `FilterBar`, `ConfirmDialog`
- [ ] Setup Ant Design theme customization

**Design Tasks**:
- [ ] Create wireframes for Queue List page
- [ ] Create wireframes for Queue Details page
- [ ] Design component library (cards, charts, forms)

**Deliverables**:
- OpenAPI spec for Phase 1 endpoints
- Base application structure with routing
- Shared UI components library
- Design system mockups

#### Week 2: Queue List & Details Pages
**Backend Tasks**:
- [ ] Implement `GET /api/management/queues/:setupId/:queueName/charts` for time-series data
- [ ] Add queue statistics aggregation logic
- [ ] Implement queue search and filter logic
- [ ] Write unit tests for queue endpoints

**Frontend Tasks**:
- [ ] Build `QueueListPage` with table and filters
- [ ] Implement `QueueDetailsPage` with tab navigation
- [ ] Create `QueueOverviewTab` with statistics cards
- [ ] Build `LineChart` and `AreaChart` components (Recharts)
- [ ] Integrate RTK Query hooks for queue data

**Design Tasks**:
- [ ] High-fidelity mockups for Queue List
- [ ] High-fidelity mockups for Queue Details tabs

**Deliverables**:
- Functional queue list with filtering
- Queue details page with Overview and Charts tabs
- Real-time chart visualizations

#### Week 3: Message Browser & Publisher
**Backend Tasks**:
- [ ] Implement `GET /api/management/queues/:setupId/:queueName/messages`
- [ ] Add message retrieval with acknowledgment modes (auto-ack, manual-ack, no-ack)
- [ ] Implement `POST /api/management/queues/:setupId/:queueName/publish`
- [ ] Add message validation and header support
- [ ] Write integration tests with Testcontainers

**Frontend Tasks**:
- [ ] Build `QueueMessagesTab` component
- [ ] Create `MessageBrowser` with pagination
- [ ] Create `MessagePublisher` form component
- [ ] Integrate Monaco Editor for message payload editing
- [ ] Add JSON syntax highlighting and validation
- [ ] Implement message header display/editing

**Design Tasks**:
- [ ] Mockups for message browser interface
- [ ] Mockups for message publisher form

**Deliverables**:
- Working message browser (get messages)
- Working message publisher (test messages)
- Monaco Editor integration

#### Week 4: Queue Configuration & Operations
**Backend Tasks**:
- [ ] Implement `PATCH /api/management/queues/:setupId/:queueName/config`
- [ ] Add configuration validation logic
- [ ] Implement `DELETE /api/management/queues/:setupId/:queueName?ifEmpty=true`
- [ ] Implement `POST /api/management/queues/:setupId/:queueName/purge`
- [ ] Implement `POST /api/management/queues/:setupId/:queueName/move`

**Frontend Tasks**:
- [ ] Build `QueueConfigEditor` component
- [ ] Create performance preset selector (High-Throughput, Low-Latency, Reliable)
- [ ] Build queue action menu with operations
- [ ] Implement confirmation dialogs for destructive operations
- [ ] Add form validation for configuration

**Deliverables**:
- Queue configuration editor with presets
- Queue operations (purge, delete, move)
- Comprehensive error handling

#### Week 5: Consumer Display & Bindings
**Backend Tasks**:
- [ ] Enhance consumer information in queue details
- [ ] Add consumer activity tracking
- [ ] Implement binding information retrieval

**Frontend Tasks**:
- [ ] Build `QueueConsumersTab` component
- [ ] Display active consumers with statistics
- [ ] Build `QueueBindingsTab` component
- [ ] Add consumer health indicators

**Deliverables**:
- Consumer visualization
- Binding display

#### Week 6: Testing, Polish & Documentation
**Tasks**:
- [ ] Write comprehensive unit tests (target: 80%+ coverage)
- [ ] Write E2E tests for critical workflows (Playwright):
  - Create queue → configure → publish → get message → purge
  - Edit configuration → verify changes persist
  - View charts → change time range → verify data
- [ ] Performance testing with 100+ queues
- [ ] Bug fixes and UI polish
- [ ] API documentation (OpenAPI/Swagger UI)
- [ ] User documentation for queue management

**Deliverables**:
- ✅ Phase 1 complete with tests
- ✅ Documentation
- ✅ Demo-ready queue management features

---

## Phase 2: Consumer Group Visualization

**Duration**: 3-4 weeks  
**Priority**: High  
**Team**: 1 Backend Dev + 2 Frontend Devs + 0.5 UI/UX Designer + 1 QA Engineer

### Objectives

Implement consumer group management with partition assignment visualization:
- Consumer group listing and details
- Member management with health monitoring
- Partition assignment visualizer
- Lag analysis and monitoring
- Manual rebalance capabilities

### Week-by-Week Breakdown

#### Week 7: Consumer Group API & List Page
**Backend Tasks**:
- [ ] Implement `GET /api/consumer-groups/:groupName/partitions`
- [ ] Implement `GET /api/consumer-groups/:groupName/lag`
- [ ] Implement `GET /api/consumer-groups/:groupName/members/:memberId`
- [ ] Add lag calculation logic
- [ ] Add partition assignment tracking

**Frontend Tasks**:
- [ ] Build `ConsumerGroupListPage` component
- [ ] Create consumer group table with filters
- [ ] Add lag indicators and health status
- [ ] Build `ConsumerGroupDetailsPage` with tabs

**Deliverables**:
- Consumer group list with real-time lag
- Consumer group details page structure

#### Week 8: Member Management & Details
**Backend Tasks**:
- [ ] Implement `DELETE /api/consumer-groups/:groupName/members/:memberId`
- [ ] Implement `POST /api/consumer-groups/:groupName/rebalance`
- [ ] Add member health monitoring
- [ ] Add heartbeat tracking

**Frontend Tasks**:
- [ ] Build `GroupMembersTab` component
- [ ] Create `MemberCard` component with details
- [ ] Add member removal functionality
- [ ] Build `GroupOverviewTab` with statistics
- [ ] Add `RebalanceButton` with confirmation

**Deliverables**:
- Member management interface
- Manual rebalance capability

#### Week 9: Partition Visualizer
**Backend Tasks**:
- [ ] Enhance partition endpoint with assignment details
- [ ] Add partition-level lag metrics

**Frontend Tasks**:
- [ ] Build `GroupPartitionsTab` component
- [ ] Create `PartitionGrid` visualizer (color-coded by member)
- [ ] Add partition details on hover/click
- [ ] Implement rebalancing animation
- [ ] Add partition lag indicators

**Design Tasks**:
- [ ] Design partition grid visualization
- [ ] Design color scheme for member assignments

**Deliverables**:
- Interactive partition assignment visualizer
- Visual rebalancing feedback

#### Week 10: Lag Analysis & Configuration
**Backend Tasks**:
- [ ] Implement `PATCH /api/consumer-groups/:groupName/config`
- [ ] Implement `GET /api/metrics/consumer-groups/:groupName`

**Frontend Tasks**:
- [ ] Build `GroupLagTab` component
- [ ] Create `LagChart` with time-series visualization
- [ ] Add per-partition and per-member lag breakdown
- [ ] Build `GroupConfigTab` component
- [ ] Add load balancing strategy selector
- [ ] E2E testing for consumer group workflows

**Deliverables**:
- ✅ Phase 2 complete
- ✅ Comprehensive consumer group management

---

## Phase 3: Event Store Explorer

**Duration**: 4-5 weeks  
**Priority**: High  
**Team**: 1 Backend Dev + 2 Frontend Devs + 0.5 UI/UX Designer + 1 QA Engineer

### Objectives

Implement unique bitemporal event store browser:
- Event store management (list, create, delete)
- Temporal query builder with dual time dimensions
- Bi-temporal timeline visualization
- Event browsing with version history
- Event operations (append, correct)
- Analytics dashboard

### Week-by-Week Breakdown

#### Week 11: Event Store API & List
**Backend Tasks**:
- [ ] Implement `GET /api/event-stores` - List stores
- [ ] Implement `POST /api/event-stores` - Create store
- [ ] Implement `GET /api/event-stores/:storeName` - Get details
- [ ] Implement `DELETE /api/event-stores/:storeName` - Delete store
- [ ] Add event store metadata tracking

**Frontend Tasks**:
- [ ] Build `EventStoreListPage` component
- [ ] Create event store table with statistics
- [ ] Build `EventStoreBrowserPage` with tabs
- [ ] Setup routing for event store pages

**Deliverables**:
- Event store listing and management
- Event store browser shell

#### Week 12: Query Builder & Query Execution
**Backend Tasks**:
- [ ] Implement `POST /api/event-stores/:storeName/query` with all filter options:
  - Event type, aggregate ID, correlation ID
  - Valid time range, transaction time range
  - Header filters, corrections, version range
- [ ] Optimize bitemporal queries with indexes
- [ ] Add pagination support
- [ ] Write comprehensive query tests

**Frontend Tasks**:
- [ ] Build `QueryBuilderTab` component
- [ ] Create `TemporalQueryBuilder` with filter controls
- [ ] Add `TemporalRangePicker` for dual time dimensions
- [ ] Implement query template system (save/load)
- [ ] Add `FilterChips` for active filters
- [ ] Build query execution and results display

**Deliverables**:
- Comprehensive query builder
- Query template management

#### Week 13: Bi-Temporal Timeline Visualization
**Backend Tasks**:
- [ ] Optimize query endpoint for timeline data
- [ ] Add event summarization for large datasets

**Frontend Tasks**:
- [ ] Research and prototype timeline visualization (D3.js)
- [ ] Build `TimelineTab` component
- [ ] Create `BiTemporalTimeline` component:
  - 2D canvas with valid-time (X) and transaction-time (Y)
  - Event markers with color coding
  - Correction chain visualization
  - Interactive zoom and pan
  - Event selection
- [ ] Optimize rendering for 1000+ events

**Design Tasks**:
- [ ] Design timeline visualization mockups
- [ ] Design event marker styles
- [ ] Design correction chain indicators

**Deliverables**:
- Working bi-temporal timeline visualization
- Performance optimization for large datasets

#### Week 14: Event Details & Version History
**Backend Tasks**:
- [ ] Implement `GET /api/event-stores/:storeName/events/:eventId`
- [ ] Implement `GET /api/event-stores/:storeName/events/:eventId/versions`
- [ ] Add version diff calculation

**Frontend Tasks**:
- [ ] Build `EventsTab` with paginated event list
- [ ] Create `EventDetailsModal` component
- [ ] Build `VersionHistoryViewer` component
- [ ] Create `EventDiffViewer` with syntax highlighting
- [ ] Add event metadata display
- [ ] Integrate Monaco Editor for payload viewing

**Deliverables**:
- Event browsing with pagination
- Version history with diff viewer

#### Week 15: Event Operations & Analytics
**Backend Tasks**:
- [ ] Implement `POST /api/event-stores/:storeName/append`
- [ ] Implement `POST /api/event-stores/:storeName/correct`
- [ ] Implement `GET /api/event-stores/:storeName/aggregates/:aggregateId`
- [ ] Implement `GET /api/event-stores/:storeName/analytics`

**Frontend Tasks**:
- [ ] Build `AppendEventForm` component
- [ ] Build `CorrectEventForm` with reason field
- [ ] Build `AnalyticsTab` component
- [ ] Create analytics charts:
  - Event type distribution (pie chart)
  - Event volume over time (line chart)
  - Correction rate analysis
  - Top aggregates by event count
- [ ] E2E testing for event store workflows
- [ ] Performance testing with 10,000+ events

**Deliverables**:
- ✅ Phase 3 complete
- ✅ Unique bitemporal event store browser

---

## Phase 4: Advanced Features

**Duration**: 3-4 weeks  
**Priority**: Medium  
**Team**: 1 Backend Dev + 2 Frontend Devs + 1 QA Engineer + 0.25 DevOps

### Objectives

Add production-ready features:
- WebSocket real-time updates
- Enhanced overview dashboard
- Metrics and monitoring integration
- Alert configuration
- Operational tools

### Week-by-Week Breakdown

#### Week 16: WebSocket Infrastructure
**Backend Tasks**:
- [ ] Implement `WS /api/ws` WebSocket endpoint
- [ ] Create `WebSocketHandler` for connection management
- [ ] Create `SubscriptionManager` for channel subscriptions
- [ ] Create `BroadcastService` for message distribution
- [ ] Implement subscription message types (SUBSCRIBE, UNSUBSCRIBE)
- [ ] Implement data message types (OVERVIEW_UPDATE, QUEUE_UPDATE, etc.)
- [ ] Add PostgreSQL LISTEN/NOTIFY integration for real-time events
- [ ] Add WebSocket connection pooling and throttling

**Frontend Tasks**:
- [ ] Create `WebSocketProvider` context
- [ ] Create `useWebSocket` custom hook
- [ ] Create `useRealTimeUpdates` hook for components
- [ ] Add connection status indicator
- [ ] Implement automatic reconnection logic
- [ ] Add subscription management

**Deliverables**:
- Working WebSocket infrastructure
- Real-time updates for queues

#### Week 17: Enhanced Overview Dashboard
**Backend Tasks**:
- [ ] Implement `GET /api/activity` - Activity log endpoint
- [ ] Implement `GET /api/metrics/summary` - System metrics summary
- [ ] Add activity tracking for all operations

**Frontend Tasks**:
- [ ] Build `OverviewDashboard` component
- [ ] Create statistics cards (6 cards):
  - Total queues
  - Total consumer groups
  - Total event stores
  - Messages/sec
  - Active connections
  - System health
- [ ] Build message rate chart with time range selector
- [ ] Build `ActivityLog` component with real-time updates
- [ ] Add quick action buttons
- [ ] Integrate WebSocket for real-time dashboard updates

**Deliverables**:
- Production-ready overview dashboard
- Real-time activity stream

#### Week 18: Monitoring & Alerts
**Backend Tasks**:
- [ ] Implement `GET /api/metrics` - Prometheus metrics endpoint
- [ ] Implement `POST /api/alerts` - Create alert
- [ ] Implement `GET /api/alerts` - List alerts
- [ ] Implement `PATCH /api/alerts/:alertId` - Update alert
- [ ] Implement `DELETE /api/alerts/:alertId` - Delete alert
- [ ] Add Micrometer custom metrics
- [ ] Setup Prometheus scraping configuration

**Frontend Tasks**:
- [ ] Build `MetricsDashboard` component
- [ ] Build `AlertManager` component
- [ ] Create alert configuration form
- [ ] Add threshold monitoring visualizations
- [ ] Create Grafana dashboard JSON exports

**DevOps Tasks**:
- [ ] Setup Prometheus scraping
- [ ] Configure Alertmanager
- [ ] Create Grafana dashboards
- [ ] Document monitoring setup

**Deliverables**:
- Prometheus metrics integration
- Alert configuration UI
- Grafana dashboards

#### Week 19: Polish, Testing & Documentation
**Backend Tasks**:
- [ ] Performance optimization
- [ ] Security review (rate limiting, validation)
- [ ] API documentation finalization

**Frontend Tasks**:
- [ ] UI/UX polish and consistency review
- [ ] Accessibility testing (WCAG 2.1 Level AA)
- [ ] Responsive design testing
- [ ] Browser compatibility testing (Chrome, Firefox, Safari, Edge)
- [ ] Performance optimization (code splitting, lazy loading)

**QA Tasks**:
- [ ] Comprehensive E2E test suite
- [ ] Load testing (k6/JMeter)
- [ ] WebSocket scalability testing
- [ ] Security testing

**Documentation Tasks**:
- [ ] User guide for management UI
- [ ] API documentation (OpenAPI/Swagger)
- [ ] Deployment guide
- [ ] Operations runbook

**Deliverables**:
- ✅ Phase 4 complete
- ✅ Production-ready management UI
- ✅ Complete documentation

---

## Cross-Phase Technical Tasks

### Continuous Integration/Continuous Deployment

**Setup (Week 1-2)**:
- [ ] Configure build pipeline for frontend (npm build)
- [ ] Configure build pipeline for backend (Maven)
- [ ] Setup Dockerfile for containerized deployment
- [ ] Configure Docker Compose for local development
- [ ] Setup automated testing in CI pipeline

**Ongoing**:
- [ ] Automated builds on every commit
- [ ] Automated testing (unit, integration, E2E)
- [ ] Code quality checks (ESLint, SonarQube)
- [ ] Automated deployments to dev/staging environments

### Security

**Phase 1-2**:
- [ ] HTTPS/TLS configuration
- [ ] CORS configuration
- [ ] Input validation framework
- [ ] Rate limiting implementation

**Phase 3-4**:
- [ ] JWT authentication (optional, for production)
- [ ] RBAC implementation (optional)
- [ ] CSRF protection
- [ ] WebSocket authentication
- [ ] Security audit and penetration testing

### Performance

**Ongoing**:
- [ ] Database query optimization (EXPLAIN ANALYZE)
- [ ] Database indexing strategy
- [ ] Frontend bundle optimization
- [ ] Code splitting and lazy loading
- [ ] Caching strategy (RTK Query + backend)
- [ ] Virtual scrolling for large lists

**Load Testing** (Week 10, 15, 19):
- [ ] API load testing (k6)
- [ ] WebSocket scalability testing
- [ ] Database performance testing
- [ ] Frontend performance testing (Lighthouse)

---

## Dependencies & Prerequisites

### External Dependencies

**Backend**:
- Java 21+
- Maven 3.9+
- Vert.x 5.x (existing)
- PostgreSQL 14+ (existing)
- Micrometer (existing)

**Frontend**:
- Node.js 18+
- npm 9+
- React 18+
- Ant Design 5+
- Redux Toolkit
- Recharts
- D3.js (for timeline)
- Monaco Editor

### Development Environment

- [ ] PostgreSQL database setup with PeeGeeQ schema
- [ ] Java development environment
- [ ] Node.js development environment
- [ ] Git repository access
- [ ] Development servers (backend port 8080, frontend port 5173)
- [ ] Code editor (VS Code recommended)

### Team Prerequisites

**Backend Developer**:
- Java 21, Vert.x experience
- PostgreSQL, SQL expertise
- REST API design
- WebSocket experience

**Frontend Developer**:
- React 18, TypeScript expertise
- Redux Toolkit, RTK Query
- Ant Design components
- Charting libraries (Recharts, D3.js)
- WebSocket client development

**UI/UX Designer**:
- Figma or similar tool
- Component library design
- Responsive design
- Data visualization design

**QA Engineer**:
- Playwright E2E testing
- API testing (Postman, REST Assured)
- Performance testing (k6, JMeter)
- Test automation

---

## Risk Management

### Technical Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Performance issues with large datasets (1000+ queues, 10K+ events) | Medium | High | - Implement pagination and virtual scrolling<br>- Use canvas rendering for timeline<br>- Optimize queries with indexes<br>- Load test early and often |
| WebSocket scalability issues with concurrent users | Medium | High | - Connection pooling<br>- Broadcast for common subscriptions<br>- Backpressure handling<br>- Early load testing |
| Complex bitemporal query performance | Medium | Medium | - Efficient database schema with indexes<br>- Query optimization<br>- Query templates for common patterns<br>- Caching |
| Browser compatibility issues | Low | Medium | - Cross-browser testing<br>- Polyfills for older browsers<br>- Progressive enhancement |
| Security vulnerabilities | Low | Critical | - Security best practices<br>- Input validation<br>- HTTPS/TLS<br>- Penetration testing |

### Schedule Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Scope creep during development | High | High | - Strict phase boundaries<br>- Change request process<br>- Regular stakeholder reviews<br>- MVP-first approach |
| Backend API delays blocking frontend | Medium | High | - API-first design with contracts<br>- Mock API server for frontend<br>- Parallel development<br>- Regular integration checkpoints |
| Underestimated complexity of timeline visualization | Medium | Medium | - Technical spike in Week 13<br>- Alternative simpler design as backup<br>- Early prototyping |
| Resource availability issues | Medium | Medium | - Cross-training team members<br>- Documentation<br>- Knowledge sharing sessions |
| Integration issues between phases | Low | Medium | - Continuous integration<br>- Integration tests<br>- Regular demos |

### Resource Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Developer turnover | Low | High | - Comprehensive documentation<br>- Code reviews<br>- Knowledge sharing<br>- Pair programming |
| Insufficient QA resources | Medium | Medium | - Start QA in Phase 2<br>- Automate testing early<br>- Developers write tests |
| Design bottleneck | Medium | Medium | - Batch design work<br>- Design system early<br>- Frontend devs contribute to design |

---

## Success Criteria

### Phase 1 Success Criteria
- [ ] Queue list displays all queues with accurate statistics
- [ ] Queue details show comprehensive information across all tabs
- [ ] Message browser retrieves messages without permanent consumption
- [ ] Message publisher sends test messages successfully
- [ ] Queue configuration can be edited and applied
- [ ] Charts display accurate time-series data
- [ ] All operations handle errors gracefully
- [ ] Unit test coverage ≥ 80%
- [ ] E2E tests pass for critical workflows
- [ ] UI performs well with 100+ queues

### Phase 2 Success Criteria
- [ ] Consumer group list displays all groups with accurate lag metrics
- [ ] Partition visualizer accurately represents assignments
- [ ] Member management allows viewing and removing members
- [ ] Lag analysis shows accurate metrics per partition and member
- [ ] Rebalancing can be triggered and completes successfully
- [ ] Configuration updates persist and take effect
- [ ] Real-time updates reflect member joins/leaves
- [ ] UI handles groups with 100+ partitions efficiently
- [ ] Unit test coverage ≥ 80%
- [ ] E2E tests pass for consumer group workflows

### Phase 3 Success Criteria
- [ ] Query builder supports all filter types and temporal dimensions
- [ ] Timeline visualizes events on both temporal dimensions
- [ ] Event list displays query results with pagination
- [ ] Event details show complete metadata and payload
- [ ] Version history displays correction chains
- [ ] Diff viewer highlights changes between versions
- [ ] Analytics show accurate statistics
- [ ] New events can be appended successfully
- [ ] Corrections can be created with reason
- [ ] UI handles 10,000+ events efficiently
- [ ] Timeline performs well with 1,000+ events
- [ ] Unit test coverage ≥ 80%
- [ ] E2E tests pass for event store workflows

### Phase 4 Success Criteria
- [ ] WebSocket connections provide real-time updates reliably
- [ ] Overview dashboard shows accurate system-wide statistics
- [ ] Activity log displays recent actions in real-time
- [ ] Alerts can be configured and trigger correctly
- [ ] Prometheus metrics are exported correctly
- [ ] Grafana dashboards display relevant metrics
- [ ] Connection status is clearly indicated
- [ ] Automatic reconnection works reliably
- [ ] UI remains responsive during high update frequency
- [ ] Load testing passes (100 concurrent users)
- [ ] Security review passes
- [ ] Complete documentation available
- [ ] Production deployment successful

---

## Deployment Strategy

### Embedded UI Approach (Recommended for MVP)

The frontend build will be embedded in the backend JAR for simplified deployment:

```bash
# Build frontend
cd peegeeq-management-ui
npm run build

# Copy to backend resources
cp -r dist/* ../peegeeq-rest/src/main/resources/webroot/

# Build backend with embedded UI
cd ../peegeeq-rest
mvn clean package

# Result: peegeeq-rest-1.0.0-fat.jar contains both API and UI
```

**Vert.x Static Handler Configuration**:
```java
router.route("/*")
  .handler(StaticHandler.create("webroot")
    .setCachingEnabled(true)
    .setMaxAgeSeconds(86400));
```

### Container Deployment

**Multi-stage Dockerfile**:
```dockerfile
# Stage 1: Build frontend
FROM node:18 AS frontend-build
WORKDIR /app/ui
COPY peegeeq-management-ui/package*.json ./
RUN npm ci
COPY peegeeq-management-ui/ ./
RUN npm run build

# Stage 2: Build backend
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /app
COPY pom.xml ./
COPY peegeeq-*/pom.xml ./peegeeq-*/
RUN mvn dependency:go-offline
COPY . ./
COPY --from=frontend-build /app/ui/dist ./peegeeq-rest/src/main/resources/webroot/
RUN mvn clean package -DskipTests

# Stage 3: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend-build /app/peegeeq-rest/target/peegeeq-rest-*-fat.jar ./app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Deployment Environments

**Development**:
- Frontend: `npm run dev` (Vite dev server on port 5173)
- Backend: Run from IDE or `mvn exec:java` (port 8080)
- CORS enabled for cross-origin requests

**Staging**:
- Embedded UI in backend JAR
- Docker container deployment
- PostgreSQL database
- HTTPS with self-signed certificate

**Production**:
- Embedded UI in backend JAR
- Kubernetes deployment (3 replicas)
- Managed PostgreSQL (Azure/AWS)
- HTTPS with valid certificate
- Load balancer
- Prometheus + Grafana monitoring

---

## Testing Strategy

### Unit Testing

**Backend** (JUnit 5 DO NOT USE Mockito):
- [ ] Test all request handlers
- [ ] Test all service methods
- [ ] Test data models and validation
- [ ] Target: 80%+ code coverage

**Frontend** (Vitest + React Testing Library):
- [ ] Test all React components
- [ ] Test Redux slices and reducers
- [ ] Test custom hooks
- [ ] Test utility functions
- [ ] Target: 80%+ code coverage

### Integration Testing

**Backend** (Testcontainers):
- [ ] Test all REST endpoints against real PostgreSQL
- [ ] Test WebSocket connections
- [ ] Test database queries and transactions
- [ ] Test error handling

**Frontend** (MSW - Mock Service Worker):
- [ ] Mock API responses
- [ ] Test API integration
- [ ] Test error scenarios
- [ ] Test loading states

### E2E Testing (Playwright)

**Phase 1 Tests**:
- [ ] Create queue → configure → publish message → get message → purge
- [ ] Edit queue configuration → verify changes persist
- [ ] View queue charts → change time range → verify data updates

**Phase 2 Tests**:
- [ ] Create consumer group → join members → view partitions → trigger rebalance
- [ ] Monitor lag → identify slow consumer → remove member
- [ ] Update group configuration → verify changes

**Phase 3 Tests**:
- [ ] Build query → execute → view results → select event → view details
- [ ] Create correction → view version history → compare versions with diff
- [ ] Append event → query for it → verify it appears in timeline

**Phase 4 Tests**:
- [ ] Connect WebSocket → subscribe to updates → verify real-time data
- [ ] Configure alert → trigger condition → verify notification
- [ ] View activity log → verify recent actions appear in real-time

### Performance Testing (k6)

**Load Tests**:
- [ ] API endpoint load test (100 concurrent users)
- [ ] WebSocket connection test (100 concurrent connections)
- [ ] Database query performance test
- [ ] Frontend performance test (Lighthouse)

**Performance Targets**:
- API response time: < 200ms (p95)
- WebSocket message latency: < 100ms
- Frontend initial load: < 3s
- Time to interactive: < 5s
- Lighthouse score: > 90

---

## Documentation Deliverables

### User Documentation
- [ ] **User Guide**: Complete guide to using the management UI
- [ ] **Getting Started**: Quick start guide for new users
- [ ] **Feature Guides**: Detailed guides for each major feature
- [ ] **Video Tutorials**: Screen recordings demonstrating key workflows

### Developer Documentation
- [ ] **API Reference**: OpenAPI/Swagger documentation for all endpoints
- [ ] **Architecture Guide**: System architecture and design decisions
- [ ] **Development Setup**: Instructions for setting up dev environment
- [ ] **Contributing Guide**: Guidelines for contributing to the project

### Operations Documentation
- [ ] **Deployment Guide**: Instructions for deploying the management UI
- [ ] **Configuration Reference**: All configuration options explained
- [ ] **Monitoring Guide**: How to monitor the management UI
- [ ] **Operations Runbook**: Troubleshooting and common operations
- [ ] **Security Guide**: Security best practices and configuration

---

## Milestones & Checkpoints

### Week 3 Checkpoint
**Review**: Queue list and details pages  
**Demo**: Show queue browsing, filtering, and charts  
**Decision**: Proceed to message operations or adjust design

### Week 6 Milestone
**Review**: Complete Phase 1  
**Demo**: End-to-end queue management workflow  
**Decision**: Sign-off on Phase 1 before starting Phase 2

### Week 10 Milestone
**Review**: Complete Phase 2  
**Demo**: Consumer group visualization and partition assignment  
**Decision**: Sign-off on Phase 2 before starting Phase 3

### Week 13 Checkpoint
**Review**: Bitemporal timeline visualization prototype  
**Demo**: Timeline with sample events  
**Decision**: Validate approach or adjust design

### Week 15 Milestone
**Review**: Complete Phase 3  
**Demo**: Event store query, timeline, and corrections workflow  
**Decision**: Sign-off on Phase 3 before starting Phase 4

### Week 19 Milestone
**Review**: Complete Phase 4  
**Demo**: Real-time updates, monitoring, and production-ready features  
**Decision**: Production deployment approval

---

## Resource Allocation

### Backend Developer (1 FTE)
- **Phase 1**: API development, queue operations (6 weeks)
- **Phase 2**: Consumer group APIs, partition management (4 weeks)
- **Phase 3**: Event store APIs, bitemporal queries (5 weeks)
- **Phase 4**: WebSocket, metrics, monitoring (4 weeks)

### Frontend Developer 1 (1 FTE, all phases)
- **Phase 1**: Core UI, queue pages, shared components (6 weeks)
- **Phase 2**: Consumer group pages, partition visualizer (4 weeks)
- **Phase 3**: Event store pages, query builder (5 weeks)
- **Phase 4**: Dashboard, real-time updates, WebSocket (4 weeks)

### Frontend Developer 2 (1 FTE, Phases 2-4)
- **Phase 2**: Consumer group components, lag analysis (4 weeks)
- **Phase 3**: Timeline visualization, event operations (5 weeks)
- **Phase 4**: Monitoring, alerts, polish (4 weeks)

### UI/UX Designer (0.5 FTE)
- **Ongoing**: Wireframes, mockups, design system, user feedback

### QA Engineer (1 FTE, Phases 2-4)
- **Phase 2**: Test planning, E2E test setup (4 weeks)
- **Phase 3**: E2E test expansion, performance testing (5 weeks)
- **Phase 4**: Load testing, security testing, final QA (4 weeks)

### DevOps Engineer (0.25 FTE, all phases)
- **Ongoing**: CI/CD, deployment, monitoring setup

---

## Next Steps

### Immediate Actions (Before Week 1)
1. [ ] **Stakeholder Approval**: Review and approve this implementation plan
2. [ ] **Team Assembly**: Hire or assign team members
3. [ ] **Environment Setup**: Setup development environments for all team members
4. [ ] **Design Kickoff**: Designer creates initial wireframes for Phase 1
5. [ ] **API Contract Definition**: Define OpenAPI specifications for Phase 1 endpoints
6. [ ] **Repository Setup**: Create branches, setup CI/CD pipeline
7. [ ] **Project Board**: Setup project tracking (Jira, GitHub Projects, etc.)

### Week 1 Kickoff
1. **Team Kickoff Meeting**: Review plan, assign tasks, set expectations
2. **Design Review**: Review Phase 1 wireframes with team
3. **API Contract Review**: Finalize Phase 1 API specifications
4. **Sprint Planning**: Plan Week 1 tasks in detail
5. **Development Start**: Begin implementation

---

## Appendix

### Key Decisions Log

| Decision | Rationale | Date | Status |
|----------|-----------|------|--------|
| Use embedded UI deployment | Simplifies deployment for MVP, single JAR | TBD | Proposed |
| React + Ant Design for frontend | Mature ecosystem, comprehensive components | TBD | Proposed |
| Redux Toolkit + RTK Query | State management + data fetching with caching | TBD | Proposed |
| D3.js for bitemporal timeline | Most flexible for custom visualizations | TBD | Proposed |
| WebSocket for real-time updates | Bidirectional, better performance than SSE | TBD | Proposed |
| JWT authentication (Phase 4) | Stateless, scalable, production-ready | TBD | Proposed |
| No authentication for MVP | Faster development, suitable for internal tools | TBD | Proposed |

### Glossary

- **PeeGeeQ**: PostgreSQL-based message queue system
- **Native Queue**: Low-latency queue using PostgreSQL LISTEN/NOTIFY
- **Outbox Pattern**: Transactional queue for at-least-once delivery
- **Bitemporal**: Two temporal dimensions (valid-time + transaction-time)
- **Consumer Group**: Set of consumers sharing message processing load
- **Partition**: Unit of parallelism for message distribution
- **Lag**: Number of unprocessed messages for a consumer or group
- **Event Store**: Append-only storage for events with temporal queries
- **Correction**: A new event version that corrects a previous event

---

**Document Version**: 1.0  
**Last Updated**: November 2025  
**Status**: Draft - Ready for Review
