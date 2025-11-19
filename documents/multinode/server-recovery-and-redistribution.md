# Server Recovery and Connection Redistribution

## Overview

This document describes the server recovery detection and connection redistribution feature for OJP multinode XA deployments. When a failed OJP server recovers, the system automatically detects the recovery, invalidates stale sessions, and rebalances connections across all available servers.

## Problem Statement

In multinode XA deployments, when a server fails:
1. Connections to that server are closed
2. New connections are distributed among remaining healthy servers
3. When the failed server recovers, it remains idle (receives no connections)
4. Load imbalance becomes permanent until application restart

**Additional Issue - Session State Loss:**

When a server is killed and resurrected:
1. Server loses all session state (sessions stored in-memory)
2. Client-side session bindings persist
3. Queries sent with old session UUIDs fail with "Connection not found" error
4. Applications experience failures until connections are manually recreated

**Example:**
```
Initial:  Server1=10, Server2=10, Server3=10 connections
Failure:  Server2=0 (failed), Server1=15, Server3=15 (redistributed)
Recovery: Server2=0 (healthy but unused), Server1=15, Server3=15 (still imbalanced)
          + Old sessions bound to Server2 fail with "Connection not found"
```

## Solution

The solution implements:
1. **Time-based health checks** - Periodically validates unhealthy servers
2. **Server recovery detection** - Identifies when failed servers become healthy
3. **XA session invalidation** - Clears stale session bindings when server recovers
4. **Automatic redistribution** - Rebalances connections when servers recover
5. **Balanced closure** - Fairly distributes connection closures across overloaded servers

## Architecture

### Components

#### 1. HealthCheckConfig
Loads configuration from `ojp.properties`:
- `ojp.health.check.interval` - How often to check (default: 30000ms)
- `ojp.health.check.threshold` - Min time before retrying unhealthy server (default: 30000ms)
- `ojp.health.check.timeout` - Health query timeout (default: 5000ms)
- `ojp.health.check.query` - Health check query (default: SELECT 1)
- `ojp.redistribution.enabled` - Enable/disable redistribution (default: true)

#### 2. ConnectionTracker
Tracks active connections and their bound servers using `ConcurrentHashMap`:
- `register(Connection, ServerEndpoint)` - Registers new connection
- `unregister(Connection)` - Removes closed connection
- `getDistribution()` - Returns map of servers to connections (only called during redistribution)
- `getCounts()` - Returns connection counts per server

#### 3. HealthCheckValidator
Validates server health by attempting direct connection:
- Creates test connection to server
- Executes health check query (if configured)
- Returns true if server responds, false otherwise
- Closes test connection after validation

#### 4. ConnectionRedistributor
Implements balanced redistribution algorithm:
- Calculates target connections per server
- Identifies overloaded servers
- Marks connections for closure using round-robin across overloaded servers
- Logs warnings if redistribution incomplete

#### 5. MultinodeConnectionManager (Enhanced)
Integrates health check and redistribution:
- Uses `AtomicLong` timestamp for time-based triggering
- Calls `tryTriggerHealthCheck()` on each connection borrow
- Only one thread executes health check (via `compareAndSet`)
- Triggers redistribution when servers recover

#### 6. Connection (Enhanced)
Supports forced invalidation:
- `markForceInvalid()` - Marks connection for removal
- `isForceInvalid()` - Checks if marked
- `checkValid()` - Throws `SQLNonTransientConnectionException` with SQLState 08006
- `isValid()` - Returns false when marked invalid

## Flow Diagram

```
1. Application borrows connection from pool
2. MultinodeConnectionManager.connect() called
3. Check if health check interval elapsed
   ├─ No → Continue with connection
   └─ Yes → Perform health check
       ├─ Validate each unhealthy server
       │  ├─ Server responds → Mark healthy, invalidate XA sessions, add to recovered list
       │  └─ Server fails → Update last failure timestamp
       └─ If servers recovered
           ├─ XA sessions for recovered server already invalidated
           ├─ Calculate target distribution
           ├─ Identify overloaded servers
           ├─ Mark connections for closure (balanced)
           └─ Marked connections throw SQLState 08006 on next use
4. Pool detects invalid connections (via isValid() or 08006)
5. Pool closes invalid connections permanently
6. Pool creates new connections to replace closed ones
7. New connections distributed via load-aware/round-robin (includes recovered servers)
8. New connections create fresh sessions on recovered server
9. Load rebalanced!
```

