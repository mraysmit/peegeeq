# PeeGeeQ Testing Quick Reference

## 🚀 Most Common Commands (Copy & Paste)

```bash
# Daily development (24 seconds)
./scripts/run-tests.sh core

# Quick validation (20 seconds)  
./scripts/run-tests.sh smoke

# Single module development
./scripts/run-tests.sh core peegeeq-outbox

# Multiple modules
./scripts/run-tests.sh core peegeeq-db peegeeq-api

# Integration testing (10-15 minutes)
./scripts/run-tests.sh integration

# Help and usage
./scripts/run-tests.sh help
```

## 📋 All Test Categories

| Category | Duration | Purpose | Command |
|----------|----------|---------|---------|
| **core** | 24s | Daily development | `./scripts/run-tests.sh core` |
| **smoke** | 20s | Quick validation | `./scripts/run-tests.sh smoke` |
| **integration** | 10-15m | Real infrastructure | `./scripts/run-tests.sh integration` |
| **performance** | 20-30m | Benchmarks | `./scripts/run-tests.sh performance` |
| **slow** | 15+m | Comprehensive | `./scripts/run-tests.sh slow` |
| **all** | 45+m | Complete suite | `./scripts/run-tests.sh all` |

## 🎯 Module-Specific Commands

### Core Modules
```bash
# API module
./scripts/run-tests.sh core peegeeq-api
./scripts/run-tests.sh smoke peegeeq-api
./scripts/run-tests.sh integration peegeeq-api

# Database module  
./scripts/run-tests.sh core peegeeq-db
./scripts/run-tests.sh integration peegeeq-db

# Native queue module
./scripts/run-tests.sh core peegeeq-native
./scripts/run-tests.sh integration peegeeq-native

# Outbox module
./scripts/run-tests.sh core peegeeq-outbox
./scripts/run-tests.sh integration peegeeq-outbox
./scripts/run-tests.sh performance peegeeq-outbox
```

### Service Modules
```bash
# REST API module
./scripts/run-tests.sh core peegeeq-rest
./scripts/run-tests.sh integration peegeeq-rest

# Service manager module
./scripts/run-tests.sh core peegeeq-service-manager
./scripts/run-tests.sh integration peegeeq-service-manager

# Bi-temporal module
./scripts/run-tests.sh core peegeeq-bitemporal
./scripts/run-tests.sh integration peegeeq-bitemporal
```

### Support & Example Modules
```bash
# Test support module
./scripts/run-tests.sh core peegeeq-test-support
./scripts/run-tests.sh performance peegeeq-test-support

# Performance test harness
./scripts/run-tests.sh core peegeeq-performance-test-harness
./scripts/run-tests.sh performance peegeeq-performance-test-harness

# Examples
./scripts/run-tests.sh smoke peegeeq-examples
./scripts/run-tests.sh core peegeeq-examples-spring
```

## 🔄 Multiple Module Combinations

### Common Development Combinations
```bash
# Core modules together
./scripts/run-tests.sh core peegeeq-api peegeeq-db peegeeq-native

# Outbox and related modules
./scripts/run-tests.sh integration peegeeq-outbox peegeeq-bitemporal

# REST and service modules
./scripts/run-tests.sh core peegeeq-rest peegeeq-service-manager

# Example modules
./scripts/run-tests.sh smoke peegeeq-examples peegeeq-examples-spring

# Performance-focused modules
./scripts/run-tests.sh performance peegeeq-outbox peegeeq-test-support
```

### Workflow-Based Combinations
```bash
# Pre-commit validation
./scripts/run-tests.sh smoke
./scripts/run-tests.sh core

# Feature development (outbox-related)
./scripts/run-tests.sh core peegeeq-outbox peegeeq-bitemporal peegeeq-examples

# API development
./scripts/run-tests.sh core peegeeq-api peegeeq-rest

# Infrastructure development  
./scripts/run-tests.sh integration peegeeq-db peegeeq-native peegeeq-test-support
```

## ⚡ Performance Quick Reference

### Expected Execution Times

| Scope | Core | Smoke | Integration | Performance |
|-------|------|-------|-------------|-------------|
| **All modules** | 24s | 20s | 10-15m | 20-30m |
| **Single module** | 2-5s | 1-2s | 2-5m | 3-8m |
| **2-3 modules** | 6-10s | 3-5s | 5-10m | 10-15m |

