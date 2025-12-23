# OJP JDBC Driver Multinode Connection Evaluation

## Executive Summary

This document provides a detailed evaluation of how the OJP JDBC driver handles connection establishment, load balancing, and failover when multiple OJP servers are configured. The analysis compares the behavior between non-XA (regular JDBC) connections and XA (distributed transaction) connections.

**Key Findings:**
- **Connection Strategy**: Non-XA connects to ALL servers; XA connects to ONE server per connection
- **Load Balancing**: Both use load-aware selection (least connections) by default with round-robin fallback
- **Failover**: Both enforce session stickiness; non-XA has broader failover capability for new connections
- **Session Management**: Different session binding strategies impact failover behavior
- **Redistribution**: Both support connection rebalancing when servers recover, with different mechanisms

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Connection Establishment](#connection-establishment)
3. [Load Balancing](#load-balancing)
4. [Failover Mechanisms](#failover-mechanisms)
5. [Session Management](#session-management)
6. [Connection Redistribution](#connection-redistribution)
7. [Key Differences Summary](#key-differences-summary)
8. [Configuration Reference](#configuration-reference)
9. [Recommendations](#recommendations)

---

## Architecture Overview

### Core Components

The OJP JDBC driver's multinode functionality is built on several key components:

1. **MultinodeConnectionManager**: Central coordinator for server selection, health tracking, and session management
2. **MultinodeStatementService**: Routes requests to appropriate servers based on session affinity
3. **ConnectionTracker**: Tracks active connections and their server bindings for load balancing
4. **HealthCheckValidator**: Monitors server health and triggers recovery
5. **ConnectionRedistributor** (non-XA): Rebalances connections when servers recover
6. **XAConnectionRedistributor** (XA): Specialized rebalancing for XA connections

### URL Format

Multinode configuration is specified in the JDBC URL:

```
# Single node (legacy)
jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb

# Multinode (comma-separated endpoints)
jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost:5432/mydb
```

**Code Reference**: `MultinodeUrlParser.java` (lines 24-251)

---

## Connection Establishment

### Non-XA Connection Flow

**Entry Point**: `Driver.connect()` → `MultinodeConnectionManager.connect()` → `connectToAllServers()`

**Behavior**: Connects to **ALL** healthy servers in the cluster

**Code Location**: `MultinodeConnectionManager.java` (lines 542-664)

```java
private SessionInfo connectToAllServers(ConnectionDetails connectionDetails) {
    SessionInfo primarySessionInfo = null;
    int successfulConnections = 0;
    List<ServerEndpoint> connectedServers = new ArrayList<>();
    
    // Try to connect to all servers
    for (ServerEndpoint server : serverEndpoints) {
        if (!server.isHealthy()) {
            // Skip or attempt recovery
            continue;
        }
        
        try {
            SessionInfo sessionInfo = channelAndStub.blockingStub.connect(connectionDetails);
            
            // Bind session using targetServer from response
            if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
                String targetServer = sessionInfo.getTargetServer();
                if (targetServer != null && !targetServer.isEmpty()) {
                    bindSession(sessionInfo.getSessionUUID(), targetServer);
                }
            }
            
            successfulConnections++;
            connectedServers.add(server);
            
            // Use first successful as primary
            if (primarySessionInfo == null) {
                primarySessionInfo = sessionInfo;
            }
        } catch (StatusRuntimeException e) {
            handleServerFailure(server, e);
        }
    }
    
    // Track which servers received connect()
    connHashToServersMap.put(primarySessionInfo.getConnHash(), connectedServers);
    
    return primarySessionInfo;
}
```

**Why Connect to All Servers?**

1. **Datasource Information Distribution**: Ensures all servers have the datasource configuration
2. **Flexible Routing**: Allows subsequent non-session operations to route to any healthy server
3. **Redundancy**: Even if primary server fails, other servers already have the datasource loaded

**Logging Example**:
```
[INFO] Connecting to server server1:1059
[INFO] Session abc123 bound to target server server1:1059 (matches connected server)
[INFO] Successfully connected to server server1:1059
[INFO] Connecting to server server2:1059
[INFO] Session abc123 bound to target server server2:1059
[INFO] Successfully connected to server server2:1059
[INFO] Connected to 2 out of 3 servers
[INFO] Tracked 2 servers for connection hash xyz789
```

### XA Connection Flow

**Entry Point**: `OjpXADataSource.getXAConnection()` → `OjpXAConnection.getOrCreateSession()` → `MultinodeConnectionManager.connect()` → `connectToSingleServer()`

**Behavior**: Connects to **ONE** server selected via round-robin (or load-aware if equal loads)

**Code Location**: `MultinodeConnectionManager.java` (lines 423-524)

```java
private SessionInfo connectToSingleServer(ConnectionDetails connectionDetails) {
    ServerEndpoint selectedServer = selectHealthyServer();
    
    if (selectedServer == null) {
        throw new SQLException("No healthy servers available for XA connection");
    }
    
    log.info("XA connection: selected server {} via round-robin", selectedServer.getAddress());
    
    try {
        ChannelAndStub channelAndStub = channelMap.get(selectedServer);
        if (channelAndStub == null) {
            channelAndStub = createChannelAndStub(selectedServer);
        }
        
        SessionInfo sessionInfo = withSelectedServer(
            channelAndStub.blockingStub.connect(connectionDetails), 
            selectedServer
        );
        
        selectedServer.setHealthy(true);
        selectedServer.setLastFailureTime(0);
        
        // Bind session to this server
        if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
            String targetServer = sessionInfo.getTargetServer();
            if (targetServer != null && !targetServer.isEmpty()) {
                bindSession(sessionInfo.getSessionUUID(), targetServer);
            } else {
                sessionToServerMap.put(sessionInfo.getSessionUUID(), selectedServer);
            }
        }
        
        // Track single server for this connection hash
        List<ServerEndpoint> connectedServers = new ArrayList<>();
        connectedServers.add(selectedServer);
        connHashToServersMap.put(sessionInfo.getConnHash(), connectedServers);
        
        return sessionInfo;
    } catch (StatusRuntimeException e) {
        handleServerFailure(selectedServer, e);
        throw sqlEx;
    }
}
```

**Why Connect to One Server?**

1. **Proper Load Distribution**: Prevents creating duplicate sessions on all servers
2. **Resource Efficiency**: Avoids creating unnecessary XA resources on all servers
3. **Transaction Isolation**: XA transactions are bound to a single server's backend database connection
4. **Avoids Orphaned Sessions**: Prevents leftover sessions on servers that won't be used

**Logging Example**:
```
[INFO] === connect() called: isXA=true ===
[INFO] ===XA connection: selected server server2:1059 via round-robin (counter=2) ===
[INFO] Connecting to server server2:1059 (XA) with datasource 'mydb'
[INFO] CONNECTION CREATED: XA connection to endpoint=server2:1059, targetServer=server2:1059, sessionUUID=xa456
[INFO] === XA session xa456 bound to target server server2:1059 (matches connected server) ===
[INFO] Successfully connected to server server2:1059 (XA) with datasource 'mydb'
[INFO] Tracked 1 server for XA connection hash xyz123
```

### Connection Differences Table

| Aspect | Non-XA | XA |
|--------|---------|-----|
| **Servers Connected** | ALL healthy servers | ONE server (round-robin selected) |
| **Entry Point** | `Driver.connect()` | `OjpXAConnection.getOrCreateSession()` |
| **Connection Flag** | `isXA=false` (default) | `isXA=true` |
| **Session Creation** | Multiple sessions (one per server) | Single session |
| **Datasource Distribution** | All servers get datasource info | Only selected server gets datasource info |
| **Connection Hash Tracking** | List of all connected servers | Single server in list |
| **Lazy Initialization** | No (connects immediately) | Yes (session created when first needed) |

---

## Load Balancing

Both non-XA and XA connections use the **same load balancing strategy** but apply it at different points.

### Load Balancing Strategy

**Default**: Load-aware selection (selects server with fewest active connections)
**Fallback**: Round-robin (when all servers have equal load)

**Code Location**: `MultinodeConnectionManager.java` (lines 745-848)

```java
private ServerEndpoint selectHealthyServer() {
    List<ServerEndpoint> healthyServers = serverEndpoints.stream()
            .filter(ServerEndpoint::isHealthy)
            .collect(Collectors.toList());
    
    if (healthyServers.isEmpty()) {
        attemptServerRecovery();
        // Re-check after recovery
        healthyServers = serverEndpoints.stream()
                .filter(ServerEndpoint::isHealthy)
                .collect(Collectors.toList());
    }
    
    if (healthyServers.isEmpty()) {
        return null;
    }
    
    // Choose strategy based on configuration
    if (healthCheckConfig.isLoadAwareSelectionEnabled()) {
        return selectByLeastConnections(healthyServers);
    } else {
        return selectByRoundRobin(healthyServers);
    }
}

private ServerEndpoint selectByLeastConnections(List<ServerEndpoint> healthyServers) {
    Map<ServerEndpoint, Integer> connectionCounts = connectionTracker.getCounts();
    
    // Check if all servers have equal load
    boolean allEqual = true;
    Integer firstCount = null;
    for (ServerEndpoint server : healthyServers) {
        int count = connectionCounts.getOrDefault(server, 0);
        if (firstCount == null) {
            firstCount = count;
        } else if (firstCount != count) {
            allEqual = false;
            break;
        }
    }
    
    // If all equal, use round-robin for fairness
    if (allEqual) {
        return selectByRoundRobin(healthyServers);
    }
    
    // Find server with minimum connections
    ServerEndpoint selected = healthyServers.stream()
            .min((s1, s2) -> {
                int count1 = connectionCounts.getOrDefault(s1, 0);
                int count2 = connectionCounts.getOrDefault(s2, 0);
                return Integer.compare(count1, count2);
            })
            .orElse(healthyServers.get(0));
    
    return selected;
}

private ServerEndpoint selectByRoundRobin(List<ServerEndpoint> healthyServers) {
    int index = Math.abs(roundRobinCounter.getAndIncrement()) % healthyServers.size();
    return healthyServers.get(index);
}
```

### Load Balancing for Non-XA Connections

**Application Point**: Initial connection establishment and session-less operations

**Behavior**:
1. When creating a new connection, driver connects to ALL servers
2. For session-bound operations, requests route to the bound server (no load balancing)
3. For non-session operations, load-aware selection chooses the least-loaded server

**Connection Tracking**: 
- Non-XA connections are NOT automatically tracked by `ConnectionTracker`
- Load-aware selection may fall back to round-robin if tracker is empty
- Manual tracking can be added via connection pool integration

**Example Scenario** (3 servers, 6 connections):
```
Initial state:
Server1: 0 connections
Server2: 0 connections  
Server3: 0 connections

After 6 non-XA connections created:
Server1: 6 connections (all servers got datasource info)
Server2: 6 connections (all servers got datasource info)
Server3: 6 connections (all servers got datasource info)

Session distribution (example):
Server1: 2 sessions bound (abc, def)
Server2: 2 sessions bound (ghi, jkl)
Server3: 2 sessions bound (mno, pqr)
```

### Load Balancing for XA Connections

**Application Point**: XA connection establishment

**Behavior**:
1. When creating an XA connection, driver selects ONE server via load-aware/round-robin
2. All operations for that XA connection route to the selected server (session stickiness)
3. `ConnectionTracker` tracks XA connections for accurate load distribution

**Connection Tracking**:
- XA connections are registered with `ConnectionTracker` when `getConnection()` is called
- Tracker maintains mapping: `Connection → ServerEndpoint`
- Load-aware selection uses tracker data for accurate load balancing

**Code Location**: `OjpXAConnection.java` (lines 186-199)

```java
// Register with ConnectionTracker if using multinode
if (statementService instanceof MultinodeStatementService) {
    MultinodeStatementService multinodeService = (MultinodeStatementService) statementService;
    MultinodeConnectionManager connectionManager = multinodeService.getConnectionManager();
    if (connectionManager != null && boundServerAddress != null) {
        ServerEndpoint boundEndpoint = findServerEndpoint(connectionManager, boundServerAddress);
        if (boundEndpoint != null) {
            connectionManager.getConnectionTracker().register(logicalConnection, boundEndpoint);
            log.debug("Registered connection with tracker for server: {}", boundServerAddress);
        }
    }
}
```

**Example Scenario** (3 servers, 6 XA connections with load-aware):
```
Initial state:
Server1: 0 XA connections
Server2: 0 XA connections  
Server3: 0 XA connections

After 6 XA connections created:
Conn1 → Server1 (selected: least loaded, all equal → round-robin index 0)
Conn2 → Server2 (selected: least loaded, all equal → round-robin index 1)
Conn3 → Server3 (selected: least loaded, all equal → round-robin index 2)
Conn4 → Server1 (selected: least loaded, all equal → round-robin index 3 % 3 = 0)
Conn5 → Server2 (selected: least loaded, all equal → round-robin index 4 % 3 = 1)
Conn6 → Server3 (selected: least loaded, all equal → round-robin index 5 % 3 = 2)

Final state:
Server1: 2 XA connections (Conn1, Conn4)
Server2: 2 XA connections (Conn2, Conn5)
Server3: 2 XA connections (Conn3, Conn6)
```

### Load Balancing Configuration

**Property**: `ojp.loadaware.selection.enabled`
**Default**: `true`
**Location**: `ojp.properties`

```properties
# Enable load-aware server selection (default: true)
ojp.loadaware.selection.enabled=true

# When true: Selects server with fewest active connections
# When false: Uses legacy round-robin distribution
```

**Code Location**: `CommonConstants.java` and `HealthCheckConfig.java`

### Load Balancing Differences Table

| Aspect | Non-XA | XA |
|--------|---------|-----|
| **Selection Timing** | During connect to all servers | Before selecting which ONE server to connect to |
| **Connection Tracking** | Not automatic (tracker may be empty) | Automatic via ConnectionTracker |
| **Load Metric Source** | ConnectionTracker (may be empty for non-XA) | ConnectionTracker (always populated for XA) |
| **Fallback Behavior** | Round-robin when tracker empty | Round-robin when loads equal |
| **Distribution Pattern** | All servers initially, sessions vary | Direct server selection per connection |

---

## Failover Mechanisms

Both non-XA and XA connections enforce **session stickiness** but differ in their failover capabilities.

### Session Stickiness Principle

Once a session is created and bound to a server, all operations for that session **MUST** route to the same server. This ensures:
- ACID transaction guarantees
- Consistent connection state
- Proper resource cleanup

**Code Location**: `MultinodeConnectionManager.java` (lines 667-726)

```java
public ServerEndpoint affinityServer(String sessionKey) throws SQLException {
    if (sessionKey == null || sessionKey.isEmpty()) {
        // No session identifier, use round-robin
        return selectHealthyServer();
    }
    
    ServerEndpoint sessionServer = sessionToServerMap.get(sessionKey);
    
    // Session must be bound - throw exception if not found
    if (sessionServer == null) {
        throw new SQLException("Session " + sessionKey + 
                " has no associated server. Session may have expired or server may be unavailable.");
    }
    
    if (!sessionServer.isHealthy()) {
        // Remove from map and throw exception - do NOT fall back to round-robin
        sessionToServerMap.remove(sessionKey);
        throw new SQLException("Session " + sessionKey + 
                " is bound to server " + sessionServer.getAddress() + 
                " which is currently unavailable. Cannot continue with this session.");
    }
    
    return sessionServer;
}
```

### Non-XA Failover Behavior

**Connection-Level Failover**: ✅ YES (for new connections)
**Session-Level Failover**: ❌ NO (session stickiness enforced)

**Scenario 1: New Connection with Server Failure**
```
Setup: 3 servers [A, B, C], Server B is unhealthy

Client creates new connection:
1. Driver tries to connect to Server A → Success (session created)
2. Driver tries to connect to Server B → Skipped (unhealthy)
3. Driver tries to connect to Server C → Success (session created)

Result: Connection succeeds with sessions on A and C
```

**Scenario 2: Existing Session with Server Failure**
```
Setup: Active connection with session bound to Server B

Server B becomes unhealthy:
1. Client attempts operation on session
2. affinityServer() detects Server B is unhealthy
3. Throws SQLException: "Session is bound to server B which is currently unavailable"

Result: Operation fails, application must create new connection
```

**Code Example**:
```java
// Non-XA connection attempt
try {
    Connection conn = DriverManager.getConnection(
        "jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost:5432/mydb",
        "user", "password"
    );
    // If some servers are down, connection still succeeds if at least one is healthy
} catch (SQLException e) {
    // Only fails if ALL servers are unhealthy
}

// Session-bound operation
try {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT * FROM users");
    // Routes to bound server - fails if that server is unhealthy
} catch (SQLException e) {
    // Session server unavailable - must create new connection
}
```

### XA Failover Behavior

**Connection-Level Failover**: ✅ YES (can retry with different server)
**Session-Level Failover**: ❌ NO (session stickiness enforced)
**Proactive Connection Invalidation**: ✅ YES (on server failure detection)

**Scenario 1: New XA Connection with Server Failure**
```
Setup: 3 servers [A, B, C], Server B is unhealthy

Client requests XA connection:
1. Round-robin selects Server A
2. Server A is healthy → Connection succeeds
3. Session created on Server A only

Next XA connection request:
1. Round-robin would select Server B
2. Server B is unhealthy → Skipped
3. Round-robin tries next healthy server (C)
4. Connection succeeds on Server C
```

**Scenario 2: Existing XA Session with Server Failure**
```
Setup: Active XA connection with session bound to Server B

Server B becomes unhealthy (detected by health check):
1. Health check marks Server B as unhealthy
2. MultinodeConnectionManager invalidates ALL sessions bound to Server B
3. Connections marked as forceInvalid
4. On next XA transaction attempt:
   - If connection is from pool: Pool validation fails, connection removed
   - If connection is direct: Operation throws SQLException

Result: Connection invalidated, pool creates new connection to healthy server
```

**Proactive Invalidation Code**: `MultinodeConnectionManager.java` (lines 309-363)

```java
private void invalidateSessionsAndConnectionsForFailedServer(ServerEndpoint endpoint) {
    log.info("Invalidating all XA sessions and connections for failed server {}", endpoint.getAddress());
    
    // Step 1: Remove all session bindings for this server
    List<String> sessionsToInvalidate = sessionToServerMap.entrySet().stream()
            .filter(entry -> entry.getValue().equals(endpoint))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    
    for (String sessionUUID : sessionsToInvalidate) {
        sessionToServerMap.remove(sessionUUID);
    }
    
    // Step 2: Mark all connections for this server as invalid and close them
    Map<ServerEndpoint, List<java.sql.Connection>> distribution = connectionTracker.getDistribution();
    List<java.sql.Connection> connectionsToInvalidate = distribution.get(endpoint);
    
    if (connectionsToInvalidate != null && !connectionsToInvalidate.isEmpty()) {
        for (java.sql.Connection conn : connectionsToInvalidate) {
            if (conn instanceof org.openjproxy.jdbc.Connection) {
                org.openjproxy.jdbc.Connection ojpConn = (org.openjproxy.jdbc.Connection) conn;
                ojpConn.markForceInvalid();
                try {
                    conn.close();
                } catch (Exception e) {
                    log.warn("Failed to close connection: {}", e.getMessage());
                }
            }
        }
    }
}
```

**XA Connection Listener**: `OjpXAConnection.java` (lines 314-328)

```java
@Override
public void onServerUnhealthy(ServerEndpoint endpoint, Exception exception) {
    String serverAddr = endpoint.getHost() + ":" + endpoint.getPort();
    
    // Check if this connection is bound to the failed server
    if (boundServerAddress != null && boundServerAddress.equals(serverAddr)) {
        log.warn("XA connection bound to unhealthy server {}, closing connection proactively", serverAddr);
        try {
            close(); // Atomikos will remove from pool and create new one
        } catch (SQLException e) {
            log.error("Error closing XA connection after server failure: {}", e.getMessage(), e);
        }
    }
}
```

### Health Check and Recovery

**Health Check Trigger**: Time-based, non-blocking
**Check Interval**: Configurable (default: based on health check config)
**Recovery Attempt**: For unhealthy servers only

**Code Location**: `MultinodeConnectionManager.java` (lines 170-272)

```java
private void tryTriggerHealthCheck() {
    long now = System.currentTimeMillis();
    long lastCheck = lastHealthCheckTimestamp.get();
    long elapsed = now - lastCheck;
    
    // Only check if interval has passed
    if (elapsed >= healthCheckConfig.getHealthCheckIntervalMs()) {
        // Atomic update - only one thread succeeds
        if (lastHealthCheckTimestamp.compareAndSet(lastCheck, now)) {
            try {
                performHealthCheck();
            } catch (Exception e) {
                log.warn("Health check failed: {}", e.getMessage());
            }
        }
    }
}

private void performHealthCheck() {
    // XA Mode: Proactively check healthy servers to detect failures early
    if (xaConnectionRedistributor != null) {
        List<ServerEndpoint> healthyServers = serverEndpoints.stream()
                .filter(ServerEndpoint::isHealthy)
                .collect(Collectors.toList());
        
        for (ServerEndpoint endpoint : healthyServers) {
            if (!validateServer(endpoint)) {
                endpoint.setHealthy(false);
                endpoint.setLastFailureTime(System.currentTimeMillis());
                
                // XA Mode: Immediately invalidate sessions and connections
                invalidateSessionsAndConnectionsForFailedServer(endpoint);
                
                notifyServerUnhealthy(endpoint, new Exception("Health check failed"));
            }
        }
    }
    
    // Check unhealthy servers to see if they've recovered
    List<ServerEndpoint> unhealthyServers = serverEndpoints.stream()
            .filter(endpoint -> !endpoint.isHealthy())
            .collect(Collectors.toList());
    
    for (ServerEndpoint endpoint : unhealthyServers) {
        long timeSinceFailure = System.currentTimeMillis() - endpoint.getLastFailureTime();
        
        if (timeSinceFailure >= healthCheckConfig.getHealthCheckThresholdMs()) {
            if (validateServer(endpoint)) {
                endpoint.markHealthy();
                recoveredServers.add(endpoint);
                notifyServerRecovered(endpoint);
            }
        }
    }
}
```

### Connection-Level vs Database-Level Errors

**Important Distinction**: Only connection-level errors mark servers as unhealthy

**Connection-Level Errors** (mark server unhealthy):
- `UNAVAILABLE`: Server not reachable
- `DEADLINE_EXCEEDED`: Request timeout
- `CANCELLED`: Connection cancelled
- `UNKNOWN`: Connection-related errors

**Database-Level Errors** (do NOT mark server unhealthy):
- SQL syntax errors
- Table not found
- Permission denied
- Constraint violations

**Code Location**: `MultinodeConnectionManager.java` (lines 896-931)

```java
public boolean isConnectionLevelError(Exception exception) {
    if (exception instanceof io.grpc.StatusRuntimeException) {
        io.grpc.StatusRuntimeException statusException = (io.grpc.StatusRuntimeException) exception;
        io.grpc.Status.Code code = statusException.getStatus().getCode();
        
        // Only these status codes indicate connection-level failures
        return code == io.grpc.Status.Code.UNAVAILABLE ||
               code == io.grpc.Status.Code.DEADLINE_EXCEEDED ||
               code == io.grpc.Status.Code.CANCELLED ||
               (code == io.grpc.Status.Code.UNKNOWN && 
                statusException.getMessage() != null && 
                (statusException.getMessage().contains("connection") || 
                 statusException.getMessage().contains("Connection")));
    }
    
    // For non-gRPC exceptions, check for connection-related keywords
    String message = exception.getMessage();
    if (message != null) {
        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains("connection") || 
               lowerMessage.contains("timeout") ||
               lowerMessage.contains("unavailable");
    }
    
    return false; // Default to not marking unhealthy for unknown errors
}
```

### Failover Differences Table

| Aspect | Non-XA | XA |
|--------|---------|-----|
| **New Connection Failover** | ✅ Connects to ALL healthy servers | ✅ Retries with next healthy server |
| **Session-Level Failover** | ❌ NO (throws SQLException) | ❌ NO (throws SQLException) |
| **Proactive Invalidation** | ❌ NO (waits for operation attempt) | ✅ YES (health check invalidates) |
| **Connection Tracking** | Not automatic | Automatic via ConnectionTracker |
| **Pool Integration** | Requires manual pool validation | Natural pool validation via isValid() |
| **Health Check Impact** | Marks servers unhealthy only | Marks unhealthy + invalidates connections |
| **Recovery Behavior** | Gradual (as new connections created) | Immediate (invalidation + redistribution) |

---

## Session Management

### Session Binding

Sessions are bound to servers to ensure consistency and proper resource management.

**Session Mapping**: `Map<String, ServerEndpoint> sessionToServerMap`

**Binding Process**:

1. **Connection Establishment**: Server returns `SessionInfo` with `sessionUUID` and `targetServer`
2. **Binding**: Client maps `sessionUUID → targetServer`
3. **Routing**: Subsequent operations use `sessionUUID` to route to correct server

**Code Location**: `MultinodeConnectionManager.java` (lines 987-1023)

```java
public void bindSession(String sessionUUID, String targetServer) {
    if (sessionUUID == null || sessionUUID.isEmpty()) {
        log.warn("Attempted to bind session with null or empty sessionUUID");
        return;
    }
    
    if (targetServer == null || targetServer.isEmpty()) {
        log.warn("Attempted to bind session {} with null or empty targetServer", sessionUUID);
        return;
    }
    
    // Find the matching ServerEndpoint for this targetServer string
    ServerEndpoint matchingEndpoint = null;
    for (ServerEndpoint endpoint : serverEndpoints) {
        String endpointAddress = endpoint.getHost() + ":" + endpoint.getPort();
        if (endpointAddress.equals(targetServer)) {
            matchingEndpoint = endpoint;
            break;
        }
    }
    
    if (matchingEndpoint != null) {
        ServerEndpoint previous = sessionToServerMap.put(sessionUUID, matchingEndpoint);
        if (previous == null) {
            log.info("Bound session {} to target server {}", sessionUUID, targetServer);
        } else {
            log.info("Rebound session {} from {} to target server {}", 
                    sessionUUID, previous.getAddress(), targetServer);
        }
    } else {
        log.warn("Could not find matching endpoint for targetServer: {}", targetServer);
    }
}
```

### Non-XA Session Management

**Sessions Per Connection**: Multiple (one per server)
**Session Binding**: Per-server sessions bound independently
**Session Lifecycle**: Independent per server

**Flow**:
```
Client creates connection:
├─ Connect to Server1 → Session S1 bound to Server1
├─ Connect to Server2 → Session S2 bound to Server2
└─ Connect to Server3 → Session S3 bound to Server3

Client executes query on connection:
├─ Needs session affinity
├─ Routes to primary session (S1 on Server1)
└─ All subsequent operations use S1

Client closes connection:
├─ Terminate S1 on Server1
├─ Terminate S2 on Server2
└─ Terminate S3 on Server3
```

**Connection Hash Tracking**: `Map<String, List<ServerEndpoint>> connHashToServersMap`

This tracks which servers received `connect()` for each connection, ensuring proper cleanup during `terminateSession()`.

**Code Location**: `MultinodeConnectionManager.java` (lines 959-977)

```java
public void terminateSession(SessionInfo sessionInfo) {
    if (sessionInfo != null) {
        // Remove session binding if sessionUUID is present
        if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
            unbindSession(sessionInfo.getSessionUUID());
        }
        
        // Remove connection hash mapping if present
        if (sessionInfo.getConnHash() != null && !sessionInfo.getConnHash().isEmpty()) {
            connHashToServersMap.remove(sessionInfo.getConnHash());
        }
    }
}

public List<ServerEndpoint> getServersForConnHash(String connHash) {
    if (connHash == null || connHash.isEmpty()) {
        return null;
    }
    List<ServerEndpoint> servers = connHashToServersMap.get(connHash);
    return servers != null ? new ArrayList<>(servers) : null;
}
```

### XA Session Management

**Sessions Per Connection**: One (on selected server only)
**Session Binding**: Single session bound to one server
**Session Lifecycle**: Created lazily, bound permanently

**Flow**:
```
Client requests XA connection:
├─ Round-robin selects Server2
├─ Connect to Server2 only
└─ Session X1 bound to Server2

Client gets XAResource:
├─ Uses existing session X1
└─ All XA operations route to Server2

Client gets logical connection:
├─ Creates OjpXALogicalConnection
├─ Uses same session X1
├─ Register with ConnectionTracker
└─ All operations route to Server2

Client closes XA connection:
├─ Unregister from ConnectionTracker
├─ Terminate X1 on Server2 only
└─ No other servers to clean up
```

**Lazy Session Creation**: `OjpXAConnection.java` (lines 74-126)

```java
private SessionInfo getOrCreateSession() throws SQLException {
    if (sessionInfo != null) {
        return sessionInfo;
    }
    sessionLock.lock();
    try {
        if (sessionInfo != null) {
            return sessionInfo;
        }
        // Connect to server with XA flag enabled
        ConnectionDetails.Builder connBuilder = ConnectionDetails.newBuilder()
                .setUrl(url)
                .setUser(user != null ? user : "")
                .setPassword(password != null ? password : "")
                .setClientUUID(ClientUUID.getUUID())
                .setIsXA(true);  // Mark this as an XA connection

        // Add server endpoints for multinode coordination
        if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
            connBuilder.addAllServerEndpoints(serverEndpoints);
        }

        if (properties != null && !properties.isEmpty()) {
            Map<String, Object> propertiesMap = new HashMap<>();
            for (String key : properties.stringPropertyNames()) {
                propertiesMap.put(key, properties.getProperty(key));
            }
            connBuilder.addAllProperties(ProtoConverter.propertiesToProto(propertiesMap));
        }

        this.sessionInfo = statementService.connect(connBuilder.build());
        
        // Track the bound server
        if (sessionInfo.getTargetServer() != null && !sessionInfo.getTargetServer().isEmpty()) {
            this.boundServerAddress = sessionInfo.getTargetServer();
        }
        
        return sessionInfo;
    } finally {
        sessionLock.unlock();
    }
}
```

### Session Management Differences Table

| Aspect | Non-XA | XA |
|--------|---------|-----|
| **Sessions Created** | Multiple (one per server) | Single (on selected server) |
| **Creation Timing** | Eager (during connect) | Lazy (when first needed) |
| **Session Binding** | Multiple bindings | Single binding |
| **Connection Hash Tracking** | List of all servers | Single server |
| **Cleanup Scope** | All servers that got connect() | Single bound server |
| **Resource Usage** | Higher (multiple sessions) | Lower (single session) |
| **Server Coordination** | Required for cleanup | Simpler (single server) |

---

## Connection Redistribution

When a failed server recovers, connections may need to be redistributed to rebalance load across all healthy servers.

### Non-XA Redistribution

**Mechanism**: `ConnectionRedistributor`
**Trigger**: Server recovery detected by health check
**Strategy**: Mark connections for forced invalidation

**Code Location**: `ConnectionRedistributor.java` (lines 34-163)

```java
public void rebalance(List<ServerEndpoint> recoveredServers, List<ServerEndpoint> allHealthyServers) {
    if (!config.isRedistributionEnabled()) {
        return;
    }
    
    // Get current distribution
    Map<ServerEndpoint, List<Connection>> distribution = connectionTracker.getDistribution();
    int totalConnections = connectionTracker.getTotalConnections();
    
    // Calculate target per server
    int targetPerServer = totalConnections / allHealthyServers.size();
    
    // For each recovered server, calculate needed connections
    int totalToClose = 0;
    for (ServerEndpoint recovered : recoveredServers) {
        int currentCount = distribution.getOrDefault(recovered, new ArrayList<>()).size();
        int needed = targetPerServer - currentCount;
        if (needed > 0) {
            totalToClose += needed;
        }
    }
    
    // Find overloaded servers
    List<ServerEndpoint> overloadedServers = distribution.entrySet().stream()
            .filter(entry -> !recoveredServers.contains(entry.getKey()))
            .filter(entry -> entry.getValue().size() > targetPerServer)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    
    // Perform balanced closure: alternate between overloaded servers
    int closedCount = 0;
    int serverIndex = 0;
    
    while (closedCount < totalToClose) {
        ServerEndpoint server = overloadedServers.get(serverIndex % overloadedServers.size());
        List<Connection> connections = distribution.get(server);
        
        for (Connection conn : connections) {
            if (conn instanceof org.openjproxy.jdbc.Connection) {
                org.openjproxy.jdbc.Connection ojpConn = (org.openjproxy.jdbc.Connection) conn;
                if (!ojpConn.isClosed() && !ojpConn.isForceInvalid()) {
                    ojpConn.markForceInvalid();
                    closedCount++;
                    connections.remove(conn);
                    break;
                }
            }
        }
        serverIndex++;
    }
    
    log.info("Redistribution complete: marked {} connections for closure", closedCount);
}
```

**How It Works**:

1. **Calculate Target**: `totalConnections / healthyServerCount`
2. **Identify Overloaded**: Servers with `connections > target`
3. **Mark for Closure**: Call `markForceInvalid()` on connections from overloaded servers
4. **Balanced Approach**: Alternate between overloaded servers for fairness
5. **Pool Handles Cleanup**: Connection pools detect invalid connections via `isValid()` and replace them

**Connection Invalidation**: `Connection.java` (lines 66-96)

```java
public void markForceInvalid() {
    this.forceInvalid = true;
    log.debug("Connection marked for forced invalidation");
}

public boolean isForceInvalid() {
    return this.forceInvalid;
}

private void checkValid() throws SQLException {
    if (closed) {
        throw new SQLException("Connection is closed");
    }
    if (forceInvalid) {
        throw new SQLNonTransientConnectionException(
            "Connection has been marked invalid for redistribution",
            "08006"  // SQLState: connection failure
        );
    }
}

@Override
public boolean isValid(int timeout) throws SQLException {
    if (closed || forceInvalid) {
        return false;
    }
    // Additional validation logic...
    return true;
}
```

### XA Redistribution

**Mechanism**: `XAConnectionRedistributor`
**Trigger**: Server recovery detected by health check
**Strategy**: Mark idle XA connections for invalidation, respecting transaction boundaries

**Code Location**: `XAConnectionRedistributor.java` (lines 37-123)

```java
public void rebalance(List<ServerEndpoint> recoveredServers, List<ServerEndpoint> allHealthyServers) {
    ConnectionTracker tracker = connectionManager.getConnectionTracker();
    
    // Get current XA connection distribution
    List<ConnectionTracker.ConnectionInfo> allConnections = tracker.getAllXAConnections();
    
    if (allConnections.isEmpty()) {
        log.info("No XA connections to redistribute");
        return;
    }
    
    // Calculate target per server
    int totalConnections = allConnections.size();
    int targetPerServer = totalConnections / allHealthyServers.size();
    
    // Group connections by server
    Map<String, List<ConnectionTracker.ConnectionInfo>> connectionsByServer = 
            allConnections.stream()
                    .collect(Collectors.groupingBy(ConnectionTracker.ConnectionInfo::getBoundServerAddress));
    
    // Identify overloaded servers
    double idleRebalanceFraction = healthConfig.getIdleRebalanceFraction();
    int maxMarkPerRecovery = healthConfig.getMaxClosePerRecovery();
    int totalMarked = 0;
    
    List<String> overloadedServers = connectionsByServer.entrySet().stream()
            .filter(entry -> entry.getValue().size() > targetPerServer)
            .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    
    for (String serverAddress : overloadedServers) {
        if (totalMarked >= maxMarkPerRecovery) {
            break;
        }
        
        List<ConnectionTracker.ConnectionInfo> serverConnections = connectionsByServer.get(serverAddress);
        int excessConnections = serverConnections.size() - targetPerServer;
        int toMark = (int) Math.ceil(excessConnections * idleRebalanceFraction);
        toMark = Math.min(toMark, maxMarkPerRecovery - totalMarked);
        
        if (toMark <= 0) {
            continue;
        }
        
        // Sort by last used time (oldest first) and mark idle connections
        List<ConnectionTracker.ConnectionInfo> idleConnections = serverConnections.stream()
                .sorted(Comparator.comparingLong(ConnectionTracker.ConnectionInfo::getLastUsedTime))
                .limit(toMark)
                .collect(Collectors.toList());
        
        for (ConnectionTracker.ConnectionInfo connInfo : idleConnections) {
            tracker.markConnectionInvalid(connInfo.getConnectionUUID());
            totalMarked++;
        }
    }
    
    log.info("XA redistribution complete: marked {} connections as invalid", totalMarked);
}
```

**Key Differences from Non-XA**:

1. **Transaction Awareness**: Only marks idle connections (based on `lastUsedTime`)
2. **Gradual Rebalancing**: Uses `idleRebalanceFraction` to limit impact
3. **Max Limit**: Respects `maxClosePerRecovery` to avoid disruption
4. **Connection Tracking**: Uses `ConnectionTracker.ConnectionInfo` with metadata

### Redistribution Configuration

**Properties**:

```properties
# Enable connection redistribution on server recovery
ojp.redistribution.enabled=true

# Health check interval (time between health checks)
ojp.healthcheck.interval.ms=30000  # 30 seconds

# Health check threshold (time before retrying failed server)
ojp.healthcheck.threshold.ms=60000  # 60 seconds

# XA-specific: Fraction of excess connections to rebalance
ojp.xa.idle.rebalance.fraction=0.5  # 50% of excess

# XA-specific: Maximum connections to close per recovery event
ojp.xa.max.close.per.recovery=10
```

**Code Location**: `HealthCheckConfig.java`

### Redistribution Flow Comparison

**Non-XA Redistribution Flow**:
```
1. Server recovers
2. Health check detects recovery
3. ConnectionRedistributor.rebalance() called
4. Calculate: target = totalConnections / healthyServers
5. Identify overloaded servers (connections > target)
6. Mark connections from overloaded servers as invalid
7. Connection pool's next validation detects invalid connections
8. Pool closes invalid connections
9. Pool creates new connections
10. New connections distributed via load-aware selection
11. Recovered server receives new connections
```

**XA Redistribution Flow**:
```
1. Server recovers
2. Health check detects recovery
3. XAConnectionRedistributor.rebalance() called
4. Calculate: target = totalXAConnections / healthyServers
5. Identify overloaded servers (connections > target)
6. Calculate toMark = excessConnections * idleRebalanceFraction
7. Sort connections by lastUsedTime (oldest first)
8. Mark ONLY idle connections as invalid (up to toMark or maxClosePerRecovery)
9. Connection pool's next validation detects invalid XA connections
10. Pool closes invalid XA connections (triggers session termination)
11. Pool creates new XA connections
12. New XA connections distributed via round-robin/load-aware
13. Recovered server receives new XA connections
```

### Redistribution Differences Table

| Aspect | Non-XA | XA |
|--------|---------|-----|
| **Redistributor Class** | ConnectionRedistributor | XAConnectionRedistributor |
| **Trigger Point** | Server recovery | Server recovery |
| **Target Calculation** | Equal distribution | Equal distribution |
| **Connection Selection** | Any connection | Idle connections only |
| **Selection Criteria** | None (first available) | Sort by lastUsedTime |
| **Rebalance Fraction** | 100% (all excess) | Configurable (default 50%) |
| **Max Per Recovery** | No limit | Configurable (default 10) |
| **Transaction Safety** | No special handling | Respects active transactions |
| **Gradual Rebalancing** | Immediate (all at once) | Progressive (over multiple checks) |

---

## Key Differences Summary

### Connection Establishment Differences

| Aspect | Non-XA | XA |
|--------|---------|-----|
| Servers Connected | ALL healthy | ONE (round-robin) |
| Sessions Created | Multiple | Single |
| Datasource Distribution | All servers | Selected server only |
| Resource Usage | Higher | Lower |
| Connection Flag | `isXA=false` | `isXA=true` |

### Load Balancing Differences

| Aspect | Non-XA | XA |
|--------|---------|-----|
| Strategy | Load-aware + Round-robin | Load-aware + Round-robin |
| Application Point | Session-less operations | Connection establishment |
| Connection Tracking | Not automatic | Automatic |
| Fallback Condition | Tracker empty | Loads equal |

### Failover Differences

| Aspect | Non-XA | XA |
|--------|---------|-----|
| New Connection Failover | ✅ Multi-server | ✅ Single-server retry |
| Session Failover | ❌ Session stickiness | ❌ Session stickiness |
| Proactive Invalidation | ❌ On operation attempt | ✅ On health check |
| Pool Integration | Manual validation | Natural validation |

### Redistribution Differences

| Aspect | Non-XA | XA |
|--------|---------|-----|
| Strategy | Mark all excess | Mark idle only |
| Transaction Safety | No special handling | Respects transactions |
| Rebalance Speed | Immediate | Gradual |
| Configuration | Basic | Advanced (fraction, max) |

---

## Configuration Reference

### ojp.properties Configuration

```properties
# ============================================================
# Multinode Configuration
# ============================================================

# Retry attempts for multinode operations (-1 for infinite)
ojp.multinode.retryAttempts=-1

# Retry delay in milliseconds between attempts
ojp.multinode.retryDelayMs=5000

# ============================================================
# Load Balancing Configuration
# ============================================================

# Enable load-aware server selection (default: true)
# When enabled: Selects server with fewest active connections
# When false: Uses legacy round-robin distribution
ojp.loadaware.selection.enabled=true

# ============================================================
# Health Check Configuration
# ============================================================

# Enable connection redistribution on server recovery
ojp.redistribution.enabled=true

# Health check interval - time between checks (milliseconds)
ojp.healthcheck.interval.ms=30000

# Health check threshold - time before retrying failed server (milliseconds)
ojp.healthcheck.threshold.ms=60000

# ============================================================
# XA Configuration
# ============================================================

# Maximum concurrent XA transactions (divided among servers)
ojp.xa.maxTransactions=50

# XA transaction start timeout (milliseconds)
ojp.xa.startTimeout=30000

# Fraction of excess connections to rebalance (0.0 - 1.0)
ojp.xa.idle.rebalance.fraction=0.5

# Maximum connections to close per recovery event
ojp.xa.max.close.per.recovery=10

# ============================================================
# Connection Pool Configuration (per server)
# ============================================================

# Maximum pool size (divided among servers in multinode)
ojp.connection.pool.maximumPoolSize=25

# Minimum idle connections
ojp.connection.pool.minimumIdle=5

# Idle timeout (milliseconds)
ojp.connection.pool.idleTimeout=300000

# Maximum lifetime (milliseconds)
ojp.connection.pool.maxLifetime=900000

# Connection timeout (milliseconds)
ojp.connection.pool.connectionTimeout=15000
```

### JDBC URL Examples

```java
// Single node
String url = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb";

// Multinode (2 servers)
String url = "jdbc:ojp[server1:1059,server2:1059]_postgresql://localhost:5432/mydb";

// Multinode (3 servers, recommended for production)
String url = "jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost:5432/mydb";

// Mixed ports
String url = "jdbc:ojp[proxy1:1059,proxy2:1060,proxy3:1061]_mysql://localhost:3306/mydb";
```

### Non-XA Connection Example

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;

public class NonXAExample {
    public void demonstrateNonXA() throws Exception {
        String url = "jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost:5432/mydb";
        
        // Get connection - connects to ALL servers
        try (Connection conn = DriverManager.getConnection(url, "user", "password")) {
            System.out.println("Connected to OJP cluster");
            // Sessions created on all servers
            // Primary session selected for operations
            
            // Execute query - uses primary session
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT * FROM users");
                while (rs.next()) {
                    System.out.println("User: " + rs.getString("name"));
                }
            }
            
            // Connection cleanup - terminates sessions on all servers
        }
    }
}
```

### XA Connection Example

```java
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;
import org.openjproxy.jdbc.xa.OjpXADataSource;

public class XAExample {
    public void demonstrateXA() throws Exception {
        // Configure XA datasource
        OjpXADataSource xaDataSource = new OjpXADataSource();
        xaDataSource.setUrl("jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost:5432/mydb");
        xaDataSource.setUser("user");
        xaDataSource.setPassword("password");
        
        // Get XA connection - connects to ONE server (round-robin selected)
        XAConnection xaConn = xaDataSource.getXAConnection();
        System.out.println("XA connection created (bound to single server)");
        
        // Get XA resource (triggers session creation if not already created)
        XAResource xaResource = xaConn.getXAResource();
        
        // Get logical connection
        Connection conn = xaConn.getConnection();
        
        // All operations use the single bound session
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO users (name) VALUES ('Alice')");
        }
        
        // Close - terminates session on single bound server
        conn.close();
        xaConn.close();
    }
}
```

---

## Recommendations

### For Non-XA Connections

1. **Use Multinode for High Availability**
   - Deploy at least 3 OJP servers for production
   - Configure identical pool sizes on all servers
   - Enable load-aware selection for better distribution

2. **Configure Connection Pools Appropriately**
   - Set `maximumPoolSize` based on total capacity needs
   - Servers will automatically divide pool sizes
   - Example: With 3 servers and `maximumPoolSize=30`, each server handles ~10 connections

3. **Handle Session Failures Gracefully**
   - Catch `SQLException` for session-bound operations
   - Implement retry logic to create new connections
   - Use connection pool with proper validation

4. **Monitor Server Health**
   - Track server health status in logs
   - Monitor connection distribution across servers
   - Set appropriate health check intervals

### For XA Connections

1. **Use Multinode for XA High Availability**
   - Deploy at least 3 OJP servers
   - Configure `ojp.xa.maxTransactions` appropriately
   - Servers automatically divide XA transaction limits

2. **Integrate with XA-Aware Connection Pools**
   - Use Atomikos, Bitronix, or similar
   - Enable connection validation in pool
   - Set appropriate validation query or timeout

3. **Configure Gradual Redistribution**
   - Set `ojp.xa.idle.rebalance.fraction` conservatively (0.3-0.5)
   - Limit `ojp.xa.max.close.per.recovery` to avoid disruption
   - Allow multiple health check cycles for complete rebalancing

4. **Handle Proactive Invalidation**
   - XA connections may be closed proactively on server failure
   - Pool will automatically create new connections
   - No application changes needed if using proper pool

### General Best Practices

1. **Minimum 3-Node Production Configuration**
   - With 2 nodes: 100% load increase when one fails
   - With 3 nodes: 50% load increase when one fails
   - Better fault tolerance and smoother load distribution

2. **Network Reliability**
   - Ensure reliable connectivity between clients and all servers
   - Use DNS names instead of IP addresses when possible
   - Configure appropriate timeout values

3. **Monitoring and Alerting**
   - Monitor server health status
   - Track connection distribution
   - Alert on server failures and recoveries
   - Monitor redistribution activities

4. **Testing**
   - Test server failure scenarios regularly
   - Verify connection failover behavior
   - Validate redistribution after recovery
   - Measure impact on application performance

---

## Appendix: Code References

### Key Files and Classes

**Connection Management**:
- `MultinodeConnectionManager.java` - Central coordinator
- `MultinodeStatementService.java` - Request routing
- `MultinodeUrlParser.java` - URL parsing and service creation

**Non-XA**:
- `Driver.java` - Entry point for non-XA connections
- `Connection.java` - Connection implementation
- `ConnectionRedistributor.java` - Non-XA redistribution

**XA**:
- `OjpXADataSource.java` - XA datasource implementation
- `OjpXAConnection.java` - XA connection with health listener
- `OjpXAResource.java` - XA resource implementation
- `XAConnectionRedistributor.java` - XA-specific redistribution

**Health and Tracking**:
- `HealthCheckValidator.java` - Server health validation
- `HealthCheckConfig.java` - Health check configuration
- `ConnectionTracker.java` - Connection tracking for load balancing
- `ServerEndpoint.java` - Server endpoint representation

**Documentation**:
- `/documents/multinode/README.md` - Multinode feature documentation
- `/documents/configuration/ojp-jdbc-configuration.md` - JDBC configuration
- `/documents/configuration/ojp-server-configuration.md` - Server configuration

---

## Conclusion

The OJP JDBC driver provides sophisticated multinode support with key differences between non-XA and XA connections:

**Non-XA** connections prioritize **datasource distribution** and **flexibility** by connecting to all servers, enabling broad failover capability for new connections while maintaining session stickiness for active sessions.

**XA** connections prioritize **resource efficiency** and **proper load distribution** by connecting to a single server per connection, with proactive invalidation and gradual redistribution to handle server failures gracefully without disrupting active XA transactions.

Both approaches enforce **session stickiness** to maintain ACID guarantees, provide **load-aware server selection** for optimal distribution, and support **automatic failover** for new connections when servers fail. The choice between non-XA and XA depends on whether distributed transaction support is required, with each optimized for its specific use case.

---

**Document Version**: 1.0  
**Last Updated**: 2025-12-23  
**Author**: Copilot Code Review Agent  
**Status**: Final
