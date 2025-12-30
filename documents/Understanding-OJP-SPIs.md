# Understanding OJP Service Provider Interfaces (SPIs)

## Introduction

Open-J-Proxy (OJP) introduces a powerful plugin architecture through two Service Provider Interfaces (SPIs) that allow Java developers to extend and customize connection pooling behavior. This article explains what these SPIs are, why they matter, and how you can implement your own providers.

## What is a Service Provider Interface (SPI)?

A Service Provider Interface is a design pattern in Java that enables extensibility through pluggable implementations. Java's `ServiceLoader` mechanism discovers and loads implementations at runtime by scanning the classpath for configuration files.

Think of it as a "plugin system" where:
- The **interface** defines the contract (what methods must be implemented)
- The **implementation** provides the actual functionality
- The **ServiceLoader** automatically discovers and loads implementations without hardcoding dependencies

## The Two SPIs in OJP

OJP provides two SPIs for different connection pooling needs:

### 1. ConnectionPoolProvider - For Standard JDBC Connections

**Purpose**: Manages regular (non-distributed) database connection pools.

**Location**: `org.openjproxy.datasource.ConnectionPoolProvider`

**When to use**: When your application uses standard JDBC connections without distributed transactions.

**Built-in Implementations**:
- **HikariCP** (default, priority 100) - High-performance connection pool
- **Apache DBCP** (priority 0) - Alternative connection pool

### 2. XAConnectionPoolProvider - For Distributed Transactions

**Purpose**: Manages XA-enabled connection pools for distributed transactions across multiple databases.

**Location**: `org.openjproxy.xa.pool.spi.XAConnectionPoolProvider`

**When to use**: When your application requires two-phase commit (2PC) distributed transactions.

**Built-in Implementation**:
- **CommonsPool2XAProvider** (priority 100) - Universal provider that works with all databases supporting XA

## Why Use These SPIs?

### Flexibility
Replace the connection pool implementation without changing your application code. Switch from HikariCP to another pool by simply adding a JAR to the classpath.

### Extensibility
Create custom pool providers optimized for specific databases or use cases. For example, you could implement an Oracle UCP provider for Oracle-specific optimizations.

### Zero Vendor Lock-in
The XAConnectionPoolProvider uses reflection to configure any XADataSource, avoiding compile-time dependencies on vendor JDBC drivers.

### Automatic Selection
Multiple providers can coexist. OJP automatically selects the best available provider based on priority and availability.

---

## Part 1: Implementing ConnectionPoolProvider

Let's walk through implementing a custom connection pool provider.

### The Interface

The `ConnectionPoolProvider` interface defines five key methods:

```java
public interface ConnectionPoolProvider {
    // Unique identifier for this provider (e.g., "hikari", "dbcp", "my-pool")
    String id();
    
    // Create a configured DataSource based on PoolConfig
    DataSource createDataSource(PoolConfig config) throws SQLException;
    
    // Close the DataSource and release resources
    void closeDataSource(DataSource dataSource) throws Exception;
    
    // Return current pool statistics
    Map<String, Object> getStatistics(DataSource dataSource);
    
    // Priority for auto-selection (higher = preferred)
    default int getPriority() {
        return 0;
    }
    
    // Check if required dependencies are available
    default boolean isAvailable() {
        return true;
    }
}
```

### Example Implementation

Here's a simplified example based on HikariCP:

