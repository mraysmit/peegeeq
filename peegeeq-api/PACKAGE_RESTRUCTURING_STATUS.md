# PeeGeeQ API Package Restructuring Status

## Overview
This document tracks the progress of restructuring the `dev.mars.peegeeq.api` package into a more organized, domain-driven structure.

## New Package Structure

### ✅ Completed Packages

#### 1. `dev.mars.peegeeq.api.messaging` - Core Messaging Contracts
- ✅ Package documentation created
- ✅ `Message<T>` - Moved and backward compatibility added
- ✅ `MessageProducer<T>` - Moved and backward compatibility added  
- ✅ `MessageConsumer<T>` - Moved and backward compatibility added
- ✅ `MessageHandler<T>` - Moved and backward compatibility added
- ✅ `MessageFilter` - Moved (utility class)

#### 2. `dev.mars.peegeeq.api.queue` - Queue Management
- ✅ Package documentation created
- ✅ `QueueFactory` - Moved with updated imports
- ✅ `ConsumerGroup<T>` - Moved with updated imports
- 🔄 `ConsumerGroupMember<T>` - Needs to be moved
- 🔄 `ConsumerGroupStats` - Needs to be moved
- 🔄 `ConsumerMemberStats` - Needs to be moved
- 🔄 `QueueConfiguration` - Needs to be moved
- 🔄 `PgQueue` - Needs to be moved

#### 3. `dev.mars.peegeeq.api.events` - Event Sourcing Domain
- ✅ Package documentation created
- 🔄 `EventStore<T>` - Needs to be moved
- 🔄 `BiTemporalEvent<T>` - Needs to be moved
- 🔄 `EventQuery` - Needs to be moved
- 🔄 `TemporalRange` - Needs to be moved

#### 4. `dev.mars.peegeeq.api.database` - Database Abstractions
- ✅ Package documentation created
- 🔄 `DatabaseService` - Needs to be moved
- 🔄 `ConnectionProvider` - Needs to be moved
- 🔄 `MetricsProvider` - Needs to be moved

#### 5. `dev.mars.peegeeq.api.factory` - Factory & Provider Pattern
- ✅ Package documentation created
- 🔄 `QueueFactoryProvider` - Needs to be moved

#### 6. `dev.mars.peegeeq.api.config` - Configuration Domain
- ✅ Package documentation created
- 🔄 Move config classes from `setup` package:
  - `DatabaseConfig`
  - `QueueConfig` 
  - `EventStoreConfig`
  - `ConnectionPoolConfig`

#### 7. `dev.mars.peegeeq.api.setup` - Setup & Management
- ✅ Already exists with proper structure
- ✅ `DatabaseSetupService`
- ✅ `DatabaseSetupRequest`
- ✅ `DatabaseSetupResult`
- ✅ `DatabaseSetupStatus`

#### 8. `dev.mars.peegeeq.api.common` - Common Utilities
- ✅ Package documentation created
- 🔄 `SchemaVersion` - Needs to be moved

#### 9. `dev.mars.peegeeq.api.impl` - Reference Implementations
- ✅ Package documentation created
- 🔄 `SimpleMessage<T>` - Needs to be moved
- 🔄 `SimpleBiTemporalEvent<T>` - Needs to be moved

## Migration Strategy

### Phase 1: ✅ COMPLETED
- Created new package structure with documentation
- Moved core messaging interfaces with backward compatibility
- Updated imports in moved classes

### Phase 2: 🔄 IN PROGRESS
- Move remaining classes to appropriate packages
- Add backward compatibility interfaces in original locations
- Update all internal imports

### Phase 3: 📋 TODO
- Update all references in other modules (peegeeq-db, peegeeq-rest, etc.)
- Update documentation and examples
- Run full test suite to ensure compatibility

### Phase 4: 📋 TODO
- Add deprecation warnings to old locations
- Update build scripts and CI/CD
- Create migration guide for users

## Backward Compatibility

All moved interfaces maintain backward compatibility through deprecated wrapper interfaces in the original package locations. For example:

```java
@Deprecated(since = "1.0", forRemoval = true)
public interface Message<T> extends dev.mars.peegeeq.api.messaging.Message<T> {
}
```

## Benefits Achieved

1. **Clear Domain Boundaries** - Each package represents a specific concern
2. **Better Organization** - Related classes are grouped together
3. **Improved Maintainability** - Easier to find and modify functionality
4. **Enhanced Documentation** - Package-level documentation explains domains
5. **Backward Compatibility** - Existing code continues to work

## Next Steps

1. Complete moving remaining classes to new packages
2. Update imports in implementation modules
3. Run comprehensive tests
4. Update documentation and examples
5. Plan deprecation timeline for old package structure

## Files Modified

### New Files Created
- Package documentation files (`package-info.java`) for all new packages
- New interface files in structured packages
- This status document

### Files Modified
- Original interface files converted to deprecated wrappers
- Import statements updated in moved files

### Files To Be Modified
- Implementation classes in other modules
- Test files
- Documentation files
- Build configuration files