### Module Performance Rankings (Core Tests)
```bash
# Fastest modules (1-2 seconds)
./scripts/run-tests.sh core peegeeq-db          # 1.7s
./scripts/run-tests.sh core peegeeq-native      # 1.5s

# Medium modules (2-3 seconds)  
./scripts/run-tests.sh core peegeeq-api         # 2.3s
./scripts/run-tests.sh core peegeeq-outbox      # 2.4s
./scripts/run-tests.sh core peegeeq-rest        # 3s

# Slower modules (5+ seconds)
./scripts/run-tests.sh core peegeeq-test-support # 5.3s
```

## 🛠️ Development Workflows

### Daily Development Workflow
```bash
# 1. Start development session
./scripts/run-tests.sh smoke

# 2. Work on specific module
./scripts/run-tests.sh core peegeeq-outbox

# 3. Quick validation after changes
./scripts/run-tests.sh core peegeeq-outbox

# 4. Pre-commit check
./scripts/run-tests.sh core

# 5. Final validation
./scripts/run-tests.sh smoke
```

### Feature Development Workflow
```bash
# 1. Initial validation
./scripts/run-tests.sh smoke

# 2. Module-specific development
./scripts/run-tests.sh core peegeeq-outbox peegeeq-bitemporal

# 3. Integration testing
./scripts/run-tests.sh integration peegeeq-outbox peegeeq-bitemporal

# 4. Performance validation (if needed)
./scripts/run-tests.sh performance peegeeq-outbox

# 5. Full validation before PR
./scripts/run-tests.sh core
./scripts/run-tests.sh integration
```

### Bug Fix Workflow
```bash
# 1. Reproduce issue with specific module
./scripts/run-tests.sh core peegeeq-outbox

# 2. Fix and validate
./scripts/run-tests.sh core peegeeq-outbox

# 3. Regression testing with related modules
./scripts/run-tests.sh core peegeeq-outbox peegeeq-bitemporal peegeeq-examples

# 4. Integration validation
./scripts/run-tests.sh integration peegeeq-outbox

# 5. Full smoke test
./scripts/run-tests.sh smoke
```

## 🚨 Troubleshooting Commands

### Common Issues
```bash
# Script not executable
chmod +x scripts/run-tests.sh

# Check available modules and categories
./scripts/run-tests.sh help

# Verbose Maven output for debugging
mvn test -Pcore-tests -pl :peegeeq-outbox -X

# Clean and rebuild before testing
mvn clean compile test-compile
./scripts/run-tests.sh core peegeeq-outbox
```

### Docker/TestContainers Issues
```bash
# Check Docker status
docker ps
docker system df

# Clean up Docker resources
docker system prune -f
docker volume prune -f

# Restart Docker and retry
./scripts/run-tests.sh integration peegeeq-outbox
```

## 📊 CI/CD Integration Examples

### GitHub Actions
```yaml
# Fast feedback
- run: ./scripts/run-tests.sh core

# Pre-merge validation  
- run: ./scripts/run-tests.sh smoke
- run: ./scripts/run-tests.sh core

# Nightly comprehensive
- run: ./scripts/run-tests.sh all
```

### Jenkins Pipeline
```groovy
stage('Core Tests') {
    steps { sh './scripts/run-tests.sh core' }
}
stage('Integration Tests') {
    steps { sh './scripts/run-tests.sh integration' }
}
```

## 📝 All Available Modules

```bash
peegeeq-api                    # Core API definitions
peegeeq-db                     # Database utilities  
peegeeq-native                 # Native PostgreSQL queue
peegeeq-outbox                 # Transactional outbox
peegeeq-bitemporal             # Bi-temporal event store
peegeeq-rest                   # REST API server
peegeeq-test-support           # Testing utilities
peegeeq-service-manager        # Service discovery
peegeeq-performance-test-harness # Performance framework
peegeeq-examples               # Usage examples
peegeeq-examples-spring        # Spring Boot examples
```

## 🎯 Pro Tips

### Speed Optimization
```bash
# Use smoke tests for quick validation
./scripts/run-tests.sh smoke

# Focus on modules you're changing
./scripts/run-tests.sh core peegeeq-outbox

# Combine related modules for efficiency
./scripts/run-tests.sh core peegeeq-outbox peegeeq-bitemporal
```

### Development Efficiency
```bash
# Create shell aliases for frequent commands
alias pqcore='./scripts/run-tests.sh core'
alias pqsmoke='./scripts/run-tests.sh smoke'
alias pqoutbox='./scripts/run-tests.sh core peegeeq-outbox'

# Use in development
pqcore
pqoutbox
pqsmoke
```

### IDE Integration
- **IntelliJ**: Add as External Tool
- **VS Code**: Create tasks.json entries
- **Terminal**: Create shell aliases

---

*For comprehensive documentation, see [TESTING-GUIDE.md](TESTING-GUIDE.md)*