### XA Session Invalidation Details

When a server recovers from failure:

1. **Detection**: Health check validates server is responding
2. **Session Invalidation** (XA mode only):
   - Identify all sessions bound to recovered server in `sessionToServerMap`
   - Remove session bindings (clears client-side cache)
   - Log invalidated sessions for debugging
3. **Result**: 
   - Old sessions can't be used (no binding exists)
   - Connection pools detect invalid connections
   - New connections create fresh sessions
   - No "Connection not found" errors

**Note**: Only affects XA mode where sessions are tracked. Non-XA mode doesn't maintain session bindings.

## Configuration

### ojp.properties

```properties
# Health check interval (milliseconds) - how often to check unhealthy servers
ojp.health.check.interval=30000

# Health check threshold (milliseconds) - min time before retrying failed server
ojp.health.check.threshold=30000

# Health check timeout (milliseconds) - query execution timeout
ojp.health.check.timeout=5000

# Health check query - executed to validate server health
ojp.health.check.query=SELECT 1

# Enable/disable automatic redistribution on recovery
ojp.redistribution.enabled=true
```

### Database-Specific Health Queries

Default: `SELECT 1` (works for most databases)

Database-specific alternatives:
- **PostgreSQL**: `SELECT 1`
- **MySQL**: `SELECT 1`
- **Oracle**: `SELECT 1 FROM DUAL`
- **SQL Server**: `SELECT 1`
- **H2**: `SELECT 1`

## Performance

### Overhead

**Per Connection Borrow:**
- Time check: ~50 nanoseconds
- Timestamp comparison only
- No locks, no I/O

