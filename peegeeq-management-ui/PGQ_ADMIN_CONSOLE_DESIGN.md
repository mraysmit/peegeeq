# PeeGeeQ Management UI - Design Document

## Executive Summary

The PeeGeeQ Management UI is a modern, web-based administration interface designed to provide comprehensive management and monitoring capabilities for the PeeGeeQ message queue system. Inspired by RabbitMQ's management console, it serves as the primary administrative dashboard for system operators, developers, and administrators.

## Goals and Objectives

### Primary Goals

1. **Unified Administration Interface**
   - Provide a single, centralized interface for all PeeGeeQ management operations
   - Enable real-time monitoring and administration of message queues, consumer groups, and event stores
   - Replace command-line and programmatic administration with an intuitive web interface

2. **Operational Excellence**
   - Enable proactive system monitoring through real-time dashboards and metrics
   - Provide comprehensive visibility into message flow, queue health, and consumer performance
   - Support troubleshooting and debugging through detailed message inspection capabilities

3. **Developer Productivity**
   - Accelerate development workflows with visual queue management and message browsing
   - Provide interactive API documentation and testing capabilities
   - Enable rapid prototyping through visual queue design tools

4. **Enterprise Readiness**
   - Support multi-environment management (production, staging, development)
   - Provide audit trails and activity logging for compliance requirements
   - Enable scalable administration across multiple PeeGeeQ setups

### Secondary Goals

1. **User Experience Excellence**
   - Deliver a responsive, accessible interface that works across all devices
   - Provide intuitive navigation inspired by industry-leading tools like RabbitMQ
   - Minimize learning curve for administrators familiar with message queue systems

2. **Real-time Operations**
   - Enable live monitoring of system performance and message throughput
   - Provide immediate feedback on configuration changes and system events
   - Support real-time collaboration among team members

## Architecture Overview

### Technology Stack

**Frontend Framework:**
- **React 18** with TypeScript for type-safe, modern component development
- **Ant Design** for enterprise-grade UI components and consistent design language
- **Vite** for fast development builds and optimized production bundles
- **React Router v6** for client-side routing and navigation

**State Management:**
- **Zustand** (planned) for lightweight, modern state management
- **React Hooks** for component-level state and side effects
- **Axios** for HTTP client with interceptors and error handling

**Real-time Communication:**
- **WebSocket API** for bidirectional real-time communication
- **Server-Sent Events (SSE)** for efficient server-to-client streaming
- **Custom hooks** for managing real-time connections and data updates

**Development Tools:**
- **TypeScript** for static type checking and enhanced developer experience
- **ESLint** for code quality and consistency
- **Playwright** for end-to-end testing
- **Vitest** for unit and integration testing

### Backend Integration

**REST API Integration:**
- **Management API** (`/api/v1/management/*`) for administrative operations
- **Queue API** (`/api/v1/queues/*`) for queue management and message operations
- **Event Store API** (`/api/v1/eventstores/*`) for event storage and querying
- **Consumer Group API** (`/api/v1/consumer-groups/*`) for group coordination
- **Health API** (`/api/v1/health`) for system health monitoring

**Real-time Data Streams:**
- **WebSocket endpoints** (`/ws/*`) for live message streaming and system updates
- **SSE endpoints** (`/sse/*`) for metrics streaming and queue updates
- **Automatic reconnection** with exponential backoff for resilient connections

**Static Content Serving:**
- Production builds served directly from PeeGeeQ REST server at `/ui/`
- Integrated deployment pipeline with automatic build placement
- Development proxy configuration for seamless local development

## Core Functional Areas

### 1. System Overview Dashboard

**Purpose:** Provide at-a-glance system health and performance monitoring

**Key Features:**
- **System Health Status** with uptime tracking and connection monitoring
- **Key Performance Metrics** including total queues, consumer groups, event stores, and message throughput
- **Real-time Charts** for message throughput trends and connection statistics
- **Queue Overview Table** showing top queues by activity and status
- **Recent Activity Feed** with system events and administrative actions
- **Auto-refresh** capabilities with 30-second intervals

**Data Sources:**
- `/api/v1/management/overview` for comprehensive system statistics
- `/api/v1/health` for health status and uptime information
- Real-time updates via WebSocket connections

### 2. Queue Management Interface

**Purpose:** Complete lifecycle management of message queues

**Key Features:**
- **Queue Inventory** with comprehensive queue listing and statistics
- **CRUD Operations** for queue creation, modification, and deletion
- **Queue Statistics** including message counts, consumer counts, and throughput rates
- **Status Monitoring** with visual indicators for queue health
- **Filtering and Search** capabilities for large-scale queue management
- **Queue Configuration** with durability, auto-delete, and setup assignment options

**Data Sources:**
- `/api/v1/management/queues` for queue listing and statistics
- Queue-specific APIs for detailed queue operations
- Real-time updates for live queue statistics

### 3. Consumer Group Coordination

**Purpose:** Manage consumer group membership and load balancing

**Key Features:**
- **Group Management** with creation, configuration, and deletion capabilities
- **Member Monitoring** showing active consumers and their assignments
- **Partition Assignment Visualization** with interactive partition mapping
- **Load Balancing Strategy Configuration** (Round Robin, Range, Sticky, Random)
- **Rebalancing Operations** with manual trigger capabilities
- **Performance Metrics** including throughput and error tracking

**Data Sources:**
- `/api/v1/management/consumer-groups` for group information
- Consumer group APIs for member management and coordination
- Real-time updates for group membership changes

### 4. Event Store Explorer

**Purpose:** Bi-temporal event storage management and querying

