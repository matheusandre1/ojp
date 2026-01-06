# StatementServiceImpl Refactoring Design

## Executive Summary

The `StatementServiceImpl` class has grown to 2,528 lines and has become a God class that violates the Single Responsibility Principle. This document outlines a design to refactor the class using an Action pattern where each public method delegates to a dedicated Action class that operates on a shared `ActionContext`.

## Current State Analysis

### Class Metrics
- **Total Lines**: 2,528 lines
- **Public Methods**: 19 methods (gRPC service endpoints)
- **Private Methods**: 14+ helper methods
- **Instance Fields**: 9 maps and collections, 3 service dependencies

### Public Methods (gRPC Service Endpoints)

1. `connect(ConnectionDetails, StreamObserver<SessionInfo>)` - Connection establishment
2. `executeUpdate(StatementRequest, StreamObserver<OpResult>)` - SQL update operations
3. `executeQuery(StatementRequest, StreamObserver<OpResult>)` - SQL query operations
4. `fetchNextRows(ResultSetFetchRequest, StreamObserver<OpResult>)` - Result set pagination
5. `createLob(StreamObserver<LobReference>)` - LOB creation (streaming)
6. `readLob(ReadLobRequest, StreamObserver<LobDataBlock>)` - LOB reading (streaming)
7. `terminateSession(SessionInfo, StreamObserver<SessionTerminationStatus>)` - Session cleanup
8. `startTransaction(SessionInfo, StreamObserver<SessionInfo>)` - Transaction start
9. `commitTransaction(SessionInfo, StreamObserver<SessionInfo>)` - Transaction commit
10. `rollbackTransaction(SessionInfo, StreamObserver<SessionInfo>)` - Transaction rollback
11. `callResource(CallResourceRequest, StreamObserver<CallResourceResponse>)` - Generic resource calls
12. `xaStart(XaStartRequest, StreamObserver<XaResponse>)` - XA transaction start
13. `xaEnd(XaEndRequest, StreamObserver<XaResponse>)` - XA transaction end
14. `xaPrepare(XaPrepareRequest, StreamObserver<XaPrepareResponse>)` - XA prepare phase
15. `xaCommit(XaCommitRequest, StreamObserver<XaResponse>)` - XA commit
16. `xaRollback(XaRollbackRequest, StreamObserver<XaResponse>)` - XA rollback
17. `xaRecover(XaRecoverRequest, StreamObserver<XaRecoverResponse>)` - XA recovery
18. `xaForget(XaForgetRequest, StreamObserver<XaResponse>)` - XA forget
19. `xaSetTransactionTimeout(XaSetTransactionTimeoutRequest, StreamObserver<XaSetTransactionTimeoutResponse>)` - XA timeout setter
20. `xaGetTransactionTimeout(XaGetTransactionTimeoutRequest, StreamObserver<XaGetTransactionTimeoutResponse>)` - XA timeout getter
21. `xaIsSameRM(XaIsSameRMRequest, StreamObserver<XaIsSameRMResponse>)` - XA resource manager comparison

### Shared State (All accessed by multiple methods)

**Data Source Management:**
- `Map<String, DataSource> datasourceMap` - Regular connection pools
- `Map<String, XADataSource> xaDataSourceMap` - Unpooled XA datasources
- `Map<String, XATransactionRegistry> xaRegistries` - Pooled XA registries
- `Map<String, UnpooledConnectionDetails> unpooledConnectionDetailsMap` - Passthrough connection configs
- `Map<String, DbName> dbNameMap` - Database type mappings

**Query Management:**
- `Map<String, SlowQuerySegregationManager> slowQuerySegregationManagers` - Per-datasource query managers

**Coordination & Monitoring:**
- `XAConnectionPoolProvider xaPoolProvider` - XA pool SPI provider
- `MultinodeXaCoordinator xaCoordinator` - Static multinode coordinator
- `ClusterHealthTracker clusterHealthTracker` - Cluster health monitoring

**Service Dependencies (Constructor Injected):**
- `SessionManager sessionManager` - Session lifecycle management
- `CircuitBreaker circuitBreaker` - Query failure protection
- `ServerConfiguration serverConfiguration` - Server-wide configuration

## Proposed Refactoring Design

