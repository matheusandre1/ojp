# ADR 006: Adopt Service Provider Interface (SPI) Pattern for Extensibility

In the context of the OJP project,  
facing the need to support multiple connection pool implementations and provide extensibility without coupling the core framework to specific implementations,  

we decided for adopting the **Service Provider Interface (SPI) pattern** with Java's ServiceLoader mechanism  
and neglected hard-coded dependencies on specific connection pool libraries,  

to achieve pluggable architecture, zero vendor lock-in, and runtime extensibility,  
accepting the complexity of dynamic class loading and provider registration,  

because the SPI pattern enables customers to extend OJP with custom connection pool providers, supports multiple implementations coexisting on the classpath, and allows deployment of extensions without recompiling OJP Server.

## Context

OJP Server acts as a proxy between applications and databases, managing connection pooling to protect databases from connection storms. Initially, the project was tightly coupled to HikariCP for standard connections and used a hardcoded approach for XA (distributed transaction) connection pooling.

As the project evolved, several requirements emerged:

1. **Flexibility**: Different databases and use cases may benefit from different connection pool implementations
2. **Extensibility**: Customers should be able to add optimized providers for specific databases (e.g., Oracle UCP for Oracle)
3. **Zero Vendor Lock-in**: The core framework should not depend on specific connection pool implementations at compile time
4. **Runtime Discovery**: Providers should be discovered automatically without configuration changes
5. **External Deployment**: Custom providers should be deployable via the `ojp-libs` directory without rebuilding OJP

## Decision

We implemented two Service Provider Interfaces (SPIs):

### 1. ConnectionPoolProvider
**Purpose**: Manages standard (non-XA) JDBC connection pools  
**Location**: `org.openjproxy.datasource.ConnectionPoolProvider`

**Key Methods**:
- `String id()` - Unique provider identifier
- `DataSource createDataSource(PoolConfig config)` - Creates configured pool
- `void closeDataSource(DataSource ds)` - Releases pool resources
- `Map<String, Object> getStatistics(DataSource ds)` - Returns pool metrics
- `int getPriority()` - Selection priority (higher wins)
- `boolean isAvailable()` - Checks if dependencies are present

**Built-in Implementations**:
- HikariCP (priority 100) - Default high-performance pool
- Apache DBCP (priority 10) - Alternative implementation

### 2. XAConnectionPoolProvider
**Purpose**: Manages XA-enabled connection pools for distributed transactions  
**Location**: `org.openjproxy.xa.pool.spi.XAConnectionPoolProvider`

**Key Methods**:
- `String id()` - Unique provider identifier
- `XADataSource createXADataSource(Map<String, String> config)` - Creates XA pool
- `void closeXADataSource(XADataSource ds)` - Releases pool resources
- `XABackendSession borrowSession(Object xaDataSource)` - Obtains session from pool
- `void returnSession(Object xaDataSource, XABackendSession session)` - Returns session
- `void invalidateSession(Object xaDataSource, XABackendSession session)` - Invalidates broken session
- `int getPriority()` - Selection priority (higher wins)
- `boolean isAvailable()` - Checks if dependencies are present

**Built-in Implementation**:
- CommonsPool2XAProvider (priority 100) - Universal provider using Apache Commons Pool 2, works with all XA-capable databases via reflection

### Provider Selection Mechanism

Both SPIs use identical selection logic implemented in their respective registries:

```java
// ConnectionPoolProviderRegistry
public static Optional<ConnectionPoolProvider> getDefaultProvider() {
    return providers.values().stream()
            .filter(ConnectionPoolProvider::isAvailable)
            .max(Comparator.comparingInt(ConnectionPoolProvider::getPriority));
}

// XA Provider Selection (in StatementServiceImpl)
int highestPriority = Integer.MIN_VALUE;
for (XAConnectionPoolProvider provider : loader) {
    if (provider.isAvailable() && provider.getPriority() > highestPriority) {
        selectedProvider = provider;
        highestPriority = provider.getPriority();
    }
}
```

**Selection Criteria**:
1. Provider must be available (`isAvailable()` returns `true`)
2. Provider with highest priority wins
3. Ties are resolved by ServiceLoader discovery order

### Registration

Providers are registered using Java's standard ServiceLoader mechanism:

**File**: `META-INF/services/org.openjproxy.datasource.ConnectionPoolProvider`
```
org.openjproxy.datasource.hikari.HikariConnectionPoolProvider
```

