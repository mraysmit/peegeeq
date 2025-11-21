#!/bin/bash

# PeeGeeQ Hardware Profiling Dashboard Demo Script
# This script sets up and demonstrates the hardware profiling dashboard

set -e

echo "🚀 PeeGeeQ Hardware Profiling Dashboard Demo"
echo "============================================="

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo "❌ Docker is not running. Please start Docker and try again."
    exit 1
fi

# Check if Docker Compose is available
if ! command -v docker-compose > /dev/null 2>&1; then
    echo "❌ Docker Compose is not installed. Please install Docker Compose and try again."
    exit 1
fi

echo "✅ Docker is running"

# Build the test support JAR first
echo "📦 Building PeeGeeQ test support JAR..."
cd ..
mvn clean package -pl peegeeq-test-support -DskipTests
cd examples

echo "✅ Test support JAR built successfully"

# Start the monitoring stack
echo "🐳 Starting monitoring stack..."
docker-compose -f docker-compose-monitoring-demo.yml up -d

echo "⏳ Waiting for services to start..."
sleep 30

# Check service health
echo "🔍 Checking service health..."

# Check Prometheus
if curl -s http://localhost:9090/-/healthy > /dev/null; then
    echo "✅ Prometheus is healthy (http://localhost:9090)"
else
    echo "❌ Prometheus is not responding"
fi

# Check Grafana
if curl -s http://localhost:3000/api/health > /dev/null; then
    echo "✅ Grafana is healthy (http://localhost:3000)"
    echo "   📊 Login: admin / admin123"
else
    echo "❌ Grafana is not responding"
fi

# Check PostgreSQL
if docker exec peegeeq-postgres-demo pg_isready -U peegeeq > /dev/null; then
    echo "✅ PostgreSQL is healthy"
else
    echo "❌ PostgreSQL is not responding"
fi

echo ""
echo "🎯 Demo Instructions:"
echo "===================="
echo ""
echo "1. 📊 Open Grafana Dashboard:"
echo "   URL: http://localhost:3000"
echo "   Login: admin / admin123"
echo "   Navigate to: Dashboards → PeeGeeQ → PeeGeeQ Hardware-Aware Performance Dashboard"
echo ""
echo "2. 📈 Open Prometheus (optional):"
echo "   URL: http://localhost:9090"
echo "   Check targets: Status → Targets"
echo ""
echo "3. 🧪 Run Hardware Profiling Tests:"
echo "   The test application will automatically generate hardware profiling metrics"
echo "   Watch the dashboard for real-time updates"
echo ""
echo "4. 🔍 Explore Dashboard Features:"
echo "   - Hardware Profile Overview (CPU, Memory, Frequency)"
echo "   - Real-time Resource Monitoring (CPU, Memory usage during tests)"
echo "   - Performance Efficiency Metrics (Throughput per CPU core)"
echo "   - Resource Constraint Indicators"
echo "   - Template Variables (Environment, Instance, Hardware Profile)"
echo ""
echo "5. 🚨 Test Alerting (optional):"
echo "   - High CPU/Memory usage alerts"
echo "   - Resource constraint detection"
echo "   - Performance regression alerts"
echo ""
echo "📝 To stop the demo:"
echo "   docker-compose -f docker-compose-monitoring-demo.yml down"
echo ""
echo "🎉 Demo is ready! Open http://localhost:3000 to view the dashboard"
