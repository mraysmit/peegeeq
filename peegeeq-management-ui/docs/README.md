# PeeGeeQ Management Console

A modern, web-based administration interface for PeeGeeQ message queue system, inspired by RabbitMQ's excellent management console design.

## ⚠️ Production Readiness Status

**Current Status:** 🔴 **NOT PRODUCTION READY**
**Estimated Work Required:** 4-6 weeks

**Critical Gaps:**
- Backend endpoints return placeholder/empty data for key features
- Missing authentication and authorization
- Incomplete integration test coverage
- No production deployment strategy

**📋 See:** [**PRODUCTION_READINESS.md**](PRODUCTION_READINESS.md) - Complete assessment, gap analysis, and 6-week implementation plan

---

## Features

### **Implemented (Phase 5.1)**
- **Modern React UI** - Built with React 18, TypeScript, and Ant Design
- **System Overview Dashboard** - Real-time metrics and system health
- **Queue Management** - Complete CRUD operations for queues (UI complete, backend partial)
- **Responsive Design** - Works on desktop, tablet, and mobile
- **Real-time Connection Status** - Live connection monitoring
- **Clean Navigation** - Inspired by RabbitMQ's intuitive sidebar design
- **Comprehensive E2E Tests** - 178 Playwright tests covering major workflows

### **Partially Implemented**
- **Queue Details Page** - UI complete, backend endpoints need implementation:
  - Consumer tracking (returns empty array)
  - Message browsing (returns empty array)
  - Queue purge (placeholder)
  - Bindings management (returns empty array)

### **Coming Soon (Phase 5.2+)**
- **Message Browser** - Visual message inspection and debugging
- **Consumer Group Management** - Visual consumer group coordination
- **Event Store Explorer** - Advanced event querying interface
- **Schema Registry** - Message schema management and validation
- **Developer Portal** - Interactive API documentation and testing
- **Visual Queue Designer** - Drag-and-drop queue configuration
- **Real-time Monitoring** - Live dashboards with WebSocket updates

## Architecture

### **Technology Stack**
- **Frontend**: React 18 + TypeScript + Vite
- **UI Framework**: Ant Design (enterprise-grade components)
- **Charts**: Recharts for real-time visualizations
- **State Management**: Zustand (lightweight, modern)
- **Routing**: React Router v6
- **Build Tool**: Vite (fast development and builds)

### **Backend Integration**
- **Management API**: REST endpoints for UI operations
- **WebSocket API**: Real-time data streaming (planned)
- **Static Serving**: Served from PeeGeeQ REST server

## Getting Started

### **Prerequisites**
- Node.js 18+ and npm/yarn
- PeeGeeQ REST server running on port 8080

### **Development Setup**

1. **Install Dependencies**
   ```bash
   cd peegeeq-management-ui
   npm install
   ```

2. **Start Development Server**
   ```bash
   npm run dev
   ```
   
   The UI will be available at `http://localhost:3000`

3. **Build for Production**
   ```bash
   npm run build
   ```
   
   Built files will be placed in `../peegeeq-rest/src/main/resources/webroot`

### **Production Deployment**

The management UI is automatically served by the PeeGeeQ REST server:

1. Build the UI: `npm run build`
2. Start PeeGeeQ REST server
3. Access the UI at: `http://localhost:8080/ui/`

## User Interface

### **Navigation Structure**
```
PeeGeeQ Management Console
├── Overview (Dashboard)
├── Queues (Queue Management)
├── Consumer Groups (Group Coordination)
├── Event Stores (Event Management)
├── Message Browser (Message Inspection)
├── Schema Registry (Schema Management)
├── Developer Portal (API Documentation)
├── Queue Designer (Visual Design)
├── Monitoring (Real-time Dashboards)
└── Settings (System Configuration)
```

### **Key Design Principles**
- **Clean, Intuitive Navigation** - Clear sidebar with logical grouping
- **Information Density** - Rich data presentation without clutter
- **Real-time Updates** - Live metrics and status updates
- **Contextual Actions** - Relevant actions available where needed
- **Visual Hierarchy** - Clear information architecture
- **Responsive Design** - Works on all screen sizes

## Design Inspiration

The PeeGeeQ Management Console is inspired by RabbitMQ's excellent admin interface, featuring:

- **Dark sidebar navigation** with clear iconography
- **Clean, card-based layout** for information display
- **Consistent color scheme** with status indicators
- **Rich data tables** with sorting and filtering
- **Real-time metrics** with charts and graphs
- **Contextual actions** and dropdown menus

## API Integration

### **Management API Endpoints**
```http
GET /api/v1/health                    # Health check
GET /api/v1/management/overview       # System overview
GET /api/v1/management/queues         # Queue list
GET /api/v1/management/metrics        # System metrics
```

### **Real-time Features**
- **Connection Status** - Automatic health checks every 30 seconds
- **Live Metrics** - Real-time system statistics
- **WebSocket Integration** - Planned for Phase 5.2

## Screenshots

### **Overview Dashboard**
- System health status with uptime
- Key metrics (queues, consumer groups, messages/sec)
- Real-time throughput charts
- Queue overview table
- Recent activity feed

### **Queue Management**
- Queue list with status indicators
- Create/edit/delete operations
- Message and consumer statistics
- Real-time rate monitoring

## Development

### **Available Scripts**
```bash
npm run dev          # Start development server
npm run build        # Build for production
npm run preview      # Preview production build
npm run lint         # Run ESLint
npm run type-check   # Run TypeScript checks
```

### **Project Structure**
```
src/
├── components/
│   ├── layout/          # Layout components (Sidebar, Header)
│   └── common/          # Reusable components
├── pages/               # Page components
├── hooks/               # Custom React hooks
├── services/            # API services
├── types/               # TypeScript type definitions
├── utils/               # Utility functions
└── stores/              # State management
```

## Configuration

### **Proxy Configuration**
The development server proxies API calls to the PeeGeeQ REST server:

```typescript
// vite.config.ts
server: {
  proxy: {
    '/api': 'http://localhost:8080',
    '/ws': 'ws://localhost:8080'
  }
}
```

### **Build Configuration**
Production builds are automatically placed in the REST server's static directory:

```typescript
// vite.config.ts
build: {
  outDir: '../peegeeq-rest/src/main/resources/webroot'
}
```

## Documentation

### 📋 **Production Readiness**
- **[PRODUCTION_READINESS.md](PRODUCTION_READINESS.md)** - **START HERE** - Complete assessment with:
  - Executive summary and status at a glance
  - The 5 critical blockers
  - Complete gap analysis (12 categories)
  - 3 timeline options (Demo, MVP 4-week, Production 6-week)
  - Week-by-week implementation plan
  - Production readiness checklist
  - Risk assessment and recommendations

### 🚀 **Getting Started**
- **[Quick Start Guide](QUICK_START.md)** - Get up and running in 5 minutes
- **[Testing Guide](TESTING.md)** - How to run and write tests (E2E, integration, unit)

### 📚 **Technical Documentation**
- **[REST API Interfaces](REST_API_INTERFACES.md)** - Complete API documentation (25+ endpoints)
- **[Complete Design Document](PEEGEEQ_MANAGEMENT_UI_COMPLETE_DESIGN.md)** - Full technical specification
- **[Phase 1 Implementation Status](PHASE1_IMPLEMENTATION_STATUS.md)** - Current implementation details

---

## Roadmap

### **Phase 5.1** ✅ **Complete** (UI) / 🔴 **Incomplete** (Backend)
- ✅ Basic UI framework and navigation
- ✅ Overview dashboard with real data
- ✅ Queue management interface (UI complete)
- 🔴 Queue Details endpoints (return placeholder data)
- 🔴 Consumer tracking (not implemented)
- 🔴 Message browsing (not implemented)

### **Phase 5.2** 🔴 **Blocked** (Requires Phase 5.1 completion)
- Message Browser with real-time updates
- Consumer Group management interface
- Event Store explorer
- WebSocket integration for live updates

### **Phase 5.3** 📋 **Planned**
- Schema Registry interface
- Developer Portal with API testing
- Visual Queue Designer
- Advanced monitoring dashboards

### **Phase 5.4** 📋 **Future**
- User authentication and authorization
- Multi-tenant support
- Advanced analytics and reporting
- Mobile app companion

## License

MIT License - see the main PeeGeeQ project for details.

---

**PeeGeeQ Management Console** - Making message queue management intuitive and powerful!
