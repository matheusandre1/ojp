# Housekeeping Implementation Plan for Copilot Execution

**Date**: 2026-01-08  
**Status**: Ready for Implementation  
**Based on Stakeholder Feedback from**: @rrobetti

---

## 📋 STAKEHOLDER DECISIONS

Based on feedback from @rrobetti:

1. ✅ **Approach**: Fill the gaps only (selective enhancement)
2. ✅ **Leak Detection**: Enabled by default
3. ✅ **Max Lifetime Strategy**: Passive during validation
   - ⚠️ **Important**: Connections must be IDLE for minimum configurable time before recycling (default: 5 minutes)
   - Connections in use should NOT be recycled
4. ✅ **Backward Compatibility**: Don't worry too much, we're in beta - make the best version possible
5. ✅ **Testing**: Unit and integration tests only (no need for comprehensive load testing)

---

## 🎯 IMPLEMENTATION PHASES

This plan divides the work into **7 manageable phases**, each designed to fit within a single Copilot session execution.

---

## PHASE 0: Foundation and Test Fixes

**Goal**: Fix existing test failures and prepare baseline

**Duration**: 1 session (~30-45 min)

**Files Modified**:
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/BackendSessionImpl.java`

**Tasks**:
1. Add null checks for `defaultTransactionIsolation` in:
   - `open()` method
   - `reset()` method  
   - `sanitizeAfterTransaction()` method

2. Run tests to verify fixes

**Validation**: `mvn test -pl ojp-xa-pool-commons`

---

## PHASE 1: Core Infrastructure

**Goal**: Add configuration and state tracking foundation

**Duration**: 1 session (~45-60 min)

**Files Created**:
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/housekeeping/HousekeepingConfig.java`
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/housekeeping/HousekeepingListener.java`

**Files Modified**:
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/BackendSessionImpl.java`
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/CommonsPool2XADataSource.java`

**Key Features**:
- Configuration class with all options
- Listener interface for events
- State tracking fields in BackendSessionImpl
- Configuration parsing from properties

---

## PHASE 2: Leak Detection

**Goal**: Implement leak detection feature

**Duration**: 1 session (~60-75 min)

**Files Created**:
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/housekeeping/LeakDetectionTask.java`
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/housekeeping/LoggingHousekeepingListener.java`
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/housekeeping/BorrowInfo.java`
- `ojp-xa-pool-commons/src/test/java/org/openjproxy/xa/pool/commons/housekeeping/LeakDetectionTest.java`

**Files Modified**:
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/CommonsPool2XADataSource.java`

**Key Features**:
- Track borrowed sessions
- Periodic leak checking task
- Logging with optional stack traces
- Enabled by default

---

## PHASE 3: Max Lifetime

**Goal**: Implement max lifetime with idle-before-recycle

**Duration**: 1 session (~45-60 min)

**Files Created**:
- `ojp-xa-pool-commons/src/test/java/org/openjproxy/xa/pool/commons/housekeeping/MaxLifetimeTest.java`

**Files Modified**:
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/BackendSessionImpl.java`
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/BackendSessionFactory.java`
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/CommonsPool2XADataSource.java`

**Key Features**:
- Passive enforcement via validation
- Requires idle time before recycle (5 min default)
- Active connections NOT recycled
- Configurable lifetime (30 min default)

---

## PHASE 4: Enhanced Diagnostics

**Goal**: Add pool state logging

**Duration**: 1 session (~30-45 min)

**Files Created**:
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/housekeeping/DiagnosticsTask.java`
- `ojp-xa-pool-commons/src/test/java/org/openjproxy/xa/pool/commons/housekeeping/DiagnosticsTest.java`

**Files Modified**:
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/CommonsPool2XADataSource.java`

**Key Features**:
- Periodic pool state logging
- Shows active/idle/waiters/borrowed
- Disabled by default
- 5 minute interval

---

## PHASE 5: Integration Testing

**Goal**: Test all features with real database

**Duration**: 1 session (~45-60 min)

**Files Created**:
- `ojp-xa-pool-commons/src/test/java/org/openjproxy/xa/pool/commons/housekeeping/HousekeepingIntegrationTest.java`

**Key Features**:
- Tests with H2 database
- All features working together
- End-to-end validation

---

## PHASE 6: Documentation & Provider Integration

**Goal**: Complete documentation and provider integration

**Duration**: 1 session (~30-45 min)

**Files Created**:
- `docs/housekeeping/CONFIGURATION.md`
- `docs/housekeeping/EXAMPLES.md`

**Files Modified**:
- `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/CommonsPool2XAProvider.java`
- README files

**Key Features**:
- Provider integration
- Configuration docs
- Examples
- Javadoc

---

## 📊 CONFIGURATION DEFAULTS

```properties
# Leak Detection (ENABLED by default)
xa.leakDetection.enabled=true
xa.leakDetection.timeoutMs=300000          # 5 min
xa.leakDetection.enhanced=false            # Stack traces off
xa.leakDetection.intervalMs=60000          # 1 min check

# Max Lifetime
xa.maxLifetimeMs=1800000                   # 30 min
xa.idleBeforeRecycleMs=300000              # 5 min IDLE required!

# Diagnostics (DISABLED by default)
xa.diagnostics.enabled=false
xa.diagnostics.intervalMs=300000           # 5 min
```

---

## ✅ SUCCESS CRITERIA

After all phases:
- ✅ Leak detection enabled by default and working
- ✅ Max lifetime respects idle-before-recycle requirement
- ✅ Active connections NOT recycled
- ✅ Enhanced diagnostics available
- ✅ All tests passing (unit + integration)
- ✅ Full documentation
- ✅ Provider properly integrated

---

**Total Estimated Time**: 5-7 hours across 7 Copilot sessions

**Ready for Phase 0 execution!** 🚀