**Health Check (every 30s by default):**
- Validates only unhealthy servers
- Single thread execution (via compareAndSet)
- ~100-300ms per server validation
- Non-blocking (doesn't affect connection attempts)

### Scalability

- Works efficiently with 10-50 connections (typical)
- ConcurrentHashMap iteration very fast for this size
- Only iterates map during redistribution (infrequent)

## Testing

### Manual Testing

1. Start 3 OJP servers
2. Create connection pool (Atomikos/HikariCP/DBCP)
3. Generate load to create 30 connections (10 per server)
4. Stop server2
5. Verify connections redistribute to server1 and server3 (15 each)
6. Wait 30+ seconds
7. Start server2
8. Verify connections rebalance after 30-60 seconds (10 per server)

### Monitoring

Check logs for:
```
INFO  MultinodeConnectionManager - Checking N unhealthy server(s)
INFO  MultinodeConnectionManager - Server <address> has recovered
INFO  MultinodeConnectionManager - Invalidating N XA session(s) bound to recovered server <address>
INFO  MultinodeConnectionManager - XA session invalidation complete for server <address>
INFO  ConnectionRedistributor - Starting connection redistribution for N recovered server(s)
INFO  ConnectionRedistributor - Redistribution complete: marked N connections for closure
```

### XA Session Invalidation Logs

When a server recovers, you'll see:
```
INFO  MultinodeConnectionManager - Server server1:1059 has recovered
INFO  MultinodeConnectionManager - Invalidating 5 XA session(s) bound to recovered server server1:1059
DEBUG MultinodeConnectionManager - Invalidated XA session abc-123-def for recovered server server1:1059
DEBUG MultinodeConnectionManager - Invalidated XA session xyz-456-uvw for recovered server server1:1059
...
INFO  MultinodeConnectionManager - XA session invalidation complete for server server1:1059. Connection pools will create new sessions.
```

If no sessions were bound (server was idle or non-XA mode):
```
DEBUG MultinodeConnectionManager - No sessions bound to recovered server server1:1059
```

### Verification Queries

```java
// Get connection counts per server
Map<ServerEndpoint, Integer> counts = 
    connectionManager.getConnectionTracker().getCounts();
    
// Check if redistribution enabled
boolean enabled = healthCheckConfig.isRedistributionEnabled();
```

## Troubleshooting

### Server Not Recovering

**Symptoms:** Server marked healthy but no connections redistributed

**Possible Causes:**
1. Health check disabled: Check `ojp.redistribution.enabled=true`
2. Threshold not met: Wait for `ojp.health.check.interval` to elapse
3. Connections in use: Wait for connections to be returned to pool
4. Pool not validating: Check pool validation configuration

**Solutions:**
- Enable logging: `log.level.org.openjproxy.grpc.client=DEBUG`
- Check configuration: Review ojp.properties
- Monitor pool: Check pool statistics

### Partial Redistribution

**Symptoms:** Warning "Redistribution incomplete: N connections not marked"

**Possible Causes:**
1. Active transactions: Connections in use can't be marked
2. Low traffic: Connections not being borrowed
3. Pool behavior: Some pools may not return connections immediately

**Solutions:**
- Wait for traffic: More borrows = more opportunities to mark
- Check transactions: Ensure transactions complete
- Increase interval: Give more time between checks

### Performance Impact

**Symptoms:** Slowdown during redistribution

**Possible Causes:**
1. Too many connections: Thousands of connections (unusual for XA)
2. Frequent health checks: Interval too short
3. Complex health query: Query takes too long

**Solutions:**
- Increase interval: `ojp.health.check.interval=60000` (60s)
- Simplify query: Use `SELECT 1` instead of complex query
- Increase timeout: `ojp.health.check.timeout=10000` (10s)

### "Connection not found" Errors After Server Recovery

**Symptoms:** After server restart, queries fail with "Connection not found for this sessionInfo"

**Root Cause:** 
- Server loses session state when killed (in-memory storage)
- Client-side session bindings persist
- Queries sent with old session UUIDs that don't exist on resurrected server

**Solution (Automatic in XA Mode):**
The system automatically invalidates XA sessions when a server recovers:
1. Health check detects server recovery
2. All sessions bound to that server are invalidated
3. Connection pools detect invalid connections
4. New connections created with fresh sessions

**Manual Verification:**
- Check logs for "Invalidating N XA session(s)" messages
- Verify XA redistributor is enabled: `manager.setXaConnectionRedistributor()`
- Ensure connection pool validates connections periodically

**For Non-XA Mode:**
Session invalidation only affects XA mode. In non-XA mode, applications should implement retry logic for transient connection errors.

## Best Practices

### Configuration

1. **Set appropriate intervals**
   - Start with 30s interval (default)
   - Increase for stable environments (60s+)
   - Decrease for testing only (10s minimum)

2. **Use simple health queries**
   - `SELECT 1` is sufficient for most cases
   - Avoid complex queries or large result sets
   - Ensure query executes in < 1 second

3. **Enable logging during initial deployment**
   - Set `log.level.org.openjproxy.grpc.client=INFO`
   - Monitor recovery events
   - Verify redistribution working

### Operations

1. **Monitor connection distribution**
   - Check logs for redistribution events
   - Verify balanced distribution after recovery
   - Alert on permanent imbalance

2. **Plan for downtime**
   - Health checks will detect planned restarts
   - Redistribution automatic (no manual intervention)
   - Expect 30-60s delay for rebalancing

3. **Test recovery scenarios**
   - Test server failure and recovery
   - Verify redistribution working
   - Validate performance acceptable

## Limitations

1. **Requires connection pool**
   - Works with pools that validate connections (isValid() or test query)
   - Most modern pools support this (Atomikos, HikariCP, DBCP)

2. **Gradual redistribution**
   - Connections marked as borrowed
   - Takes time for all connections to be redistributed
   - Usually completes within few minutes under normal traffic

3. **Cannot interrupt transactions**
   - Active transactions not interrupted
   - Connection marked on return to pool
   - Ensures transaction safety

4. **Multinode only**
   - Feature only works in multinode deployments
   - Single-node deployments unaffected

## Future Enhancements

Potential improvements for future versions:

1. **Metrics and monitoring**
   - Expose JMX metrics for redistribution
   - Track recovery success rate
   - Monitor redistribution completion time

2. **Configurable redistribution strategy**
   - Immediate (current): Close connections immediately
   - Gradual: Reduce connection lifetime gradually
   - Manual: Trigger redistribution via API

3. **Advanced health checks**
   - Execute custom health check queries
   - Validate specific database features
   - Check connection performance

4. **Admin API**
   - Manually trigger health checks
   - Force redistribution
   - View current distribution

## References

- [JDBC Connection Pool Validation](https://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html#isValid-int-)
- [SQLState Codes](https://en.wikipedia.org/wiki/SQLSTATE)
- [Atomikos Connection Pooling](https://www.atomikos.com/Documentation/ConnectionPoolConfiguration)
- [HikariCP Connection Testing](https://github.com/brettwooldridge/HikariCP#connection-testing)
