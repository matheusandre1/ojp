# StatementServiceImpl Refactoring - Implementation Checklist

## Overview
This document provides a detailed checklist for implementing the refactoring of `StatementServiceImpl` into the Action pattern.

## Pre-Implementation Checklist

- [ ] Review main refactoring design document
- [ ] Review detailed design document
- [ ] Get team approval on the approach
- [ ] Set up feature branch for refactoring
- [ ] Verify all existing tests pass on current code
- [ ] Document baseline performance metrics
- [ ] Set up CI/CD pipeline for incremental testing

## Phase 1: Infrastructure Setup (Week 1)

### 1.1 Create Base Infrastructure
- [ ] Create package structure:
  - [ ] `org.openjproxy.grpc.server.action`
  - [ ] `org.openjproxy.grpc.server.action.connection`
  - [ ] `org.openjproxy.grpc.server.action.statement`
  - [ ] `org.openjproxy.grpc.server.action.lob`
  - [ ] `org.openjproxy.grpc.server.action.session`
  - [ ] `org.openjproxy.grpc.server.action.transaction`
  - [ ] `org.openjproxy.grpc.server.action.resource`
  - [ ] `org.openjproxy.grpc.server.action.xa`
  - [ ] `org.openjproxy.grpc.server.action.util`

### 1.2 Create Core Classes
- [ ] Create `Action<TRequest, TResponse>` interface
- [ ] Create `StreamingAction<TRequest, TResponse>` interface
- [ ] Create `InitAction` interface
- [ ] Create `ValueAction<TRequest, TResult>` interface
- [ ] Create `ActionContext` class with all fields
- [ ] Add Javadoc to all interfaces and ActionContext

### 1.3 Modify StatementServiceImpl
- [ ] Add `actionContext` field
- [ ] Add `createActionContext()` method
- [ ] Modify constructor to initialize `actionContext`
- [ ] Keep all existing methods unchanged (for now)

### 1.4 Testing Infrastructure
- [ ] Create `ActionContextTest` unit test
- [ ] Create base test utilities for mocking ActionContext
- [ ] Verify build still compiles
- [ ] Verify all existing tests still pass

### 1.5 Documentation
- [ ] Update project README with refactoring notes
- [ ] Add ADR (Architecture Decision Record) for Action pattern choice
- [ ] Document migration strategy in CONTRIBUTING.md

**Milestone**: Infrastructure complete, all tests passing, no functional changes

---

## Phase 2: Simple Actions (Week 2-3)