```java
package com.example.pool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.openjproxy.datasource.ConnectionPoolProvider;
import org.openjproxy.datasource.PoolConfig;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class MyCustomPoolProvider implements ConnectionPoolProvider {

    @Override
    public String id() {
        return "my-custom-pool";
    }

    @Override
    public DataSource createDataSource(PoolConfig config) throws SQLException {
        // Validate configuration
        if (config == null) {
            throw new IllegalArgumentException("PoolConfig cannot be null");
        }

        // Create HikariCP configuration
        HikariConfig hikariConfig = new HikariConfig();
        
        // Map PoolConfig to HikariCP settings
        hikariConfig.setJdbcUrl(config.getUrl());
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPasswordAsString());
        hikariConfig.setDriverClassName(config.getDriverClassName());
        
        // Pool sizing
        hikariConfig.setMaximumPoolSize(config.getMaxPoolSize());
        hikariConfig.setMinimumIdle(config.getMinIdle());
        
        // Timeouts
        hikariConfig.setConnectionTimeout(config.getConnectionTimeoutMs());
        hikariConfig.setIdleTimeout(config.getIdleTimeoutMs());
        hikariConfig.setMaxLifetime(config.getMaxLifetimeMs());
        
        // Validation query (if provided)
        if (config.getValidationQuery() != null) {
            hikariConfig.setConnectionTestQuery(config.getValidationQuery());
        }
        
        // Auto-commit behavior
        hikariConfig.setAutoCommit(config.isAutoCommit());
        
        // Pool name for monitoring
        hikariConfig.setPoolName("my-custom-pool-" + System.currentTimeMillis());
        
        // Create and return DataSource
        return new HikariDataSource(hikariConfig);
    }

    @Override
    public void closeDataSource(DataSource dataSource) throws Exception {
        if (dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
        }
    }

    @Override
    public Map<String, Object> getStatistics(DataSource dataSource) {
        Map<String, Object> stats = new HashMap<>();
        
        if (dataSource instanceof HikariDataSource) {
            HikariDataSource hikariDS = (HikariDataSource) dataSource;
            
            // Collect statistics from HikariCP MXBean
            if (hikariDS.getHikariPoolMXBean() != null) {
                stats.put("activeConnections", 
                    hikariDS.getHikariPoolMXBean().getActiveConnections());
                stats.put("idleConnections", 
                    hikariDS.getHikariPoolMXBean().getIdleConnections());
                stats.put("totalConnections", 
                    hikariDS.getHikariPoolMXBean().getTotalConnections());
                stats.put("threadsAwaitingConnection", 
                    hikariDS.getHikariPoolMXBean().getThreadsAwaitingConnection());
            }
        }
        
        return stats;
    }

    @Override
    public int getPriority() {
        // Lower priority than HikariCP (100), will be used if HikariCP is unavailable
        return 50;
    }

    @Override
    public boolean isAvailable() {
        try {
            // Check if HikariCP is on the classpath
            Class.forName("com.zaxxer.hikari.HikariDataSource");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
```

### Registering Your Provider

Create a file at `META-INF/services/org.openjproxy.datasource.ConnectionPoolProvider` in your JAR:

```
com.example.pool.MyCustomPoolProvider
```

That's it! When your JAR is on the classpath, OJP will automatically discover and use your provider.

### Understanding PoolConfig

The `PoolConfig` class provides a standardized way to configure connection pools:

```java
PoolConfig config = PoolConfig.builder()
    .url("jdbc:postgresql://localhost:5432/mydb")
    .username("dbuser")
    .password("secret".toCharArray())
    .driverClassName("org.postgresql.Driver")
    .maxPoolSize(20)
    .minIdle(5)
    .connectionTimeoutMs(30000)
    .idleTimeoutMs(600000)
    .maxLifetimeMs(1800000)
    .validationQuery("SELECT 1")
    .autoCommit(true)
    .build();

DataSource ds = provider.createDataSource(config);
```

---

## Part 2: Implementing XAConnectionPoolProvider

The XA SPI is designed for distributed transactions with a zero-dependency approach.

### The Interface

The `XAConnectionPoolProvider` interface is more complex as it handles XA transaction lifecycle:

```java
public interface XAConnectionPoolProvider {
    // Unique identifier for this provider
    String id();
    
    // Create a pooled XADataSource
    XADataSource createXADataSource(Map<String, String> config) 
        throws SQLException, ReflectiveOperationException;
    
    // Close the XADataSource pool
    void closeXADataSource(XADataSource xaDataSource) throws Exception;
    
    // Get pool statistics
    Map<String, Object> getStatistics(XADataSource xaDataSource);
    
    // Provider priority
    default int getPriority() {
        return 0;
    }
    
    // Check availability
    default boolean isAvailable() {
        return true;
    }
    
    // Check if this provider supports a specific database
    default boolean supportsDatabase(String jdbcUrl, String driverClassName) {
        return true;
    }
    
    // Borrow a session from the pool
    XABackendSession borrowSession(Object xaDataSource) throws Exception;
    
    // Return a session to the pool
    void returnSession(Object xaDataSource, XABackendSession session) throws Exception;
    
    // Invalidate a broken session
    void invalidateSession(Object xaDataSource, XABackendSession session) throws Exception;
}
```

### XA Configuration

The XA pool provider uses a `Map<String, String>` for configuration with these canonical keys:

```java
Map<String, String> config = new HashMap<>();

// Required: XADataSource class name
config.put("xa.datasource.className", "org.postgresql.xa.PGXADataSource");

// Connection properties
config.put("xa.url", "jdbc:postgresql://localhost:5432/mydb");
config.put("xa.username", "postgres");
config.put("xa.password", "secret");

// Pool sizing
config.put("xa.maxPoolSize", "20");
config.put("xa.minIdle", "2");

// Timeouts
config.put("xa.connectionTimeoutMs", "30000");
config.put("xa.idleTimeoutMs", "600000");
config.put("xa.maxLifetimeMs", "1800000");

// Optional validation
config.put("xa.validationQuery", "SELECT 1");
```

### Database-Specific XADataSource Classes

Different databases use different XADataSource implementations:

| Database | XADataSource Class |
|----------|-------------------|
| PostgreSQL | `org.postgresql.xa.PGXADataSource` |
| SQL Server | `com.microsoft.sqlserver.jdbc.SQLServerXADataSource` |
| Oracle | `oracle.jdbc.xa.client.OracleXADataSource` |
| MySQL | `com.mysql.cj.jdbc.MysqlXADataSource` |
| MariaDB | `org.mariadb.jdbc.MariaDbDataSource` |
| DB2 | `com.ibm.db2.jcc.DB2XADataSource` |

### Example Implementation: Universal XA Provider

Here's a simplified version showing the key concepts:

```java
package com.example.xa;

import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;
import org.openjproxy.xa.pool.XABackendSession;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import javax.sql.XADataSource;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class MyUniversalXAProvider implements XAConnectionPoolProvider {

    @Override
    public String id() {
        return "my-universal-xa";
    }

    @Override
    public XADataSource createXADataSource(Map<String, String> config) 
            throws SQLException, ReflectiveOperationException {
        
        // Get the XADataSource class name
        String className = config.get("xa.datasource.className");
        if (className == null) {
            throw new IllegalArgumentException("xa.datasource.className is required");
        }
        
        // Instantiate the vendor XADataSource using reflection
        Class<?> xaDataSourceClass = Class.forName(className);
        XADataSource vendorXADS = (XADataSource) xaDataSourceClass
            .getDeclaredConstructor()
            .newInstance();
        
        // Configure the XADataSource using reflection
        setPropertyIfPresent(vendorXADS, "URL", config.get("xa.url"));
        setPropertyIfPresent(vendorXADS, "user", config.get("xa.username"));
        setPropertyIfPresent(vendorXADS, "password", config.get("xa.password"));
        
        // Additional database-specific properties
        setPropertyIfPresent(vendorXADS, "databaseName", config.get("xa.databaseName"));
        setPropertyIfPresent(vendorXADS, "serverName", config.get("xa.serverName"));
        setPropertyIfPresent(vendorXADS, "portNumber", config.get("xa.portNumber"));
        
        // Create a pooled wrapper
        return new MyPooledXADataSource(vendorXADS, config);
    }

    @Override
    public void closeXADataSource(XADataSource xaDataSource) throws Exception {
        if (xaDataSource instanceof MyPooledXADataSource) {
            ((MyPooledXADataSource) xaDataSource).close();
        }
    }

    @Override
    public Map<String, Object> getStatistics(XADataSource xaDataSource) {
        Map<String, Object> stats = new HashMap<>();
        
        if (xaDataSource instanceof MyPooledXADataSource) {
            MyPooledXADataSource pooled = (MyPooledXADataSource) xaDataSource;
            stats.put("activeConnections", pooled.getNumActive());
            stats.put("idleConnections", pooled.getNumIdle());
            stats.put("totalConnections", pooled.getNumActive() + pooled.getNumIdle());
        }
        
        return stats;
    }

    @Override
    public int getPriority() {
        return 100; // Default/universal provider
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("org.apache.commons.pool2.ObjectPool");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean supportsDatabase(String jdbcUrl, String driverClassName) {
        // Universal provider supports all databases
        return true;
    }

    @Override
    public XABackendSession borrowSession(Object xaDataSource) throws Exception {
        if (!(xaDataSource instanceof MyPooledXADataSource)) {
            throw new IllegalArgumentException("Invalid XADataSource type");
        }
        return ((MyPooledXADataSource) xaDataSource).borrowSession();
    }

    @Override
    public void returnSession(Object xaDataSource, XABackendSession session) throws Exception {
        if (!(xaDataSource instanceof MyPooledXADataSource)) {
            throw new IllegalArgumentException("Invalid XADataSource type");
        }
        ((MyPooledXADataSource) xaDataSource).returnSession(session);
    }

    @Override
    public void invalidateSession(Object xaDataSource, XABackendSession session) 
            throws Exception {
        if (!(xaDataSource instanceof MyPooledXADataSource)) {
            throw new IllegalArgumentException("Invalid XADataSource type");
        }
        ((MyPooledXADataSource) xaDataSource).invalidateSession(session);
    }

    // Helper method to set properties via reflection
    private void setPropertyIfPresent(Object obj, String propertyName, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        
        try {
            String setterName = "set" + propertyName.substring(0, 1).toUpperCase() 
                              + propertyName.substring(1);
            
            // Try String parameter
            try {
                Method setter = obj.getClass().getMethod(setterName, String.class);
                setter.invoke(obj, value);
                return;
            } catch (NoSuchMethodException e) {
                // Try int parameter (for port numbers, etc.)
                try {
                    Method setter = obj.getClass().getMethod(setterName, int.class);
                    setter.invoke(obj, Integer.parseInt(value));
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Exception e) {
            // Property not supported, ignore
        }
    }
}
```

### Registering Your XA Provider

Create a file at `META-INF/services/org.openjproxy.xa.pool.spi.XAConnectionPoolProvider`:

```
com.example.xa.MyUniversalXAProvider
```

### Database-Specific Optimization Example

You can create optimized providers for specific databases:

```java
public class OracleUCPXAProvider implements XAConnectionPoolProvider {

    @Override
    public String id() {
        return "oracle-ucp";
    }

    @Override
    public int getPriority() {
        return 50; // Medium priority, specific to Oracle
    }

    @Override
    public boolean supportsDatabase(String jdbcUrl, String driverClassName) {
        // Only support Oracle databases
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:oracle:");
    }

    @Override
    public boolean isAvailable() {
        try {
            // Check if Oracle UCP is available
            Class.forName("oracle.ucp.jdbc.PoolDataSource");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public XADataSource createXADataSource(Map<String, String> config) 
            throws SQLException, ReflectiveOperationException {
        // Use Oracle's Universal Connection Pool
        // Implementation details...
        throw new UnsupportedOperationException("Example only");
    }
    
    // ... implement other methods
}
```

---

## Understanding Provider Selection

When multiple providers are available, OJP selects based on:

1. **Availability**: The provider's `isAvailable()` must return `true`
2. **Database Support**: For XA providers, `supportsDatabase()` must return `true`
3. **Priority**: Higher priority wins (range typically 0-100)