**Key Features:**
- **Event Store Management** with creation and configuration capabilities
- **Bi-temporal Event Browsing** with valid-time and transaction-time filtering
- **Advanced Event Querying** with correlation ID and causation ID tracking
- **Aggregate Type Management** with event type categorization
- **Event Inspection** with detailed metadata and payload viewing
- **Storage Statistics** including event counts and storage utilization

**Data Sources:**
- `/api/v1/management/event-stores` for event store listing
- Event store APIs for event querying and storage operations
- Real-time updates for new events and storage metrics

### 5. Message Browser and Inspection

**Purpose:** Detailed message inspection and debugging capabilities

**Key Features:**
- **Message Search and Filtering** with advanced query capabilities
- **Real-time Message Streaming** with live message arrival notifications
- **Message Detail Inspection** including headers, payload, and metadata
- **Consumer Information** showing processing status and consumer assignments
- **Message Status Tracking** (pending, processing, completed, failed)
- **Export Capabilities** for message data and debugging information

**Data Sources:**
- `/api/v1/management/messages` for message querying
- WebSocket streams for real-time message updates
- Queue APIs for message retrieval and status information

## User Interface Design

### Design Principles

1. **Information Density with Clarity**
   - Rich data presentation without visual clutter
   - Hierarchical information display with progressive disclosure
   - Consistent use of cards, tables, and statistical displays

2. **Real-time Responsiveness**
   - Live updates with visual indicators for real-time data
   - Automatic refresh capabilities with manual override options
   - Connection status monitoring with graceful degradation

3. **Contextual Actions**
   - Relevant actions available where needed without overwhelming the interface
   - Dropdown menus for secondary actions
   - Modal dialogs for complex operations

4. **Responsive Design**
   - Mobile-first approach with tablet and desktop optimizations
   - Collapsible sidebar navigation for space efficiency
   - Adaptive layouts for different screen sizes

### Visual Design Language

**Color Scheme:**
- **Primary Blue** (#1890ff) for primary actions and queue indicators
- **Success Green** (#52c41a) for healthy states and positive metrics
- **Warning Orange** (#faad14) for attention states and warnings
- **Error Red** (#ff4d4f) for error states and critical alerts
- **Purple** (#722ed1) for event stores and specialized features

**Typography:**
- **System fonts** for optimal readability across platforms
- **Consistent hierarchy** with proper heading structure
- **Code fonts** for technical identifiers and JSON data

**Layout:**
- **Dark sidebar** with light content area for visual separation
- **Card-based layout** for logical grouping of related information
- **Grid system** for responsive layout management

## Technical Implementation

### Component Architecture

**Page Components:**
- `Overview.tsx` - System dashboard with metrics and activity
- `Queues.tsx` - Queue management interface with CRUD operations
- `ConsumerGroups.tsx` - Consumer group coordination and monitoring
- `EventStores.tsx` - Event store management and event browsing
- `MessageBrowser.tsx` - Message inspection and real-time streaming

**Shared Components:**
- Layout components for consistent navigation and header structure
- Common components for reusable UI elements
- Connection status components for real-time monitoring

**Service Layer:**
- `websocketService.ts` - WebSocket connection management with reconnection logic
- Custom React hooks for real-time data integration
- Axios-based HTTP client for REST API communication

### Data Flow Architecture

**State Management:**
- Component-level state for UI interactions and form data
- Custom hooks for real-time data subscriptions
- Centralized error handling and loading state management

**API Integration:**
- RESTful endpoints for CRUD operations and data retrieval
- WebSocket connections for real-time updates and streaming
- Automatic retry logic and connection recovery

**Real-time Updates:**
- 30-second polling intervals for non-critical data
- WebSocket streams for time-sensitive updates
- SSE connections for metrics and system monitoring

## Development and Deployment

### Development Workflow

**Local Development:**
- Vite development server with hot module replacement
- Proxy configuration for seamless backend integration
- TypeScript compilation with strict type checking

**Build Process:**
- Production builds automatically placed in REST server static directory
- Source map generation for debugging production issues
- Asset optimization and code splitting

**Testing Strategy:**
- Unit tests with Vitest for component logic
- Integration tests for API connectivity and data flow
- End-to-end tests with Playwright for complete user workflows
- Visual regression testing for UI consistency

### Deployment Architecture

**Production Serving:**
- Static files served directly from PeeGeeQ REST server
- Single-port deployment for simplified infrastructure
- Integrated authentication and authorization (planned)

**Development Environment:**
- Separate development server with API proxying
- Live reload and hot module replacement
- Development-specific error handling and debugging tools

## Future Roadmap

### Phase 5.2 - Enhanced Real-time Features
- Complete WebSocket integration for all real-time features
- Advanced message streaming with filtering and search
- Live dashboard updates with configurable refresh intervals

### Phase 5.3 - Advanced Management Features
- Schema Registry interface for message schema management
- Developer Portal with interactive API documentation
- Visual Queue Designer with drag-and-drop configuration

### Phase 5.4 - Enterprise Features
- User authentication and role-based access control
- Multi-tenant support with setup isolation
- Advanced analytics and reporting capabilities
- Mobile companion application

## Success Metrics

### User Experience Metrics
- Page load times under 2 seconds
- API response times under 500ms
- 95%+ cross-browser compatibility
- Accessibility compliance (WCAG 2.1 AA)

### Operational Metrics
- Real-time data latency under 100ms
- 99.9% uptime for management interface
- Support for 1000+ concurrent administrative sessions
- Zero data loss during real-time updates

### Developer Productivity Metrics
- Reduced time-to-diagnosis for queue issues
- Increased visibility into system performance
- Simplified queue configuration and management workflows
- Enhanced debugging capabilities for message flow issues

---

**PeeGeeQ Management UI** - Transforming message queue administration through intuitive, powerful, and real-time management capabilities.