### 1. Core Pattern: Action Pattern

Each public method will delegate to a dedicated Action class following this pattern:

```java
public void methodName(Request request, StreamObserver<Response> responseObserver) {
    ActionContext context = createActionContext();
    MethodNameAction action = new MethodNameAction(context);
    action.execute(request, responseObserver);
}
```

### 2. ActionContext Design

The `ActionContext` class will hold all shared state and provide accessor methods:

```java
public class ActionContext {
    // Data Source Management
    private final Map<String, DataSource> datasourceMap;
    private final Map<String, XADataSource> xaDataSourceMap;
    private final Map<String, XATransactionRegistry> xaRegistries;
    private final Map<String, UnpooledConnectionDetails> unpooledConnectionDetailsMap;
    private final Map<String, DbName> dbNameMap;
    
    // Query Management
    private final Map<String, SlowQuerySegregationManager> slowQuerySegregationManagers;
    
    // XA Pool Provider
    private XAConnectionPoolProvider xaPoolProvider;
    
    // Coordinators & Trackers
    private final MultinodeXaCoordinator xaCoordinator;
    private final ClusterHealthTracker clusterHealthTracker;
    
    // Service Dependencies
    private final SessionManager sessionManager;
    private final CircuitBreaker circuitBreaker;
    private final ServerConfiguration serverConfiguration;
    
    // Constructor
    public ActionContext(
            Map<String, DataSource> datasourceMap,
            Map<String, XADataSource> xaDataSourceMap,
            Map<String, XATransactionRegistry> xaRegistries,
            Map<String, UnpooledConnectionDetails> unpooledConnectionDetailsMap,
            Map<String, DbName> dbNameMap,
            Map<String, SlowQuerySegregationManager> slowQuerySegregationManagers,
            XAConnectionPoolProvider xaPoolProvider,
            MultinodeXaCoordinator xaCoordinator,
            ClusterHealthTracker clusterHealthTracker,
            SessionManager sessionManager,
            CircuitBreaker circuitBreaker,
            ServerConfiguration serverConfiguration) {
        // Assignment...
    }
    
    // Getters for all fields
    public Map<String, DataSource> getDatasourceMap() { return datasourceMap; }
    public Map<String, XADataSource> getXaDataSourceMap() { return xaDataSourceMap; }
    // ... etc
}
```

### 3. Base Action Interface

```java
public interface Action<TRequest, TResponse> {
    void execute(TRequest request, StreamObserver<TResponse> responseObserver);
}
```

### 4. Individual Action Classes

#### 4.1 Connection Management Actions

**ConnectAction** (lines 288-431)
- Handles connection establishment for both regular and XA connections
- Manages pooled and unpooled modes
- Delegates to: `HandleXAConnectionWithPoolingAction`, `HandleUnpooledXAConnectionAction`
- Key responsibilities:
  - Connection hash generation
  - DataSource/XADataSource creation
  - Session registration
  - Multinode pool coordination
  - Slow query manager creation

**Helper Actions for ConnectAction:**
- `HandleXAConnectionWithPoolingAction` (lines 441-710)
- `HandleUnpooledXAConnectionAction` (lines 717-759)
- `InitializeXAPoolProviderAction` (lines 164-195)

#### 4.2 Statement Execution Actions

**ExecuteUpdateAction** (lines 881-923)
- Handles SQL UPDATE, INSERT, DELETE operations
- Circuit breaker integration
- Slow query segregation
- Delegates to `ExecuteUpdateInternalAction` for actual execution

**ExecuteUpdateInternalAction** (lines 928-1011)
- Core update logic
- Parameter handling
- Batch operations
- Auto-generated keys

**ExecuteQueryAction** (lines 1014-1050)
- Handles SQL SELECT operations
- Circuit breaker integration
- Slow query segregation
- Delegates to `ExecuteQueryInternalAction`

**ExecuteQueryInternalAction** (lines 1055-1069)
- Core query logic
- ResultSet registration
- Parameter handling

**FetchNextRowsAction** (lines 1072-1085)
- ResultSet pagination
- Cluster health processing

#### 4.3 LOB (Large Object) Actions