### Selection Examples

**Scenario 1**: Standard connection pooling
- HikariCP available (priority 100) → **HikariCP selected** ✓
- DBCP available (priority 0) → Not selected
- Custom pool available (priority 50) → Not selected

**Scenario 2**: HikariCP not on classpath
- HikariCP unavailable (isAvailable = false) → Not selected
- Custom pool available (priority 50) → **Custom pool selected** ✓
- DBCP available (priority 0) → Not selected

**Scenario 3**: XA pooling with Oracle
- Oracle UCP provider (priority 50, supports Oracle) → **Selected for Oracle** ✓
- CommonsPool2 (priority 100, universal) → Used for other databases ✓

---

## Real-World Usage Examples

### Example 1: Standard Connection with Custom Pool

```java
// Application code - no changes needed
String url = "jdbc:postgresql://localhost:5432/mydb";
DataSource ds = // OJP creates this using the highest-priority provider

// OJP automatically:
// 1. Discovers all ConnectionPoolProvider implementations
// 2. Checks isAvailable() for each
// 3. Selects the one with highest priority
// 4. Creates DataSource using your provider
```

### Example 2: Distributed Transaction with XA

```java
// Application code using XA transactions
XADataSource xaDS = // OJP creates using XAConnectionPoolProvider

// OJP configuration (server-side)
Map<String, String> config = new HashMap<>();
config.put("xa.datasource.className", "org.postgresql.xa.PGXADataSource");
config.put("xa.url", "jdbc:postgresql://localhost:5432/mydb");
config.put("xa.maxPoolSize", "20");

// OJP automatically:
// 1. Discovers all XAConnectionPoolProvider implementations
// 2. Checks isAvailable() and supportsDatabase()
// 3. Selects best match based on priority
// 4. Creates pooled XADataSource
```

### Example 3: Multiple Databases, Different Providers

```java
// Configuration for multiple databases
// PostgreSQL - uses CommonsPool2XAProvider (universal)
postgresConfig.put("xa.datasource.className", "org.postgresql.xa.PGXADataSource");

// Oracle - uses OracleUCPXAProvider (if available, optimized)
oracleConfig.put("xa.datasource.className", "oracle.jdbc.xa.client.OracleXADataSource");

// SQL Server - uses CommonsPool2XAProvider (universal)
sqlServerConfig.put("xa.datasource.className", "com.microsoft.sqlserver.jdbc.SQLServerXADataSource");

// Each gets the best provider automatically!
```

---

## Benefits of the SPI Approach

### 1. Separation of Concerns
OJP core remains independent of specific pool implementations. Add HikariCP, DBCP, C3P0, or your custom pool without modifying OJP.

### 2. Testing Flexibility
Easily swap in a mock provider for testing:

```java
public class MockPoolProvider implements ConnectionPoolProvider {
    @Override
    public String id() {
        return "mock";
    }
    
    @Override
    public int getPriority() {
        return 1000; // Highest priority for tests
    }
    
    @Override
    public DataSource createDataSource(PoolConfig config) {
        return new MockDataSource(); // Your test double
    }
    
    // ... other methods
}
```

### 3. Graceful Degradation
If the preferred provider is unavailable, OJP falls back to the next available provider automatically.

### 4. Database-Specific Optimizations
For XA providers, you can optimize for specific databases while maintaining a universal fallback.

### 5. No Configuration Required
Just add the JAR to the classpath. ServiceLoader handles discovery automatically.

---

## Best Practices

### For ConnectionPoolProvider Implementations:

1. **Validate Configuration**: Check for null or invalid PoolConfig values
2. **Log Configuration**: Help users debug pool setup issues
3. **Handle Cleanup Gracefully**: Ensure `closeDataSource()` is idempotent
4. **Return Meaningful Statistics**: Help with monitoring and troubleshooting
5. **Set Appropriate Priority**: Default providers use 100, alternatives use lower values
6. **Check Dependencies**: Implement `isAvailable()` to verify classpath dependencies

