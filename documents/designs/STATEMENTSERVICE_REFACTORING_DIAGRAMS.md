# StatementServiceImpl Refactoring - Architecture Diagrams

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    StatementServiceImpl                         │
│                    (Thin Orchestrator)                          │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │ - Map<String, DataSource> datasourceMap                   │ │
│  │ - Map<String, XADataSource> xaDataSourceMap               │ │
│  │ - Map<String, XATransactionRegistry> xaRegistries         │ │
│  │ - Map<String, UnpooledConnectionDetails> unpooled...      │ │
│  │ - Map<String, DbName> dbNameMap                           │ │
│  │ - Map<String, SlowQuerySegregationManager> slowQuery...   │ │
│  │                                                             │ │
│  │ - SessionManager sessionManager                            │ │
│  │ - CircuitBreaker circuitBreaker                           │ │
│  │ - ServerConfiguration serverConfiguration                  │ │
│  │                                                             │ │
│  │ - ActionContext actionContext                             │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
│  Public Methods (gRPC endpoints):                              │
│  + connect(details, observer)                                  │
│  + executeUpdate(request, observer)                            │
│  + executeQuery(request, observer)                             │
│  + fetchNextRows(request, observer)                            │
│  + createLob(observer)                                         │
│  + readLob(request, observer)                                  │
│  + terminateSession(info, observer)                            │
│  + startTransaction(info, observer)                            │
│  + commitTransaction(info, observer)                           │
│  + rollbackTransaction(info, observer)                         │
│  + callResource(request, observer)                             │
│  + xaStart/End/Prepare/Commit/Rollback/... (9 XA methods)    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ creates once, passes to all actions
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        ActionContext                            │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │ References to all shared state from StatementServiceImpl  │ │
│  │ (datasourceMap, xaRegistries, sessionManager, etc.)       │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
│  + getDatasourceMap()                                          │
│  + getSessionManager()                                         │
│  + getCircuitBreaker()                                         │
│  + ... (getters for all shared state)                         │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ passed to
                              ▼
      ┌───────────────────────────────────────────┐
      │   Action<TRequest, TResponse> (interface) │
      │   + execute(request, observer)            │
      └───────────────────────────────────────────┘
                              △
                              │ implements
            ┌─────────────────┴─────────────────┐
            │                                   │
  ┌─────────┴──────────┐          ┌───────────┴──────────┐
  │  ConnectAction     │          │ ExecuteUpdateAction  │
  │  ExecuteQueryAction│          │ XaStartAction        │
  │  ... (19+ actions) │          │ ... (more actions)   │
  └────────────────────┘          └──────────────────────┘
```

## Package Structure Diagram

```
org.openjproxy.grpc.server
│
├── StatementServiceImpl.java (400 lines - orchestrator)
│
├── action/
│   │
│   ├── Action.java (interface)
│   ├── StreamingAction.java (interface)
│   ├── InitAction.java (interface)
│   ├── ValueAction.java (interface)
│   ├── ActionContext.java (shared state holder)
│   │
│   ├── connection/
│   │   ├── ConnectAction.java
│   │   ├── HandleXAConnectionWithPoolingAction.java
│   │   ├── HandleUnpooledXAConnectionAction.java
│   │   ├── InitializeXAPoolProviderAction.java
│   │   └── CreateSlowQuerySegregationManagerAction.java
│   │
│   ├── statement/
│   │   ├── ExecuteUpdateAction.java
│   │   ├── ExecuteUpdateInternalAction.java
│   │   ├── ExecuteQueryAction.java
│   │   ├── ExecuteQueryInternalAction.java
│   │   └── FetchNextRowsAction.java
│   │
│   ├── lob/
│   │   ├── CreateLobAction.java
│   │   ├── ReadLobAction.java
│   │   ├── FindLobContextAction.java
│   │   ├── InputStreamFromBlobAction.java
│   │   └── InputStreamFromClobAction.java
│   │
│   ├── session/
│   │   ├── TerminateSessionAction.java
│   │   └── SessionConnectionAction.java
│   │
│   ├── transaction/
│   │   ├── StartTransactionAction.java
│   │   ├── CommitTransactionAction.java
│   │   └── RollbackTransactionAction.java
│   │
│   ├── resource/
│   │   ├── CallResourceAction.java
│   │   └── Db2SpecialResultSetMetadataAction.java
│   │
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
│   │
│   └── util/
│       ├── ProcessClusterHealthAction.java
│       └── HandleResultSetAction.java
│
├── (existing classes remain unchanged)
├── SessionManager.java
├── CircuitBreaker.java
├── ServerConfiguration.java
└── ...
```

## Action Execution Flow

### Example: executeUpdate() Flow

```
Client
  │
  │ gRPC call: executeUpdate(request, observer)
  ▼