**CreateLobAction** (lines 1088-1286)
- Streaming LOB creation
- Returns StreamObserver for bidirectional streaming
- Handles BLOB, CLOB, and binary stream types
- Complex state machine for streaming

**ReadLobAction** (lines 1289-1363)
- Streaming LOB reading
- Block-based data transfer
- Handles different LOB types

**Helper Classes:**
- `FindLobContextAction` (lines 1391-1426)
- `InputStreamFromBlobAction` (lines 1446-1456)
- `InputStreamFromClobAction` (lines 1429-1443)

#### 4.4 Session Management Actions

**TerminateSessionAction** (lines 1484-1518)
- Session cleanup
- XA backend session return to pool
- Resource deallocation

#### 4.5 Transaction Management Actions

**StartTransactionAction** (lines 1521-1557)
- Regular transaction start
- Auto-commit management
- Lazy session allocation

**CommitTransactionAction** (lines 1560-1586)
- Regular transaction commit
- Transaction status tracking

**RollbackTransactionAction** (lines 1589-1615)
- Regular transaction rollback
- Transaction status tracking

#### 4.6 Resource Call Actions

**CallResourceAction** (lines 1618-1774)
- Generic resource method invocation via reflection
- Handles Statement, PreparedStatement, ResultSet, Connection, etc.
- Two-level method chaining support
- DB2 special result set metadata handling

**Helper Actions:**
- `Db2SpecialResultSetMetadataAction` (lines 1786-1808)

#### 4.7 XA Transaction Actions

**XaStartAction** (lines 2050-2097)
- XA transaction start
- Supports TMNOFLAGS, TMJOIN, TMRESUME
- Branches between pooled and pass-through modes
- Delegates to: `HandleXAStartWithPoolingAction`, `HandleXAStartPassThroughAction`

**XaEndAction** (lines 2160-2203)
- XA transaction end
- Branch association termination

**XaPrepareAction** (lines 2206-2253)
- XA prepare phase
- Returns vote (XA_OK or XA_RDONLY)

**XaCommitAction** (lines 2256-2305)
- XA commit phase
- One-phase or two-phase commit

**XaRollbackAction** (lines 2308-2354)
- XA rollback
- Transaction abort

**XaRecoverAction** (lines 2357-2384)
- XA recovery
- Returns prepared transactions

**XaForgetAction** (lines 2387-2413)
- XA forget
- Heuristic completion cleanup

**XaSetTransactionTimeoutAction** (lines 2416-2445)
- XA transaction timeout setter

**XaGetTransactionTimeoutAction** (lines 2448-2473)
- XA transaction timeout getter

**XaIsSameRMAction** (lines 2476-2505)
- XA resource manager comparison

#### 4.8 Shared Utility Actions

**ProcessClusterHealthAction** (lines 217-285)
- Cluster health monitoring
- Pool rebalancing trigger
- Used by multiple actions

**SessionConnectionAction** (lines 1820-1916)
- Connection acquisition (lazy allocation)
- Handles pooled/unpooled modes
- Handles XA/regular connections
- Critical shared utility

**HandleResultSetAction** (lines 1918-2040)
- ResultSet processing
- Row-by-row or batch mode
- LOB handling
- DB-specific optimizations

### 5. Package Structure