### For XAConnectionPoolProvider Implementations:

1. **Use Reflection Wisely**: Handle vendor differences in property names (URL vs url vs Url)
2. **Support All Config Keys**: Implement the canonical configuration keys
3. **Session Lifecycle**: Properly implement borrow/return/invalidate session methods
4. **Handle Prepared Transactions**: Sessions in PREPARED state must not be closed
5. **Reset on Return**: Clean up session state before returning to pool
6. **Universal by Default**: Make your provider work with all databases unless it's database-specific

### General Best Practices:

1. **Thread Safety**: Ensure your provider is thread-safe
2. **Resource Management**: Always clean up resources (connections, threads, timers)
3. **Error Handling**: Provide clear error messages for configuration issues
4. **Documentation**: Document configuration keys and their meanings
5. **Testing**: Test with real databases, not just mocks
6. **Performance**: Profile your implementation under load

---

## Troubleshooting

### Provider Not Being Selected

**Problem**: Your provider is on the classpath but not being used.

**Solutions**:
1. Check the ServiceLoader file path: `META-INF/services/org.openjproxy.datasource.ConnectionPoolProvider`
2. Verify the fully qualified class name in the file
3. Ensure the class has a public no-args constructor
4. Check that `isAvailable()` returns `true`
5. Verify another provider doesn't have higher priority

### ClassNotFoundException at Runtime

**Problem**: Provider loads but fails when creating pools.

**Solutions**:
1. Add the pool library to the classpath (e.g., HikariCP, Commons Pool)
2. For XA providers, ensure vendor JDBC driver is on classpath
3. Check dependency versions are compatible

### Configuration Not Applied

**Problem**: Pool doesn't use the configuration you specified.

**Solutions**:
1. For ConnectionPoolProvider: Verify PoolConfig mapping in `createDataSource()`
2. For XAConnectionPoolProvider: Check reflection-based property setting
3. Log configuration values to verify they're being passed correctly
4. Some properties may have different names in different pools

---

## Conclusion

OJP's Service Provider Interfaces offer a powerful, flexible way to extend connection pooling behavior without modifying the core framework. Whether you need a custom connection pool, database-specific optimizations, or specialized XA handling, the SPI pattern makes it straightforward to implement and integrate.

Key takeaways:
- **ConnectionPoolProvider** for standard JDBC pooling
- **XAConnectionPoolProvider** for distributed transaction pooling
- ServiceLoader automatically discovers and loads implementations
- Priority-based selection with graceful fallback
- Zero vendor dependencies through reflection-based configuration

Start by exploring the built-in implementations (HikariCP, CommonsPool2), then create your own when you need custom behavior. The SPI approach ensures your extensions integrate seamlessly with OJP's architecture.

---

## Additional Resources

- [OJP GitHub Repository](https://github.com/Open-J-Proxy/ojp)
- [ConnectionPoolProvider Interface](https://github.com/Open-J-Proxy/ojp/blob/main/ojp-datasource-api/src/main/java/org/openjproxy/datasource/ConnectionPoolProvider.java)
- [XAConnectionPoolProvider Interface](https://github.com/Open-J-Proxy/ojp/blob/main/ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/spi/XAConnectionPoolProvider.java)
- [HikariCP Implementation Example](https://github.com/Open-J-Proxy/ojp/blob/main/ojp-datasource-hikari/src/main/java/org/openjproxy/datasource/hikari/HikariConnectionPoolProvider.java)
- [CommonsPool2 XA Implementation Example](https://github.com/Open-J-Proxy/ojp/blob/main/ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/CommonsPool2XAProvider.java)
- [Java ServiceLoader Documentation](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html)
- [XA Specification Overview](https://pubs.opengroup.org/onlinepubs/009680699/toc.pdf)

---

**Author**: Open-J-Proxy Team  
**Last Updated**: December 2025  
**License**: Apache 2.0