┌─────────────────────────────────────────────────────────┐
│ StatementServiceImpl.executeUpdate()                    │
│   new ExecuteUpdateAction(actionContext)                │
│       .execute(request, observer)                       │
└─────────────────────────────────────────────────────────┘
  │
  ▼
┌─────────────────────────────────────────────────────────┐
│ ExecuteUpdateAction.execute()                           │
│                                                          │
│ 1. Generate statement hash                              │
│ 2. ProcessClusterHealthAction.execute()                 │
│ 3. CircuitBreaker.preCheck(hash)                        │
│ 4. Get SlowQuerySegregationManager                      │
│ 5. Execute with segregation:                            │
│    └──> ExecuteUpdateInternalAction.execute()          │
│ 6. Send result via observer                             │
│ 7. CircuitBreaker.onSuccess()                           │
└─────────────────────────────────────────────────────────┘
  │
  │ delegate to internal action
  ▼
┌─────────────────────────────────────────────────────────┐
│ ExecuteUpdateInternalAction.execute()                   │
│                                                          │
│ 1. SessionConnectionAction.execute() → get connection   │
│ 2. Prepare statement (regular or prepared)              │
│ 3. Set parameters                                       │
│ 4. Execute update                                       │
│ 5. Return OpResult                                      │
└─────────────────────────────────────────────────────────┘
  │
  │ return OpResult
  ▼
ExecuteUpdateAction → observer.onNext(result) → Client
```

### Example: connect() Flow (XA Connection)

```
Client
  │
  │ gRPC call: connect(details, observer)
  ▼
┌─────────────────────────────────────────────────────────┐
│ StatementServiceImpl.connect()                          │
│   new ConnectAction(actionContext)                      │
│       .execute(details, observer)                       │
└─────────────────────────────────────────────────────────┘
  │
  ▼
┌─────────────────────────────────────────────────────────┐
│ ConnectAction.execute()                                 │
│                                                          │
│ 1. Check if empty (health check)                        │
│ 2. Generate connection hash                             │
│ 3. Determine connection type (XA)                       │
│ 4. Calculate multinode XA allocation                    │
│ 5. Delegate to:                                         │
│    └──> HandleXAConnectionWithPoolingAction.execute()  │
└─────────────────────────────────────────────────────────┘
  │
  │ delegate to pooled XA handler
  ▼
┌─────────────────────────────────────────────────────────┐
│ HandleXAConnectionWithPoolingAction.execute()           │
│                                                          │
│ 1. Check if XA registry exists (cache lookup)           │
│ 2. If not, create:                                      │
│    a. Parse URL                                         │
│    b. Get XA configuration                              │
│    c. Apply multinode coordination                      │
│    d. Create pooled XA DataSource via provider          │
│    e. Create XATransactionRegistry                      │
│    f. Put in xaRegistries map                           │
│    g. Create slow query manager                         │
│ 3. Process cluster health (trigger rebalancing)         │
│ 4. Borrow XABackendSession from pool                    │
│ 5. Create XA session via SessionManager                 │
│ 6. Store backend session in OJP session                 │
│ 7. Return SessionInfo via observer                      │
└─────────────────────────────────────────────────────────┘
  │
  │ return SessionInfo
  ▼