```
org.openjproxy.grpc.server
├── StatementServiceImpl.java (thin orchestrator)
├── action/
│   ├── ActionContext.java
│   ├── Action.java (interface)
│   ├── connection/
│   │   ├── ConnectAction.java
│   │   ├── HandleXAConnectionWithPoolingAction.java
│   │   ├── HandleUnpooledXAConnectionAction.java
│   │   └── InitializeXAPoolProviderAction.java
│   ├── statement/
│   │   ├── ExecuteUpdateAction.java
│   │   ├── ExecuteUpdateInternalAction.java
│   │   ├── ExecuteQueryAction.java
│   │   ├── ExecuteQueryInternalAction.java
│   │   └── FetchNextRowsAction.java
│   ├── lob/
│   │   ├── CreateLobAction.java
│   │   ├── ReadLobAction.java
│   │   ├── FindLobContextAction.java
│   │   ├── InputStreamFromBlobAction.java
│   │   └── InputStreamFromClobAction.java
│   ├── session/
│   │   ├── TerminateSessionAction.java
│   │   └── SessionConnectionAction.java
│   ├── transaction/
│   │   ├── StartTransactionAction.java
│   │   ├── CommitTransactionAction.java
│   │   └── RollbackTransactionAction.java
│   ├── resource/
│   │   ├── CallResourceAction.java
│   │   └── Db2SpecialResultSetMetadataAction.java
│   ├── xa/
│   │   ├── XaStartAction.java
│   │   ├── HandleXAStartWithPoolingAction.java
│   │   ├── HandleXAStartPassThroughAction.java
│   │   ├── XaEndAction.java
│   │   ├── XaPrepareAction.java
│   │   ├── XaCommitAction.java
│   │   ├── XaRollbackAction.java
│   │   ├── XaRecoverAction.java
│   │   ├── XaForgetAction.java
│   │   ├── XaSetTransactionTimeoutAction.java
│   │   ├── XaGetTransactionTimeoutAction.java
│   │   └── XaIsSameRMAction.java
│   └── util/
│       ├── ProcessClusterHealthAction.java
│       └── HandleResultSetAction.java
├── ... (existing classes)
```

## Refactored StatementServiceImpl

After refactoring, the `StatementServiceImpl` will be reduced to approximately 300-400 lines:

```java
@Slf4j
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {
    
    // Shared state maps (same as before)
    private final Map<String, DataSource> datasourceMap = new ConcurrentHashMap<>();
    private final Map<String, XADataSource> xaDataSourceMap = new ConcurrentHashMap<>();
    // ... etc
    
    // Service dependencies
    private final SessionManager sessionManager;
    private final CircuitBreaker circuitBreaker;
    private final ServerConfiguration serverConfiguration;
    
    // ActionContext instance (created once, reused)
    private final ActionContext actionContext;
    
    public StatementServiceImpl(SessionManager sessionManager, 
                                CircuitBreaker circuitBreaker, 
                                ServerConfiguration serverConfiguration) {
        this.sessionManager = sessionManager;
        this.circuitBreaker = circuitBreaker;
        this.serverConfiguration = serverConfiguration;
        
        // Initialize ActionContext
        this.actionContext = createActionContext();
        
        // Initialize XA Pool Provider
        new InitializeXAPoolProviderAction(actionContext).execute();
    }
    
    private ActionContext createActionContext() {
        return new ActionContext(
            datasourceMap,
            xaDataSourceMap,
            xaRegistries,
            unpooledConnectionDetailsMap,
            dbNameMap,
            slowQuerySegregationManagers,
            xaPoolProvider,
            xaCoordinator,
            clusterHealthTracker,
            sessionManager,
            circuitBreaker,
            serverConfiguration
        );
    }
    
    @Override
    public void connect(ConnectionDetails connectionDetails, 
                       StreamObserver<SessionInfo> responseObserver) {
        new ConnectAction(actionContext).execute(connectionDetails, responseObserver);
    }
    
    @Override
    public void executeUpdate(StatementRequest request, 
                             StreamObserver<OpResult> responseObserver) {
        new ExecuteUpdateAction(actionContext).execute(request, responseObserver);
    }
    
    @Override
    public void executeQuery(StatementRequest request, 
                            StreamObserver<OpResult> responseObserver) {
        new ExecuteQueryAction(actionContext).execute(request, responseObserver);
    }
    
    // ... Similar pattern for all other methods
}
```

## Migration Strategy

### Phase 1: Infrastructure Setup (Week 1)
1. Create `ActionContext` class with all shared state
2. Create `Action` interface
3. Set up package structure under `action/`
4. Create base test infrastructure for actions

### Phase 2: Simple Actions First (Week 2-3)
Start with simpler, independent actions:
1. `ProcessClusterHealthAction`
2. `TerminateSessionAction`
3. `StartTransactionAction`
4. `CommitTransactionAction`
5. `RollbackTransactionAction`

**Migration approach for each action:**
- Extract method logic to new Action class
- Update StatementServiceImpl to delegate to action
- Run existing tests to verify behavior unchanged
- Add unit tests for the action class

