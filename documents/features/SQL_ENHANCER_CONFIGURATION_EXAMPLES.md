# SQL Enhancer Configuration Examples

This document provides practical examples for configuring the SQL Enhancer Engine in OJP.

## Table of Contents

1. [Basic Configuration](#basic-configuration)
2. [Mode-Based Configuration](#mode-based-configuration)
3. [Dialect-Specific Configuration](#dialect-specific-configuration)
4. [Optimization Rules Configuration](#optimization-rules-configuration)
5. [Performance Tuning](#performance-tuning)
6. [Environment-Specific Examples](#environment-specific-examples)

---

## Basic Configuration

### Disable SQL Enhancer (Default)

```properties
# SQL enhancer is disabled by default for safety
ojp.sql.enhancer.enabled=false
```

### Enable SQL Enhancer with Defaults

```properties
# Enable with default settings (VALIDATE mode, GENERIC dialect)
ojp.sql.enhancer.enabled=true
```

This will:
- Enable SQL syntax validation
- Use GENERIC SQL dialect
- Not perform query optimization
- Cache validation results

---

## Mode-Based Configuration

### VALIDATE Mode (Default)

Validates SQL syntax without modifying queries. Fastest mode with minimal overhead.

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=VALIDATE
```

**Use Case:** Catch SQL syntax errors early in development or staging environments.

**Behavior:**
- Validates SQL syntax
- No query modifications
- Minimal performance overhead (<5ms per unique query)

### OPTIMIZE Mode (Recommended for Production)

Validates and optimizes queries using safe transformation rules.

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
ojp.sql.enhancer.dialect=POSTGRESQL
```

**Use Case:** Production environments where query optimization is desired.

**Behavior:**
- Validates SQL syntax
- Applies safe optimization rules
- Moderate performance overhead (20-50ms per unique query, then cached)
- Can improve query performance by 10-30%

### TRANSLATE Mode (For Database Migrations)

Supports dialect translation in addition to validation and optimization.

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=TRANSLATE
ojp.sql.enhancer.dialect=MYSQL
```

**Use Case:** Database migration projects or multi-database environments.

**Behavior:**
- Full validation and optimization
- Enables dialect translation API
- Highest performance overhead

### ANALYZE Mode (For Monitoring)

Analyzes queries and extracts metadata without modification.

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=ANALYZE
```

**Use Case:** Query pattern analysis, monitoring, and observability.

**Behavior:**
- Validates SQL
- Extracts query metadata
- No query modifications
- Useful for monitoring tools

---

## Dialect-Specific Configuration

### PostgreSQL

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
ojp.sql.enhancer.dialect=POSTGRESQL
ojp.sql.enhancer.logOptimizations=true
```

### MySQL

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
ojp.sql.enhancer.dialect=MYSQL
ojp.sql.enhancer.rules=FILTER_REDUCE,PROJECT_REDUCE,FILTER_MERGE,PROJECT_MERGE
```

### Oracle

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
ojp.sql.enhancer.dialect=ORACLE
ojp.sql.enhancer.optimizationTimeout=150
```

### SQL Server

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
ojp.sql.enhancer.dialect=SQL_SERVER
```

### H2 (Testing)

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=VALIDATE
ojp.sql.enhancer.dialect=H2
```

---

## Optimization Rules Configuration

### Safe Rules Only (Default)

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
# Empty rules = use safe defaults
ojp.sql.enhancer.rules=
```

**Safe Rules:**
- `FILTER_REDUCE` - Simplifies filter expressions, constant folding
- `PROJECT_REDUCE` - Simplifies projection expressions
- `FILTER_MERGE` - Merges consecutive filter operations
- `PROJECT_MERGE` - Merges consecutive projection operations
- `PROJECT_REMOVE` - Removes unnecessary projections

### Conservative Rules

For applications that need minimal risk:

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
ojp.sql.enhancer.rules=FILTER_REDUCE,PROJECT_REDUCE
```

### Aggressive Optimization

For performance-critical applications:

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
ojp.sql.enhancer.rules=FILTER_REDUCE,PROJECT_REDUCE,FILTER_MERGE,PROJECT_MERGE,PROJECT_REMOVE,FILTER_INTO_JOIN,JOIN_COMMUTE
```

**Aggressive Rules:**
- `FILTER_INTO_JOIN` - Pushes filters into join operations (predicate pushdown)
- `JOIN_COMMUTE` - Reorders joins for better performance

**Warning:** Aggressive rules may cause issues with complex queries. Test thoroughly.

### Custom Rule Set

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
# Only use specific rules
ojp.sql.enhancer.rules=FILTER_REDUCE,FILTER_MERGE,PROJECT_REMOVE
```

---

## Performance Tuning

### Low Latency Configuration

Minimize overhead for high-throughput applications:

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=VALIDATE
ojp.sql.enhancer.cacheEnabled=true
ojp.sql.enhancer.cacheSize=5000
ojp.sql.enhancer.logOptimizations=false
```

### High Optimization Configuration

Maximize query optimization:

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
ojp.sql.enhancer.optimizationTimeout=200
ojp.sql.enhancer.cacheEnabled=true
ojp.sql.enhancer.cacheSize=10000
ojp.sql.enhancer.rules=FILTER_REDUCE,PROJECT_REDUCE,FILTER_MERGE,PROJECT_MERGE,PROJECT_REMOVE,FILTER_INTO_JOIN
```

### Disable Caching (Not Recommended)

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
ojp.sql.enhancer.cacheEnabled=false
```

**Note:** Disabling caching will significantly increase overhead (100-200ms per query).

### Custom Cache Size

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
ojp.sql.enhancer.cacheSize=2000
```

---

## Environment-Specific Examples

### Development Environment

Focus on catching errors early with minimal overhead:

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=VALIDATE
ojp.sql.enhancer.dialect=POSTGRESQL
ojp.sql.enhancer.logOptimizations=true
ojp.sql.enhancer.failOnValidationError=true
```

### Staging Environment

Test optimization with logging:

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
ojp.sql.enhancer.dialect=POSTGRESQL
ojp.sql.enhancer.logOptimizations=true
ojp.sql.enhancer.rules=FILTER_REDUCE,PROJECT_REDUCE,FILTER_MERGE,PROJECT_MERGE,PROJECT_REMOVE
```

### Production Environment

Optimize queries with minimal logging:

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
ojp.sql.enhancer.dialect=POSTGRESQL
ojp.sql.enhancer.logOptimizations=false
ojp.sql.enhancer.optimizationTimeout=100
ojp.sql.enhancer.cacheEnabled=true
ojp.sql.enhancer.cacheSize=5000
ojp.sql.enhancer.failOnValidationError=false
```

### Testing Environment (H2)

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=VALIDATE
ojp.sql.enhancer.dialect=H2
ojp.sql.enhancer.failOnValidationError=true
```

---

## Using Environment Variables

All properties can be set via environment variables by converting dots to underscores and uppercasing:

```bash
# Enable SQL enhancer
export OJP_SQL_ENHANCER_ENABLED=true

# Set mode to OPTIMIZE
export OJP_SQL_ENHANCER_MODE=OPTIMIZE

# Set dialect to PostgreSQL
export OJP_SQL_ENHANCER_DIALECT=POSTGRESQL

# Configure optimization timeout
export OJP_SQL_ENHANCER_OPTIMIZATION_TIMEOUT=150

# Set cache size
export OJP_SQL_ENHANCER_CACHE_SIZE=5000
```

## Using JVM System Properties

```bash
java -jar ojp-server.jar \
  -Dojp.sql.enhancer.enabled=true \
  -Dojp.sql.enhancer.mode=OPTIMIZE \
  -Dojp.sql.enhancer.dialect=POSTGRESQL \
  -Dojp.sql.enhancer.optimizationTimeout=150 \
  -Dojp.sql.enhancer.cacheSize=5000
```

---

## Configuration Precedence

Configuration values are loaded in the following order (highest to lowest priority):

1. **JVM System Properties** (`-Dojp.sql.enhancer.mode=OPTIMIZE`)
2. **Environment Variables** (`OJP_SQL_ENHANCER_MODE=OPTIMIZE`)
3. **ojp.properties file** (`ojp.sql.enhancer.mode=OPTIMIZE`)
4. **Default values** (hardcoded in ServerConfiguration)

---

## Monitoring and Observability

### Enable Optimization Logging

```properties
ojp.sql.enhancer.enabled=true
ojp.sql.enhancer.mode=OPTIMIZE
ojp.sql.enhancer.logOptimizations=true
```

This logs:
- When SQL is modified
- Original vs optimized SQL
- Optimization time
- Applied rules

### Check Cache Statistics

The engine exposes cache statistics via logging at startup and runtime.

---

## Troubleshooting

### SQL Validation Errors

If queries fail validation:

```properties
# Disable fail-fast to allow queries to pass through
ojp.sql.enhancer.failOnValidationError=false
```

### Performance Issues

If enhancement adds too much overhead:

```properties
# Use VALIDATE mode instead of OPTIMIZE
ojp.sql.enhancer.mode=VALIDATE

# Or reduce optimization timeout
ojp.sql.enhancer.optimizationTimeout=50

# Or use fewer rules
ojp.sql.enhancer.rules=FILTER_REDUCE,PROJECT_REDUCE
```

### Optimization Causing Issues

If optimized queries fail:

```properties
# Switch to VALIDATE mode
ojp.sql.enhancer.mode=VALIDATE

# Or use only safe rules
ojp.sql.enhancer.rules=FILTER_REDUCE,PROJECT_REDUCE
```

---

## Best Practices

1. **Start with VALIDATE mode** in development
2. **Test with OPTIMIZE mode** in staging
3. **Monitor performance** before enabling in production
4. **Use dialect-specific settings** for best results
5. **Start with safe rules** and add aggressive rules incrementally
6. **Enable caching** for better performance
7. **Set appropriate timeouts** based on query complexity
8. **Monitor logs** to understand optimization impact

---

## Migration from `validationOnly` Flag

**Note:** The `ojp.sql.enhancer.validationOnly` flag was considered but **not implemented** as it is redundant with the `mode` property.

If you were planning to use `validationOnly`:

```properties
# OLD (not implemented):
# ojp.sql.enhancer.validationOnly=true

# NEW (recommended):
ojp.sql.enhancer.mode=VALIDATE
```

The `mode` property provides more flexibility with four distinct modes rather than a single validation-only flag.

---

## Complete Configuration Reference

Here's a complete example with all available properties:

```properties
# Enable/disable SQL enhancer (default: false)
ojp.sql.enhancer.enabled=true

# Enhancement mode: VALIDATE, OPTIMIZE, TRANSLATE, ANALYZE (default: VALIDATE)
ojp.sql.enhancer.mode=OPTIMIZE

# Database dialect: GENERIC, POSTGRESQL, MYSQL, ORACLE, SQL_SERVER, H2 (default: GENERIC)
ojp.sql.enhancer.dialect=POSTGRESQL

# Log enhanced queries (default: true)
ojp.sql.enhancer.logOptimizations=true

# Comma-separated optimization rules (default: empty = safe rules)
ojp.sql.enhancer.rules=FILTER_REDUCE,PROJECT_REDUCE,FILTER_MERGE,PROJECT_MERGE,PROJECT_REMOVE

# Maximum time to spend on optimization per query in milliseconds (default: 100)
ojp.sql.enhancer.optimizationTimeout=100

# Enable query caching (default: true)
ojp.sql.enhancer.cacheEnabled=true

# Cache size limit (default: 1000)
ojp.sql.enhancer.cacheSize=1000

# Fail on validation errors (default: true)
ojp.sql.enhancer.failOnValidationError=true
```

---

For more information, see:
- [SQL_ENHANCER_ENGINE_ANALYSIS.md](../../analysis/sql_enhancer/SQL_ENHANCER_ENGINE_ANALYSIS.md)
- [SQL_ENHANCER_ENGINE_QUICKSTART.md](SQL_ENHANCER_ENGINE_QUICKSTART.md)