ConnectAction → observer.onNext(sessionInfo) → Client
```

## State Management Flow

```
┌────────────────────────────────────────────────────────────────┐
│                    Application Startup                          │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────┐
│ new StatementServiceImpl(sessionManager, circuitBreaker, ...)  │
│   │                                                             │
│   ├─> Initialize empty maps:                                   │
│   │    - datasourceMap                                         │
│   │    - xaDataSourceMap                                       │
│   │    - xaRegistries                                          │
│   │    - unpooledConnectionDetailsMap                          │
│   │    - dbNameMap                                             │
│   │    - slowQuerySegregationManagers                          │
│   │                                                             │
│   ├─> Initialize coordinators/trackers:                        │
│   │    - xaCoordinator (static, shared)                        │
│   │    - clusterHealthTracker                                  │
│   │                                                             │
│   ├─> Store service dependencies:                              │
│   │    - sessionManager                                        │
│   │    - circuitBreaker                                        │
│   │    - serverConfiguration                                   │
│   │                                                             │
│   ├─> Create ActionContext with all above                      │
│   │                                                             │
│   └─> InitializeXAPoolProviderAction.execute()                │
│       (loads XA Pool Provider via SPI)                         │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────┐
│              First connect() for connection hash X             │
│                                                                 │
│ ConnectAction checks datasourceMap.get(X) → null              │
│   │                                                             │
│   ├─> Creates DataSource or XADataSource                       │
│   ├─> Puts in datasourceMap or xaRegistries                    │
│   ├─> Creates SlowQuerySegregationManager                      │
│   ├─> Puts in slowQuerySegregationManagers                     │
│   └─> Puts DbName in dbNameMap                                │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────┐
│           Subsequent calls for connection hash X               │
│                                                                 │
│ All actions check maps → found → reuse cached resources        │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────┐
│                    Application Shutdown                         │
│                                                                 │
│ (Future enhancement: cleanup actions for graceful shutdown)    │
└────────────────────────────────────────────────────────────────┘
```

## Action Dependency Graph

```
                          ActionContext
                               │
                ┌──────────────┼──────────────┐
                │              │              │
                ▼              ▼              ▼
         ConnectAction  ExecuteUpdateAction  XaStartAction
                │              │              │
        ┌───────┼───────┐      │      ┌───────┼────────┐
        │       │       │      │      │       │        │
        ▼       ▼       ▼      ▼      ▼       ▼        ▼
  HandleXA   HandleXA  Create  Execute  HandleXA  HandleXA  Process
  WithPool   Unpooled  SlowQ   Internal WithPool  PassThru  Cluster
   Action    Action    Manager  Action   Action    Action   Health
                │      Action           Action              Action
                │                         │
                ▼                         ▼
        CreateSlowQuery            SessionConnection
        SegregationManager         Action
        Action                           │
                                         ▼
                                   HandleResultSet
                                   Action

Legend:
  → : "uses" / "delegates to"
  All actions have access to ActionContext
  Helper actions are created and called by primary actions
```

## Threading and Concurrency Model

```
┌──────────────────────────────────────────────────────────┐
│                 gRPC Thread Pool                         │
│  (Multiple threads serving concurrent client requests)   │
└──────────────────────────────────────────────────────────┘
    │         │         │         │         │
    │ Thread1 │ Thread2 │ Thread3 │ Thread4 │ ...
    ▼         ▼         ▼         ▼         ▼
┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
│ Action  │ │ Action  │ │ Action  │ │ Action  │
│Instance1│ │Instance2│ │Instance3│ │Instance4│
│(new)    │ │(new)    │ │(new)    │ │(new)    │
└─────────┘ └─────────┘ └─────────┘ └─────────┘
    │         │         │         │
    └─────────┴─────────┴─────────┴─────────────────┐
                                                     │
                           All access same           │
                                ▼                    │
              ┌──────────────────────────────────┐  │
              │      ActionContext (Singleton)    │  │
              │  ┌────────────────────────────┐  │  │
              │  │ ConcurrentHashMap instances│  │  │
              │  │ - datasourceMap            │  │  │
              │  │ - xaRegistries             │  │  │
              │  │ - slowQuerySegMgrs         │  │  │
              │  │ - etc.                     │  │  │
              │  └────────────────────────────┘  │  │
              └──────────────────────────────────┘  │
                               │                    │
                               │                    │
              ┌────────────────┴─────────────────┐  │
              │   Thread-Safe Service Objects    │  │
              │   - SessionManager               │  │
              │   - CircuitBreaker               │  │
              │   - ClusterHealthTracker         │  │
              └──────────────────────────────────┘  │
                                                     │
