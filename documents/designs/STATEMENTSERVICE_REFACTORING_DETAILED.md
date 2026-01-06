# StatementServiceImpl Refactoring - Detailed Design

## Table of Contents
1. [Class Relationship Diagram](#class-relationship-diagram)
2. [ActionContext Detailed Design](#actioncontext-detailed-design)
3. [Action Interface Hierarchy](#action-interface-hierarchy)
4. [Detailed Action Class Designs](#detailed-action-class-designs)
5. [State Management Strategy](#state-management-strategy)
6. [Error Handling Strategy](#error-handling-strategy)
7. [Implementation Examples](#implementation-examples)

## Class Relationship Diagram

```
                        StatementServiceImpl
                        (Orchestrator ~400 lines)
                                |
                                | creates once
                                v
                         ActionContext
                      (Shared State Holder)
                                |
                                | passed to
                                v
                    +-----------+------------+
                    |                        |
              Action<TRequest, TResponse>    |
              (Interface)                    |
                    |                        |
                    |                        |
    +---------------+----------------+       |
    |               |                |       |
ConnectAction  ExecuteUpdateAction  XaStartAction  ... (19+ actions)
    |               |                |
    v               v                v
Helper Actions  Internal Actions  XA Helper Actions
```

## ActionContext Detailed Design

### Full Class Structure

```java
package org.openjproxy.grpc.server.action;

import org.openjproxy.grpc.server.*;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.DataSource;
import javax.sql.XADataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ActionContext holds all shared state and dependencies needed by Action classes.
 * This context is created once in StatementServiceImpl and passed to all actions.
 * 
 * Thread Safety: This class is thread-safe. All maps are ConcurrentHashMap.
 * Actions should not modify the context itself, only the data within the maps.
 */
public class ActionContext {
    
    // ========== Data Source Management ==========
    
    /**
     * Map of connection hash to regular DataSource (HikariCP by default).
     * Key: connection hash (from ConnectionHashGenerator)
     * Value: pooled DataSource for regular (non-XA) connections
     */
    private final Map<String, DataSource> datasourceMap;
    
    /**
     * Map of connection hash to unpooled XADataSource.
     * Used when XA pooling is disabled (ojp.xa.connection.pool.enabled=false).
     * Key: connection hash
     * Value: native database XADataSource (not pooled)
     */
    private final Map<String, XADataSource> xaDataSourceMap;
    
    /**
     * Map of connection hash to XATransactionRegistry.
     * Used when XA pooling is enabled (default).
     * Key: connection hash
     * Value: registry managing pooled XA connections and transactions
     */
    private final Map<String, XATransactionRegistry> xaRegistries;
    
    /**
     * Map of connection hash to unpooled connection details.
     * Used when regular pooling is disabled (ojp.connection.pool.enabled=false).
     * Key: connection hash
     * Value: connection details for creating direct JDBC connections
     */
    private final Map<String, StatementServiceImpl.UnpooledConnectionDetails> unpooledConnectionDetailsMap;
    
    /**
     * Map of connection hash to database type.
     * Used for database-specific behavior (e.g., DB2 LOB handling).
     * Key: connection hash
     * Value: DbName enum (POSTGRES, ORACLE, MYSQL, etc.)
     */
    private final Map<String, DbName> dbNameMap;
    
    // ========== Query Management ==========
    
    /**
     * Map of connection hash to SlowQuerySegregationManager.
     * Each datasource gets its own manager for segregating slow/fast queries.
     * Key: connection hash
     * Value: manager for this datasource's slow query segregation
     */
    private final Map<String, SlowQuerySegregationManager> slowQuerySegregationManagers;
    
    // ========== XA Pool Provider ==========
    
    /**
     * XA Connection Pool Provider loaded via SPI.
     * Used for creating and managing pooled XA connections.
     * Mutable because it's initialized after construction.
     */
    private XAConnectionPoolProvider xaPoolProvider;
    
    // ========== Coordinators & Trackers ==========
    
    /**
     * Multinode XA coordinator for distributing transaction limits across nodes.
     * Static in original class, shared across all instances.
     */
    private final MultinodeXaCoordinator xaCoordinator;
    
    /**
     * Cluster health tracker for monitoring health changes and triggering rebalancing.
     */
    private final ClusterHealthTracker clusterHealthTracker;
    
    // ========== Service Dependencies ==========
    
    /**
     * Session manager for managing JDBC sessions, connections, and resources.
     * Thread-safe, shared across all actions.
     */
    private final SessionManager sessionManager;
    
    /**
     * Circuit breaker for protecting against cascading failures.
     * Thread-safe, shared across all actions.
     */
    private final CircuitBreaker circuitBreaker;
    
    /**
     * Server-wide configuration.
     * Immutable after construction.
     */
    private final ServerConfiguration serverConfiguration;
    
    // ========== Constructor ==========
    
    public ActionContext(
            Map<String, DataSource> datasourceMap,
            Map<String, XADataSource> xaDataSourceMap,
            Map<String, XATransactionRegistry> xaRegistries,
            Map<String, StatementServiceImpl.UnpooledConnectionDetails> unpooledConnectionDetailsMap,
            Map<String, DbName> dbNameMap,
            Map<String, SlowQuerySegregationManager> slowQuerySegregationManagers,
            XAConnectionPoolProvider xaPoolProvider,
            MultinodeXaCoordinator xaCoordinator,
            ClusterHealthTracker clusterHealthTracker,
            SessionManager sessionManager,
            CircuitBreaker circuitBreaker,
            ServerConfiguration serverConfiguration) {
        
        this.datasourceMap = datasourceMap;
        this.xaDataSourceMap = xaDataSourceMap;
        this.xaRegistries = xaRegistries;
        this.unpooledConnectionDetailsMap = unpooledConnectionDetailsMap;
        this.dbNameMap = dbNameMap;
        this.slowQuerySegregationManagers = slowQuerySegregationManagers;
        this.xaPoolProvider = xaPoolProvider;
        this.xaCoordinator = xaCoordinator;
        this.clusterHealthTracker = clusterHealthTracker;
        this.sessionManager = sessionManager;
        this.circuitBreaker = circuitBreaker;
        this.serverConfiguration = serverConfiguration;
    }
    
    // ========== Getters ==========
    
    public Map<String, DataSource> getDatasourceMap() {
        return datasourceMap;
    }
    
    public Map<String, XADataSource> getXaDataSourceMap() {
        return xaDataSourceMap;
    }
    
    public Map<String, XATransactionRegistry> getXaRegistries() {
        return xaRegistries;
    }
    
    public Map<String, StatementServiceImpl.UnpooledConnectionDetails> getUnpooledConnectionDetailsMap() {
        return unpooledConnectionDetailsMap;
    }
    
    public Map<String, DbName> getDbNameMap() {
        return dbNameMap;
    }
    
    public Map<String, SlowQuerySegregationManager> getSlowQuerySegregationManagers() {
        return slowQuerySegregationManagers;
    }
    
    public XAConnectionPoolProvider getXaPoolProvider() {
        return xaPoolProvider;
    }
    
    public void setXaPoolProvider(XAConnectionPoolProvider xaPoolProvider) {
        this.xaPoolProvider = xaPoolProvider;
    }
    
    public MultinodeXaCoordinator getXaCoordinator() {
        return xaCoordinator;
    }
    
    public ClusterHealthTracker getClusterHealthTracker() {
        return clusterHealthTracker;
    }
    
    public SessionManager getSessionManager() {
        return sessionManager;
    }
    
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
    
    public ServerConfiguration getServerConfiguration() {
        return serverConfiguration;
    }
}
```

## Action Interface Hierarchy

### Base Action Interface

```java
package org.openjproxy.grpc.server.action;

import io.grpc.stub.StreamObserver;

/**
 * Base interface for all action classes.
 * 
 * @param <TRequest> The gRPC request type
 * @param <TResponse> The gRPC response type
 */
@FunctionalInterface
public interface Action<TRequest, TResponse> {
    /**
     * Execute the action with the given request and response observer.
     * 
     * @param request The gRPC request
     * @param responseObserver The gRPC response observer for sending responses
     */
    void execute(TRequest request, StreamObserver<TResponse> responseObserver);
}
```

### Specialized Interfaces

```java
/**
 * Action that returns a streaming observer (for bidirectional streaming).
 * Used by CreateLobAction.
 */
public interface StreamingAction<TRequest, TResponse> {
    StreamObserver<TRequest> execute(StreamObserver<TResponse> responseObserver);
}

/**
 * Action with no request (initialization actions).
 */
public interface InitAction {
    void execute();
}

/**
 * Action that returns a value (for internal actions).
 */
public interface ValueAction<TRequest, TResult> {
    TResult execute(TRequest request) throws Exception;
}
```

## Detailed Action Class Designs

### 1. ConnectAction

**Responsibility**: Handle connection establishment for regular and XA connections.

**Key Dependencies**:
- ActionContext (all fields)
- HandleXAConnectionWithPoolingAction
- HandleUnpooledXAConnectionAction

**Algorithm**:
```
1. Check if empty connection details (health check)
   - If yes, return empty SessionInfo
2. Generate connection hash
3. Determine connection type (XA vs regular)
4. If XA:
   a. Check multinode configuration
   b. Calculate XA transaction limits
   c. Check if XA Pool Provider available
   d. Delegate to HandleXAConnectionWithPoolingAction or HandleUnpooledXAConnectionAction
5. If regular:
   a. Check if datasource/unpooled details exist
   b. If not, get configuration and create datasource or unpooled details
   c. Apply multinode pool coordination if needed
   d. Create slow query segregation manager
6. Register client UUID
7. Return SessionInfo
```

**Class Structure**:
```java
public class ConnectAction implements Action<ConnectionDetails, SessionInfo> {
    private final ActionContext context;
    private static final Logger log = LoggerFactory.getLogger(ConnectAction.class);
    
    public ConnectAction(ActionContext context) {
        this.context = context;
    }
    
    @Override
    public void execute(ConnectionDetails connectionDetails, 
                       StreamObserver<SessionInfo> responseObserver) {
        // Implementation...
    }
    
    private void handleRegularConnection(ConnectionDetails details, String connHash,
                                        StreamObserver<SessionInfo> observer) {
        // Helper method...
    }
    
    private void handleXAConnection(ConnectionDetails details, String connHash,
                                   int maxXaTransactions, long xaStartTimeoutMillis,
                                   StreamObserver<SessionInfo> observer) {
        // Helper method...
    }
}
```

### 2. ExecuteUpdateAction

**Responsibility**: Handle SQL UPDATE/INSERT/DELETE operations with circuit breaker and slow query segregation.

**Key Dependencies**:
- CircuitBreaker (from context)
- SlowQuerySegregationManager (from context)
- ExecuteUpdateInternalAction

**Algorithm**:
```
1. Generate statement hash from SQL
2. Process cluster health
3. Circuit breaker pre-check
4. Get slow query segregation manager for this connection
5. Execute with segregation:
   a. Delegate to ExecuteUpdateInternalAction
6. On success:
   a. Send result to observer
   b. Complete observer
   c. Circuit breaker on success
7. On failure:
   a. Circuit breaker on failure
   b. Send SQL exception metadata
```

**Class Structure**:
```java
public class ExecuteUpdateAction implements Action<StatementRequest, OpResult> {
    private final ActionContext context;
    private static final Logger log = LoggerFactory.getLogger(ExecuteUpdateAction.class);
    
    public ExecuteUpdateAction(ActionContext context) {
        this.context = context;
    }
    
    @Override
    public void execute(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());
        
        // Process cluster health
        new ProcessClusterHealthAction(context).execute(request.getSession());
        
        try {
            context.getCircuitBreaker().preCheck(stmtHash);
            
            String connHash = request.getSession().getConnHash();
            SlowQuerySegregationManager manager = 
                getSlowQuerySegregationManagerForConnection(connHash);
            
            OpResult result = manager.executeWithSegregation(stmtHash, () -> {
                return new ExecuteUpdateInternalAction(context).execute(request);
            });
            
            responseObserver.onNext(result);
            responseObserver.onCompleted();
            context.getCircuitBreaker().onSuccess(stmtHash);
            
        } catch (SQLDataException e) {
            // Handle SQL data exception...
        } catch (SQLException e) {
            // Handle SQL exception...
        } catch (Exception e) {
            // Handle unexpected exception...
        }
    }
    
    private SlowQuerySegregationManager getSlowQuerySegregationManagerForConnection(String connHash) {
        // Implementation from original class...
    }
}
```

### 3. XaStartAction

**Responsibility**: Handle XA transaction start with different flags (TMNOFLAGS, TMJOIN, TMRESUME).

**Key Dependencies**:
- XATransactionRegistry (from context)
- SessionManager (from context)
- HandleXAStartWithPoolingAction
- HandleXAStartPassThroughAction

**Algorithm**:
```
1. Process cluster health
2. Get session from SessionManager
3. Verify session is XA
4. Check if XA Pool Provider available
5. If pooled:
   a. Delegate to HandleXAStartWithPoolingAction
6. If pass-through:
   a. Delegate to HandleXAStartPassThroughAction
7. Return XaResponse
```

**Class Structure**:
```java
public class XaStartAction implements Action<XaStartRequest, XaResponse> {
    private final ActionContext context;
    private static final Logger log = LoggerFactory.getLogger(XaStartAction.class);
    
    public XaStartAction(ActionContext context) {
        this.context = context;
    }
    
    @Override
    public void execute(XaStartRequest request, StreamObserver<XaResponse> responseObserver) {
        log.debug("xaStart: session={}, xid={}, flags={}", 
                request.getSession().getSessionUUID(), request.getXid(), request.getFlags());
        
        // Process cluster health
        new ProcessClusterHealthAction(context).execute(request.getSession());
        
        Session session = null;
        try {
            session = context.getSessionManager().getSession(request.getSession());
            if (session == null || !session.isXA()) {
                throw new SQLException("Session is not an XA session");
            }
            
            if (context.getXaPoolProvider() != null) {
                new HandleXAStartWithPoolingAction(context)
                    .execute(request, session, responseObserver);
            } else {
                new HandleXAStartPassThroughAction(context)
                    .execute(request, session, responseObserver);
            }
            
        } catch (Exception e) {
            log.error("Error in xaStart", e);
            SQLException sqlException = (e instanceof SQLException) ? 
                (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }
}
```

### 4. CreateLobAction

**Responsibility**: Handle bidirectional streaming LOB creation (BLOB, CLOB, binary stream).

**Key Dependencies**:
- SessionManager (from context)
- SessionConnectionAction

**Unique Characteristics**:
- Returns StreamObserver (bidirectional streaming)
- Complex state machine for streaming
- Handles first block, subsequent blocks, and completion separately

**Class Structure**:
```java
public class CreateLobAction implements StreamingAction<LobDataBlock, LobReference> {
    private final ActionContext context;
    private static final Logger log = LoggerFactory.getLogger(CreateLobAction.class);
    
    public CreateLobAction(ActionContext context) {
        this.context = context;
    }
    
    @Override
    public StreamObserver<LobDataBlock> execute(StreamObserver<LobReference> responseObserver) {
        log.info("Creating LOB");
        return new CreateLobStreamObserver(context, responseObserver);
    }
    
    /**
     * Inner class implementing ServerCallStreamObserver for LOB streaming.
     */
    private static class CreateLobStreamObserver implements ServerCallStreamObserver<LobDataBlock> {
        private final ActionContext context;
        private final StreamObserver<LobReference> responseObserver;
        
        private SessionInfo sessionInfo;
        private String lobUUID;
        private String stmtUUID;
        private LobType lobType;
        private LobDataBlocksInputStream lobDataBlocksInputStream = null;
        private final AtomicBoolean isFirstBlock = new AtomicBoolean(true);
        private final AtomicInteger countBytesWritten = new AtomicInteger(0);
        
        public CreateLobStreamObserver(ActionContext context, 
                                      StreamObserver<LobReference> responseObserver) {
            this.context = context;
            this.responseObserver = responseObserver;
        }
        
        @Override
        public void onNext(LobDataBlock lobDataBlock) {
            // Implementation...
        }
        
        @Override
        public void onError(Throwable throwable) {
            // Implementation...
        }
        
        @Override
        public void onCompleted() {
            // Implementation...
        }
        
        // Other ServerCallStreamObserver methods...
    }
}
```

## State Management Strategy

### Shared Mutable State

The following state is shared across actions and can be modified:

1. **datasourceMap**: Modified by ConnectAction when creating new datasources
2. **xaDataSourceMap**: Modified by HandleUnpooledXAConnectionAction
3. **xaRegistries**: Modified by HandleXAConnectionWithPoolingAction
4. **unpooledConnectionDetailsMap**: Modified by ConnectAction
5. **dbNameMap**: Modified by ConnectAction
6. **slowQuerySegregationManagers**: Modified by ConnectAction

### Thread Safety

All shared maps are `ConcurrentHashMap`, providing thread-safe operations. Actions should:
- Use `computeIfAbsent` for put-if-absent semantics
- Use `putIfAbsent` for atomic checks
- Never expose map internals

### State Lifecycle

```
StatementServiceImpl construction
    ↓
Create ActionContext (empty maps)
    ↓
First connect() call for a connection hash
    ↓
Action populates maps for that hash
    ↓
Subsequent calls reuse cached state
    ↓
(Optional) Cleanup on shutdown
```

## Error Handling Strategy

### Exception Handling Patterns

All actions should follow these patterns:

#### 1. SQL Exceptions
```java
try {
    // Action logic
} catch (SQLException se) {
    log.error("SQL error in action: {}", se.getMessage(), se);
    GrpcExceptionHandler.sendSQLExceptionMetadata(se, responseObserver);
}
```

#### 2. Wrapped Exceptions
```java
try {
    // Action logic
} catch (InvocationTargetException e) {
    if (e.getTargetException() instanceof SQLException) {
        SQLException sqlException = (SQLException) e.getTargetException();
        sendSQLExceptionMetadata(sqlException, responseObserver);
    } else {
        sendSQLExceptionMetadata(
            new SQLException("Unable to call resource: " + 
                e.getTargetException().getMessage()), 
            responseObserver);
    }
}
```

#### 3. Generic Exceptions
```java
try {
    // Action logic
} catch (Exception e) {
    log.error("Unexpected error in action: {}", e.getMessage(), e);
    if (e.getCause() instanceof SQLException) {
        sendSQLExceptionMetadata((SQLException) e.getCause(), responseObserver);
    } else {
        SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
        sendSQLExceptionMetadata(sqlException, responseObserver);
    }
}
```

### Resource Cleanup

Actions that acquire resources must clean them up:

```java
Connection conn = null;
Statement stmt = null;
try {
    // Use resources
} finally {
    if (stmt != null) {
        try {
            stmt.close();
        } catch (SQLException e) {
            log.error("Failure closing statement: {}", e.getMessage(), e);
        }
    }
    if (conn != null) {
        try {
            conn.close();
        } catch (SQLException e) {
            log.error("Failure closing connection: {}", e.getMessage(), e);
        }
    }
}
```

## Implementation Examples

### Example 1: Simple Action (StartTransactionAction)

```java
package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TransactionInfo;
import com.openjproxy.grpc.TransactionStatus;
import io.grpc.stub.StreamObserver;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.utils.SessionInfoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * Action to start a regular (non-XA) transaction.
 * Sets autoCommit to false and returns updated SessionInfo with transaction UUID.
 */
public class StartTransactionAction implements Action<SessionInfo, SessionInfo> {
    private static final Logger log = LoggerFactory.getLogger(StartTransactionAction.class);
    
    private final ActionContext context;
    
    public StartTransactionAction(ActionContext context) {
        this.context = context;
    }
    
    @Override
    public void execute(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.info("Starting transaction");
        
        // Process cluster health from the request
        new ProcessClusterHealthAction(context).execute(sessionInfo);
        
        try {
            SessionInfo activeSessionInfo = sessionInfo;
            
            // Start a session if none started yet (lazy allocation)
            if (StringUtils.isEmpty(sessionInfo.getSessionUUID())) {
                String connHash = sessionInfo.getConnHash();
                DataSource dataSource = context.getDatasourceMap().get(connHash);
                if (dataSource == null) {
                    throw new SQLException("No datasource found for connection hash: " + connHash);
                }
                
                Connection conn = dataSource.getConnection();
                activeSessionInfo = context.getSessionManager()
                    .createSession(sessionInfo.getClientUUID(), conn);
                
                // Preserve targetServer from incoming request
                activeSessionInfo = SessionInfoUtils.withTargetServer(
                    activeSessionInfo, 
                    getTargetServer(sessionInfo));
            }
            
            // Get connection and start transaction
            Connection sessionConnection = context.getSessionManager()
                .getConnection(activeSessionInfo);
            sessionConnection.setAutoCommit(Boolean.FALSE);
            
            // Create transaction info
            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ACTIVE)
                    .setTransactionUUID(UUID.randomUUID().toString())
                    .build();
            
            // Build response
            SessionInfo.Builder sessionInfoBuilder = SessionInfoUtils.newBuilderFrom(activeSessionInfo);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);
            
            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
            
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(
                new SQLException("Unable to start transaction: " + e.getMessage()), 
                responseObserver);
        }
    }
    
    private String getTargetServer(SessionInfo incomingSessionInfo) {
        if (incomingSessionInfo != null && 
            incomingSessionInfo.getTargetServer() != null && 
            !incomingSessionInfo.getTargetServer().isEmpty()) {
            return incomingSessionInfo.getTargetServer();
        }
        return "";
    }
}
```

### Example 2: Complex Action with Helper Actions (ConnectAction - Simplified)

```java
package org.openjproxy.grpc.server.action.connection;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.utils.ConnectionHashGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Action to establish a database connection (regular or XA).
 * This is one of the most complex actions, handling multiple connection modes.
 */
public class ConnectAction implements Action<ConnectionDetails, SessionInfo> {
    private static final Logger log = LoggerFactory.getLogger(ConnectAction.class);
    
    private final ActionContext context;
    
    public ConnectAction(ActionContext context) {
        this.context = context;
    }
    
    @Override
    public void execute(ConnectionDetails connectionDetails, 
                       StreamObserver<SessionInfo> responseObserver) {
        
        // Handle empty connection details (health check)
        if (isEmptyConnectionDetails(connectionDetails)) {
            responseObserver.onNext(SessionInfo.newBuilder().build());
            responseObserver.onCompleted();
            return;
        }
        
        String connHash = ConnectionHashGenerator.hashConnectionDetails(connectionDetails);
        
        // Branch based on connection type
        if (connectionDetails.getIsXA()) {
            handleXAConnection(connectionDetails, connHash, responseObserver);
        } else {
            handleRegularConnection(connectionDetails, connHash, responseObserver);
        }
    }
    
    private boolean isEmptyConnectionDetails(ConnectionDetails details) {
        return StringUtils.isBlank(details.getUrl()) &&
               StringUtils.isBlank(details.getUser()) &&
               StringUtils.isBlank(details.getPassword());
    }
    
    private void handleXAConnection(ConnectionDetails details, String connHash,
                                   StreamObserver<SessionInfo> observer) {
        // Calculate XA limits
        int maxXaTransactions = org.openjproxy.constants.CommonConstants.DEFAULT_MAX_XA_TRANSACTIONS;
        long xaStartTimeoutMillis = org.openjproxy.constants.CommonConstants.DEFAULT_XA_START_TIMEOUT_MILLIS;
        
        log.info("connect connHash = {}, isXA = {}, maxXaTransactions = {}", 
                connHash, true, maxXaTransactions);
        
        // Apply multinode coordination if needed
        MultinodeXaCoordinator.XaAllocation allocation = 
            calculateMultinodeXaAllocation(details, connHash, maxXaTransactions);
        
        // Delegate to appropriate handler
        if (context.getXaPoolProvider() != null) {
            new HandleXAConnectionWithPoolingAction(context).execute(
                details, connHash, allocation.getCurrentMaxTransactions(), 
                xaStartTimeoutMillis, observer);
        } else {
            log.error("XA Pool Provider not initialized");
            observer.onError(Status.INTERNAL
                    .withDescription("XA Pool Provider not available")
                    .asRuntimeException());
        }
    }
    
    private void handleRegularConnection(ConnectionDetails details, String connHash,
                                        StreamObserver<SessionInfo> observer) {
        // Implementation for regular connection...
        // Delegates to CreateRegularDataSourceAction if needed
    }
    
    private MultinodeXaCoordinator.XaAllocation calculateMultinodeXaAllocation(
            ConnectionDetails details, String connHash, int maxXaTransactions) {
        
        List<String> serverEndpoints = details.getServerEndpointsList();
        if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
            return context.getXaCoordinator().calculateXaLimits(
                connHash, maxXaTransactions, serverEndpoints);
        }
        
        // Single node - no division needed
        return new MultinodeXaCoordinator.XaAllocation(maxXaTransactions, serverEndpoints);
    }
}
```

### Example 3: Value-Returning Internal Action

```java
package org.openjproxy.grpc.server.action.statement;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.StatementRequest;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.ValueAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Internal action for executing SQL updates.
 * Returns OpResult instead of sending to observer (called by ExecuteUpdateAction).
 */
public class ExecuteUpdateInternalAction implements ValueAction<StatementRequest, OpResult> {
    private static final Logger log = LoggerFactory.getLogger(ExecuteUpdateInternalAction.class);
    
    private final ActionContext context;
    
    public ExecuteUpdateInternalAction(ActionContext context) {
        this.context = context;
    }
    
    @Override
    public OpResult execute(StatementRequest request) throws SQLException {
        int updated = 0;
        SessionInfo returnSessionInfo = request.getSession();
        ConnectionSessionDTO dto = ConnectionSessionDTO.builder().build();
        
        Statement stmt = null;
        String psUUID = "";
        OpResult.Builder opResultBuilder = OpResult.newBuilder();
        
        try {
            // Get connection (lazy allocation via SessionConnectionAction)
            dto = new SessionConnectionAction(context).execute(
                request.getSession(), 
                StatementRequestValidator.isAddBatchOperation(request) || 
                StatementRequestValidator.hasAutoGeneratedKeysFlag(request));
            
            returnSessionInfo = dto.getSession();
            
            // Process parameters and execute
            List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());
            
            if (CollectionUtils.isNotEmpty(params)) {
                // Prepared statement execution
                PreparedStatement ps = preparePreparedStatement(dto, request, params);
                if (StatementRequestValidator.isAddBatchOperation(request)) {
                    ps.addBatch();
                    psUUID = registerPreparedStatement(dto, ps, request);
                } else {
                    updated = ps.executeUpdate();
                }
                stmt = ps;
            } else {
                // Regular statement execution
                stmt = StatementFactory.createStatement(
                    context.getSessionManager(), dto.getConnection(), request);
                updated = stmt.executeUpdate(request.getSql());
            }
            
            // Build result
            if (StatementRequestValidator.isAddBatchOperation(request)) {
                return opResultBuilder
                        .setType(ResultType.UUID_STRING)
                        .setSession(returnSessionInfo)
                        .setUuidValue(psUUID)
                        .build();
            } else {
                return opResultBuilder
                        .setType(ResultType.INTEGER)
                        .setSession(returnSessionInfo)
                        .setIntValue(updated)
                        .build();
            }
            
        } finally {
            // Cleanup if no session
            cleanupIfNoSession(dto, stmt);
        }
    }
    
    private PreparedStatement preparePreparedStatement(ConnectionSessionDTO dto, 
                                                      StatementRequest request,
                                                      List<Parameter> params) throws SQLException {
        // Implementation...
    }
    
    private String registerPreparedStatement(ConnectionSessionDTO dto, 
                                            PreparedStatement ps,
                                            StatementRequest request) {
        // Implementation...
    }
    
    private void cleanupIfNoSession(ConnectionSessionDTO dto, Statement stmt) {
        if (dto.getSession() == null || 
            StringUtils.isEmpty(dto.getSession().getSessionUUID())) {
            if (stmt != null) {
                try {
                    stmt.close();
                    stmt.getConnection().close();
                } catch (SQLException e) {
                    log.error("Failure closing resources: {}", e.getMessage(), e);
                }
            }
        }
    }
}
```

## Next Steps

1. Review this design document with the team
2. Get approval on the approach
3. Create skeleton structure (packages, interfaces, ActionContext)
4. Begin Phase 1 implementation: Simple actions
5. Iterate through Phases 2-6 as outlined in the main refactoring document

## References

- Original issue: "Refactor StatementServiceImpl God class"
- Related patterns: Action pattern, Command pattern, Context object pattern
- Design principles: Single Responsibility, Separation of Concerns