### 2.1 ProcessClusterHealthAction
- [ ] Create `ProcessClusterHealthAction` class
- [ ] Extract logic from `processClusterHealth()` method (lines 217-285)
- [ ] Create unit tests for `ProcessClusterHealthAction`
- [ ] Update callers to use new action (don't remove old method yet)
- [ ] Verify integration tests pass
- [ ] Code review and merge

### 2.2 TerminateSessionAction
- [ ] Create `TerminateSessionAction` class
- [ ] Extract logic from `terminateSession()` method (lines 1484-1518)
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.terminateSession()` to delegate to action
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

### 2.3 StartTransactionAction
- [ ] Create `StartTransactionAction` class
- [ ] Extract logic from `startTransaction()` method (lines 1521-1557)
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.startTransaction()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

### 2.4 CommitTransactionAction
- [ ] Create `CommitTransactionAction` class
- [ ] Extract logic from `commitTransaction()` method (lines 1560-1586)
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.commitTransaction()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

### 2.5 RollbackTransactionAction
- [ ] Create `RollbackTransactionAction` class
- [ ] Extract logic from `rollbackTransaction()` method (lines 1589-1615)
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.rollbackTransaction()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

**Milestone**: 5 simple actions complete, 300+ lines removed from StatementServiceImpl

---

## Phase 3: Medium Complexity Actions (Week 4-5)

### 3.1 SessionConnectionAction
- [ ] Create `SessionConnectionAction` class (shared utility)
- [ ] Extract logic from `sessionConnection()` method (lines 1820-1916)
- [ ] Create comprehensive unit tests (pooled/unpooled, XA/regular)
- [ ] Update all internal callers to use action
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

### 3.2 HandleResultSetAction
- [ ] Create `HandleResultSetAction` class
- [ ] Extract logic from `handleResultSet()` method (lines 1918-2040)
- [ ] Create helper classes if needed (e.g., `CollectResultSetMetadataAction`)
- [ ] Create unit tests
- [ ] Update callers
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

### 3.3 ExecuteUpdateInternalAction
- [ ] Create `ExecuteUpdateInternalAction` class
- [ ] Extract logic from `executeUpdateInternal()` method (lines 928-1011)
- [ ] Create unit tests
- [ ] Update `ExecuteUpdateAction` to use this (see below)
- [ ] Verify integration tests pass
- [ ] Code review and merge

### 3.4 ExecuteUpdateAction
- [ ] Create `ExecuteUpdateAction` class
- [ ] Extract logic from `executeUpdate()` method (lines 881-923)
- [ ] Use `ExecuteUpdateInternalAction` for actual execution
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.executeUpdate()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

### 3.5 ExecuteQueryInternalAction
- [ ] Create `ExecuteQueryInternalAction` class
- [ ] Extract logic from `executeQueryInternal()` method (lines 1055-1069)
- [ ] Create unit tests
- [ ] Code review and merge

### 3.6 ExecuteQueryAction
- [ ] Create `ExecuteQueryAction` class
- [ ] Extract logic from `executeQuery()` method (lines 1014-1050)
- [ ] Use `ExecuteQueryInternalAction` for actual execution
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.executeQuery()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

### 3.7 FetchNextRowsAction
- [ ] Create `FetchNextRowsAction` class
- [ ] Extract logic from `fetchNextRows()` method (lines 1072-1085)
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.fetchNextRows()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

**Milestone**: Core query and session actions complete, 600+ lines removed

---

## Phase 4: Complex Actions (Week 6-7)

### 4.1 XA Helper Actions

#### XaStartPassThroughAction
- [ ] Create `HandleXAStartPassThroughAction` class
- [ ] Extract logic from `handleXAStartPassThrough()` method (lines 2141-2157)
- [ ] Create unit tests
- [ ] Code review and merge

#### XaStartWithPoolingAction
- [ ] Create `HandleXAStartWithPoolingAction` class
- [ ] Extract logic from `handleXAStartWithPooling()` method (lines 2099-2139)
- [ ] Create unit tests
- [ ] Code review and merge

### 4.2 XA Transaction Actions

#### XaStartAction
- [ ] Create `XaStartAction` class
- [ ] Extract logic from `xaStart()` method (lines 2050-2097)
- [ ] Use helper actions created above
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.xaStart()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

#### XaEndAction
- [ ] Create `XaEndAction` class
- [ ] Extract logic from `xaEnd()` method (lines 2160-2203)
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.xaEnd()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

#### XaPrepareAction
- [ ] Create `XaPrepareAction` class
- [ ] Extract logic from `xaPrepare()` method (lines 2206-2253)
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.xaPrepare()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

#### XaCommitAction
- [ ] Create `XaCommitAction` class
- [ ] Extract logic from `xaCommit()` method (lines 2256-2305)
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.xaCommit()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

#### XaRollbackAction
- [ ] Create `XaRollbackAction` class
- [ ] Extract logic from `xaRollback()` method (lines 2308-2354)
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.xaRollback()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

#### XaRecoverAction
- [ ] Create `XaRecoverAction` class
- [ ] Extract logic from `xaRecover()` method (lines 2357-2384)
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.xaRecover()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

#### XaForgetAction
- [ ] Create `XaForgetAction` class
- [ ] Extract logic from `xaForget()` method (lines 2387-2413)
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.xaForget()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

#### XaSetTransactionTimeoutAction
- [ ] Create `XaSetTransactionTimeoutAction` class
- [ ] Extract logic from `xaSetTransactionTimeout()` method (lines 2416-2445)
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.xaSetTransactionTimeout()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

#### XaGetTransactionTimeoutAction
- [ ] Create `XaGetTransactionTimeoutAction` class
- [ ] Extract logic from `xaGetTransactionTimeout()` method (lines 2448-2473)
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.xaGetTransactionTimeout()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

#### XaIsSameRMAction
- [ ] Create `XaIsSameRMAction` class
- [ ] Extract logic from `xaIsSameRM()` method (lines 2476-2505)
- [ ] Create unit tests
- [ ] Update `StatementServiceImpl.xaIsSameRM()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

### 4.3 CallResourceAction

#### Db2SpecialResultSetMetadataAction
- [ ] Create `Db2SpecialResultSetMetadataAction` helper
- [ ] Extract logic from `db2SpecialResultSetMetadata()` method (lines 1786-1808)
- [ ] Create unit tests
- [ ] Code review and merge

#### CallResourceAction
- [ ] Create `CallResourceAction` class
- [ ] Extract logic from `callResource()` method (lines 1618-1774)
- [ ] Use `Db2SpecialResultSetMetadataAction` helper
- [ ] Create unit tests (complex reflection logic)
- [ ] Update `StatementServiceImpl.callResource()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

### 4.4 Connection Actions

#### InitializeXAPoolProviderAction
- [ ] Create `InitializeXAPoolProviderAction` class
- [ ] Extract logic from `initializeXAPoolProvider()` method (lines 164-195)
- [ ] Create unit tests
- [ ] Update constructor to use action
- [ ] Code review and merge

#### HandleUnpooledXAConnectionAction
- [ ] Create `HandleUnpooledXAConnectionAction` class
- [ ] Extract logic from `handleUnpooledXAConnection()` method (lines 717-759)
- [ ] Create unit tests
- [ ] Code review and merge

#### HandleXAConnectionWithPoolingAction
- [ ] Create `HandleXAConnectionWithPoolingAction` class
- [ ] Extract logic from `handleXAConnectionWithPooling()` method (lines 441-710)
- [ ] Create unit tests
- [ ] Code review and merge

#### CreateSlowQuerySegregationManagerAction
- [ ] Create helper action for slow query manager creation
- [ ] Extract logic from `createSlowQuerySegregationManagerForDatasource()` methods
- [ ] Create unit tests
- [ ] Code review and merge

#### ConnectAction
- [ ] Create `ConnectAction` class
- [ ] Extract logic from `connect()` method (lines 288-431)
- [ ] Use all helper actions created above
- [ ] Create comprehensive unit tests
- [ ] Update `StatementServiceImpl.connect()` to delegate
- [ ] Verify integration tests pass (critical!)
- [ ] Remove old implementation
- [ ] Code review and merge

**Milestone**: All complex actions complete, 1500+ lines removed

---

## Phase 5: Streaming Actions (Week 8)

### 5.1 LOB Helper Actions

#### FindLobContextAction
- [ ] Create `FindLobContextAction` class
- [ ] Extract logic from `findLobContext()` method (lines 1391-1426)
- [ ] Create unit tests
- [ ] Code review and merge

#### InputStreamFromBlobAction
- [ ] Create `InputStreamFromBlobAction` class
- [ ] Extract logic from `inputStreamFromBlob()` method (lines 1446-1456)
- [ ] Create unit tests
- [ ] Code review and merge

#### InputStreamFromClobAction
- [ ] Create `InputStreamFromClobAction` class
- [ ] Extract logic from `inputStreamFromClob()` method (lines 1429-1443)
- [ ] Create unit tests
- [ ] Code review and merge

### 5.2 ReadLobAction
- [ ] Create `ReadLobAction` class
- [ ] Extract logic from `readLob()` method (lines 1289-1363)
- [ ] Use helper actions created above
- [ ] Create unit tests (streaming logic)
- [ ] Update `StatementServiceImpl.readLob()` to delegate
- [ ] Verify integration tests pass
- [ ] Remove old implementation
- [ ] Code review and merge

### 5.3 CreateLobAction
- [ ] Create `CreateLobAction` class
- [ ] Extract logic from `createLob()` method (lines 1088-1286)
- [ ] Create inner `CreateLobStreamObserver` class
- [ ] Create comprehensive unit tests (complex streaming state machine)
- [ ] Update `StatementServiceImpl.createLob()` to delegate
- [ ] Verify integration tests pass (critical - bidirectional streaming)
- [ ] Remove old implementation
- [ ] Code review and merge

**Milestone**: All streaming actions complete, 400+ lines removed

---

## Phase 6: Cleanup and Optimization (Week 9)

### 6.1 Final Cleanup
- [ ] Remove all old helper methods from `StatementServiceImpl`
- [ ] Verify `StatementServiceImpl` is now < 500 lines
- [ ] Update all Javadoc in `StatementServiceImpl`
- [ ] Verify no dead code remains

### 6.2 Testing
- [ ] Run full test suite
- [ ] Run integration tests multiple times
- [ ] Test XA transactions thoroughly
- [ ] Test LOB streaming with large files
- [ ] Load testing to verify no performance regression
- [ ] Run all database-specific tests (Postgres, Oracle, MySQL, etc.)

### 6.3 Documentation
- [ ] Update architecture documentation
- [ ] Add class diagrams showing new structure
- [ ] Update CONTRIBUTING.md with Action pattern guidelines
- [ ] Create migration guide for other potential refactorings
- [ ] Update README with new structure

### 6.4 Code Review
- [ ] Full code review of all action classes
- [ ] Review ActionContext usage patterns
- [ ] Review error handling consistency
- [ ] Review logging consistency
- [ ] Review thread safety

### 6.5 Performance Verification
- [ ] Compare performance metrics with baseline
- [ ] Verify no memory leaks
- [ ] Verify no connection leaks
- [ ] Check GC behavior
- [ ] Verify latency hasn't increased

**Milestone**: Refactoring complete, all tests passing, documentation updated

---

## Post-Implementation

### Metrics Collection
- [ ] Count lines removed from StatementServiceImpl
- [ ] Count number of action classes created
- [ ] Measure test coverage improvement
- [ ] Measure build time change
- [ ] Document performance comparison

### Knowledge Transfer
- [ ] Present refactoring to team
- [ ] Create video walkthrough of new structure
- [ ] Update onboarding documentation
- [ ] Create guidelines for adding new actions

### Future Improvements
- [ ] Consider adding action factory if needed
- [ ] Consider adding action interceptors for cross-cutting concerns
- [ ] Consider extracting more utilities from actions
- [ ] Plan similar refactorings for other God classes

---

## Summary Statistics (Estimated)

| Metric | Before | After | Change |
|--------|--------|-------|--------|
| StatementServiceImpl lines | 2,528 | ~400 | -2,128 (-84%) |
| Number of classes | 1 | ~35 | +34 |
| Average class size | 2,528 | ~75 | -2,453 (-97%) |
| Public methods in main class | 21 | 21 | 0 (same API) |
| Testable units | 1 | ~35 | +34 |

---

## Risk Mitigation Checklist

- [ ] All tests pass before starting
- [ ] Feature branch created
- [ ] Incremental commits after each action
- [ ] Code review after each phase
- [ ] Integration tests run after each action
- [ ] Performance monitoring throughout
- [ ] Rollback plan documented
- [ ] Team has approved design
- [ ] Stakeholders informed of timeline

---

## Success Criteria

✅ **Code Quality**
- StatementServiceImpl reduced to < 500 lines
- Each action class < 200 lines
- Test coverage maintained or improved
- No Sonar critical issues

✅ **Functionality**
- All existing tests pass
- No regression in integration tests
- XA transactions work correctly
- LOB streaming works correctly

✅ **Performance**
- No increase in latency (< 5% acceptable)
- No memory leaks
- No connection leaks
- Build time not significantly increased

✅ **Maintainability**
- Clear separation of concerns
- Easy to find code for specific operations
- New actions can be added easily
- Documentation is complete and accurate

✅ **Team Readiness**
- Team understands new structure
- Guidelines for adding actions exist
- Knowledge transfer complete