Characteristics:                                     │
- Actions are STATELESS (new instance per request)  │
- ActionContext is SHARED (one instance, thread-safe)
- Maps are ConcurrentHashMap (thread-safe)          │
- Service objects are thread-safe                   │
- No synchronization needed in action classes       │
```

## Before vs After Comparison

### Before Refactoring

```
┌────────────────────────────────────────────────────────┐
│         StatementServiceImpl (God Class)               │
│                    2,528 lines                         │
│                                                        │
│  ┌──────────────────────────────────────────────────┐ │
│  │ All shared state (9 maps + 3 services)          │ │
│  └──────────────────────────────────────────────────┘ │
│                                                        │
│  ┌──────────────────────────────────────────────────┐ │
│  │ 21 public methods (gRPC endpoints)               │ │
│  │   - connect() [143 lines]                        │ │
│  │   - executeUpdate() [42 lines]                   │ │
│  │   - executeQuery() [36 lines]                    │ │
│  │   - createLob() [198 lines!!]                    │ │
│  │   - readLob() [74 lines]                         │ │
│  │   - xaStart/End/Prepare/Commit... [10+ methods]  │ │
│  │   - ...                                          │ │
│  └──────────────────────────────────────────────────┘ │
│                                                        │
│  ┌──────────────────────────────────────────────────┐ │
│  │ 14+ private helper methods                       │ │
│  │   - handleXAConnectionWithPooling() [269 lines!] │ │
│  │   - executeUpdateInternal() [83 lines]           │ │
│  │   - handleResultSet() [122 lines]                │ │
│  │   - sessionConnection() [96 lines]               │ │
│  │   - ...                                          │ │
│  └──────────────────────────────────────────────────┘ │
│                                                        │
│  Problems:                                            │
│  ❌ Hard to understand (too many responsibilities)    │
│  ❌ Hard to test (one giant test suite)               │
│  ❌ Hard to maintain (changes affect everything)      │
│  ❌ Hard to collaborate (merge conflicts)             │
│  ❌ Hard to navigate (find specific functionality)    │
└────────────────────────────────────────────────────────┘
```

### After Refactoring

```
┌────────────────────────────────────────────────────────┐
│      StatementServiceImpl (Thin Orchestrator)          │
│                    ~400 lines                          │
│                                                        │
│  ┌──────────────────────────────────────────────────┐ │
│  │ All shared state (9 maps + 3 services)          │ │
│  │ + ActionContext (wraps all state)               │ │
│  └──────────────────────────────────────────────────┘ │
│                                                        │
│  ┌──────────────────────────────────────────────────┐ │
│  │ 21 public methods (simple delegators)            │ │
│  │   public void connect(...) {                     │ │
│  │     new ConnectAction(context)                   │ │
│  │         .execute(details, observer);             │ │
│  │   }                                              │ │
│  │   ... (each method is 3-5 lines)                │ │
│  └──────────────────────────────────────────────────┘ │
│                                                        │
│  No private methods! (All extracted to actions)       │
└────────────────────────────────────────────────────────┘
                         │
                         │ delegates to
                         ▼
┌────────────────────────────────────────────────────────┐
│              Action Package (~35 classes)              │
│                                                        │
│  ┌──────────────────────────────────────────────────┐ │
│  │ ConnectAction (~120 lines)                       │ │
│  ├──────────────────────────────────────────────────┤ │
│  │ ExecuteUpdateAction (~80 lines)                  │ │
│  ├──────────────────────────────────────────────────┤ │
│  │ ExecuteQueryAction (~70 lines)                   │ │
│  ├──────────────────────────────────────────────────┤ │
│  │ CreateLobAction (~150 lines)                     │ │
│  ├──────────────────────────────────────────────────┤ │
│  │ ... (each action is focused and testable)        │ │
│  └──────────────────────────────────────────────────┘ │
│                                                        │
│  Benefits:                                            │
│  ✅ Easy to understand (single responsibility)         │
│  ✅ Easy to test (focused unit tests per action)      │
│  ✅ Easy to maintain (changes localized)              │
│  ✅ Easy to collaborate (no conflicts)                │
│  ✅ Easy to navigate (find action by name)            │
└────────────────────────────────────────────────────────┘
```

## Testing Strategy Visualization

```
┌──────────────────────────────────────────────────────────┐
│                  Testing Pyramid                         │
└──────────────────────────────────────────────────────────┘

                        ▲
                       ╱│╲
                      ╱ │ ╲
                     ╱  │  ╲
                    ╱   │   ╲
                   ╱    │    ╲
                  ╱     │     ╲
                 ╱      │      ╲
                ╱───────┼───────╲
               ╱  E2E   │ Few    ╲
              ╱  Tests  │ Tests   ╲
             ╱───────────┼──────────╲
            ╱Integration │ Some      ╲
           ╱   Tests     │ Tests      ╲
          ╱──────────────┼─────────────╲
         ╱   Unit Tests  │ Many Tests   ╲
        ╱    (Actions)   │ (Fast)        ╲
       ╱─────────────────┼────────────────╲
      ╱                  │                 ╲
     ╱                   │                  ╲
    ╱────────────────────┴───────────────────╲

Unit Tests (Actions):
  - Mock ActionContext
  - Test each action in isolation
  - Fast execution (milliseconds)
  - High coverage (80%+)

Integration Tests:
  - Use real ActionContext
  - Test action composition
  - Medium execution (seconds)
  - Cover critical paths

E2E Tests:
  - Full StatementServiceImpl
  - Real gRPC calls
  - Slow execution (minutes)
  - Cover main use cases
```

## Conclusion

This architecture provides:

1. **Clear Separation of Concerns**: Each action has one responsibility
2. **Shared State Management**: ActionContext provides controlled access
3. **Easy Testing**: Actions can be unit tested independently
4. **Maintainability**: Changes are localized to specific actions
5. **Team Collaboration**: Multiple developers can work on different actions
6. **Navigation**: Easy to find code for specific operations

The refactoring transforms a 2,528-line God class into a well-organized set of focused, testable components while maintaining the same public API and functionality.