### Phase 3: Medium Complexity Actions (Week 4-5)
1. `ExecuteUpdateAction` and `ExecuteUpdateInternalAction`
2. `ExecuteQueryAction` and `ExecuteQueryInternalAction`
3. `FetchNextRowsAction`
4. `SessionConnectionAction`
5. `HandleResultSetAction`

### Phase 4: Complex Actions (Week 6-7)
1. `ConnectAction` and its helper actions
2. XA transaction actions (9 actions)
3. `CallResourceAction`

### Phase 5: Streaming Actions (Week 8)
1. `CreateLobAction` (complex streaming logic)
2. `ReadLobAction` and helper actions

### Phase 6: Cleanup and Optimization (Week 9)
1. Remove all extracted code from StatementServiceImpl
2. Final verification and testing
3. Performance testing to ensure no regression
4. Documentation updates

## Benefits of Refactoring

### 1. Single Responsibility Principle
Each Action class has one clear responsibility, making the code easier to understand and maintain.

### 2. Testability
Actions can be unit tested independently with mock ActionContext, enabling focused testing.

### 3. Reusability
Common logic in ActionContext can be shared across actions without code duplication.

### 4. Maintainability
Changes to one operation don't affect others. New features can be added as new actions.

### 5. Team Collaboration
Multiple developers can work on different actions simultaneously without conflicts.

### 6. Code Navigation
Developers can quickly find the code for a specific operation by navigating to its action class.

## Risks and Mitigations

### Risk 1: Breaking Changes
**Mitigation**: Comprehensive test coverage before and after each action extraction. Keep existing integration tests running.

### Risk 2: Performance Overhead
**Mitigation**: ActionContext is created once and reused. Action classes are lightweight. Performance testing after migration.

### Risk 3: Complexity in Shared State
**Mitigation**: ActionContext provides clear API for state access. Document state lifecycle carefully.

### Risk 4: Coordination Between Actions
**Mitigation**: Some actions may need to call other actions (e.g., ConnectAction calling helper actions). Allow actions to instantiate other actions with the same context.

## Testing Strategy

### Unit Testing
Each Action class will have its own unit test suite:
```java
@Test
public void testExecuteUpdateAction_Success() {
    // Arrange
    ActionContext mockContext = mock(ActionContext.class);
    ExecuteUpdateAction action = new ExecuteUpdateAction(mockContext);
    StatementRequest request = createTestRequest();
    StreamObserver<OpResult> observer = mock(StreamObserver.class);
    
    // Act
    action.execute(request, observer);
    
    // Assert
    verify(observer).onNext(any());
    verify(observer).onCompleted();
}
```

### Integration Testing
Existing integration tests should continue to work without modification since the public API of StatementServiceImpl remains unchanged.

### Regression Testing
Run full test suite after each phase to ensure no regressions.

## Metrics for Success

1. **Lines per class**: Each action class should be < 200 lines
2. **StatementServiceImpl size**: Reduced from 2,528 to < 500 lines
3. **Test coverage**: Maintain or improve current coverage
4. **Build time**: No significant increase
5. **Performance**: No degradation in benchmarks

## Alternative Considered: Command Pattern

An alternative approach would be using the Command pattern with a command factory. However, the Action pattern was chosen because:
1. Simpler and more direct
2. Less abstraction overhead
3. Easier to understand for new developers
4. Better suited for gRPC streaming methods

## Conclusion

This refactoring will transform StatementServiceImpl from a 2,528-line God class into a thin orchestrator that delegates to focused, testable Action classes. The migration can be done incrementally over 9 weeks with minimal risk, and the benefits in maintainability and testability will be substantial.

## Open Questions

1. Should ActionContext be immutable or mutable?
   - **Recommendation**: Mutable (wraps existing mutable maps), but provide clear documentation about thread safety
   
2. Should actions be stateless or stateful?
   - **Recommendation**: Stateless - create new instance per request, all state in ActionContext
   
3. Should we use dependency injection for actions?
   - **Recommendation**: No for Phase 1. Keep it simple with direct instantiation. Can add DI later if needed.

4. How to handle action composition (actions calling other actions)?
   - **Recommendation**: Allow actions to instantiate helper actions with same ActionContext. Document composition patterns.

5. Should we extract XidImpl converter to a separate utility?
   - **Recommendation**: Yes, create `XidConverter` utility class used by XA actions.