**File**: `META-INF/services/org.openjproxy.xa.pool.spi.XAConnectionPoolProvider`
```
org.openjproxy.xa.pool.commons.CommonsPool2XAProvider
```

### Integration with External Driver Loading

The SPI pattern integrates seamlessly with OJP's external driver loading mechanism (`ojp-libs` directory):

1. At startup, OJP loads all JARs from `ojp-libs` using `URLClassLoader`
2. ServiceLoader discovers providers from both bundled and external JARs
3. Providers are selected based on availability and priority
4. No recompilation or configuration changes required

**Example Deployment**:
```
ojp-libs/
├── ojdbc11.jar                    # Oracle JDBC driver
├── ucp.jar                        # Oracle UCP library
└── oracle-ucp-xa-provider-1.0.jar # Custom XA provider (priority 150)
```

The custom Oracle UCP provider would automatically override CommonsPool2XAProvider (priority 100) for all XA connections.

## Consequences

### Positive

1. **Pluggable Architecture**: Connection pool implementations are completely decoupled from OJP core
2. **Zero Recompilation**: Custom providers can be added by dropping JARs in `ojp-libs`
3. **Multiple Implementations**: HikariCP and DBCP coexist; customers can add C3P0, Tomcat JDBC, etc.
4. **Database-Specific Optimization**: Vendors can provide optimized implementations (e.g., Oracle UCP, SQL Server connection pools)
5. **Graceful Degradation**: If preferred provider is unavailable, OJP falls back to next available provider
6. **Clean Separation**: Pool statistics, lifecycle management, and configuration are abstracted
7. **Testing Flexibility**: Mock providers can be used for testing with higher priority
8. **Version Independence**: Driver and pool versions can be upgraded without OJP changes
9. **Universal XA Support**: Single XA provider works with all databases via reflection (PostgreSQL, SQL Server, DB2, MySQL, MariaDB, Oracle)

### Negative

1. **Complexity**: Dynamic class loading and ServiceLoader mechanics add implementation complexity
2. **Debugging**: Provider selection issues may be harder to diagnose than compile-time errors
3. **Documentation Burden**: Developers need clear guidance on implementing and registering providers
4. **Priority Management**: Customers must understand priority system to ensure correct provider selection
5. **Reflection Overhead**: XA provider uses reflection for zero-dependency configuration (minimal performance impact)

### Mitigations

1. **Clear Documentation**: Created comprehensive guide (`Understanding-OJP-SPIs.md`) with examples
2. **Helpful Logging**: Both registries log provider discovery, selection, and priority
3. **Fail-Fast Validation**: `isAvailable()` prevents broken providers from being selected
4. **Default Providers**: HikariCP and CommonsPool2XA work out-of-box without configuration
5. **Example Implementations**: HikariCP and DBCP providers serve as reference implementations

## Alternatives Considered

### 1. Hard-coded to HikariCP
**Rejected**: Would prevent customers from using alternative pools or database-specific optimizations

### 2. Configuration-based Provider Selection
**Rejected**: Requires manual configuration; ServiceLoader provides automatic discovery

### 3. Dependency Injection Framework (Spring, Guice)
**Rejected**: Adds unnecessary dependencies; ServiceLoader is built into Java SE

### 4. Factory Pattern with Registry
**Rejected**: Requires code changes to add providers; SPI allows external JARs

### 5. OSGi or JPMs Modules
**Rejected**: Too heavyweight; ServiceLoader provides sufficient modularity

## Related Decisions

- **ADR-003**: Use HikariCP as the Connection Pool - Now implemented as default SPI provider
- **External Driver Loading**: Enables SPI provider deployment via `ojp-libs` directory
- **Reflection-based XA Configuration**: Eliminates vendor dependencies in CommonsPool2XAProvider

## References

- [Understanding OJP SPIs](../Understanding-OJP-SPIs.md) - Complete implementation guide
- [ConnectionPoolProvider Interface](../../ojp-datasource-api/src/main/java/org/openjproxy/datasource/ConnectionPoolProvider.java)
- [XAConnectionPoolProvider Interface](../../ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/spi/XAConnectionPoolProvider.java)
- [Java ServiceLoader Documentation](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html)

| Status      | APPROVED        |  
|-------------|-----------------| 
| Proposer(s) | Rogerio Robetti | 
| Proposal date | 01/08/2025      | 
| Approver(s) | Rogerio Robetti |
| Approval date | 01/08/2025      |
