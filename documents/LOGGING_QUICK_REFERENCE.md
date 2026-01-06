# Quick Reference: Logging Changes

## Problem
When integrating OJP JDBC driver with Spring Boot, a conflict occurred:
- OJP bundled SLF4J Simple in the shaded JAR
- Spring Boot uses Logback by default
- Result: `IllegalStateException` about multiple SLF4J providers

## Solution Summary

### Library Modules (ojp-jdbc-driver, ojp-grpc-commons, ojp-datasource-*)
**Change**: `slf4j-api` scope from `compile` → `provided`

**Effect**: 
- SLF4J API not bundled in shaded JAR
- Applications provide their own logging implementation
- No more conflicts with Spring Boot or other frameworks

### Application Module (ojp-server)
**Change**: `slf4j-simple` → `logback-classic`

**Effect**:
- Better logging features (file rotation, customizable patterns)
- Aligns with Spring Boot's default
- Production-ready logging configuration

### Testing
**Change**: Added `slf4j-simple` with `test` scope

**Effect**:
- Tests have a logging implementation
- Production code doesn't bundle it

## Verification

### Before (0.3.1-beta and earlier)
```bash
jar -tf ojp-jdbc-driver-0.3.1-beta.jar | grep "org/slf4j/"
# Output: Contains SLF4J classes ❌
```

### After (0.3.2+)
```bash
jar -tf ojp-jdbc-driver-0.3.2-snapshot.jar | grep "^org/slf4j/"
# Output: Empty (no SLF4J classes) ✅
```

## For Users

### New Projects (0.3.2+)
Just add the dependency - it works:
```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>0.3.2</version>
</dependency>
```

### Upgrading from Older Versions
1. Update version to 0.3.2+
2. Remove any workarounds (JVM flags)
3. Done!

### Standalone Applications (Non-Spring Boot)
Add a logging implementation:
```xml
<!-- For Logback -->
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>1.5.16</version>
</dependency>

<!-- OR for Log4j2 -->
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-slf4j2-impl</artifactId>
    <version>2.x.x</version>
</dependency>
```

## Key Files Changed
- `pom.xml` - Added version properties
- `ojp-server/pom.xml` - Changed to Logback
- `ojp-jdbc-driver/pom.xml` - slf4j-api to provided
- `ojp-grpc-commons/pom.xml` - slf4j-api to provided
- `ojp-datasource-*/pom.xml` - slf4j-api to provided
- `ojp-server/src/main/resources/logback.xml` - Configurable Logback configuration
- `documents/java-frameworks/spring-boot/README.md` - Updated docs
- `documents/configuration/ojp-server-example.properties` - Added logging configuration examples

## Logging Configuration

### OJP Server Logging Options

The OJP Server now uses Logback with configurable options via system properties or environment variables:

| Property | Description | Default |
|----------|-------------|---------|
| `ojp.server.logLevel` | Root log level (DEBUG, INFO, WARN, ERROR) | INFO |
| `ojp.server.log.file` | Log file location | logs/ojp-server.log |
| `ojp.server.log.fileNamePattern` | Rolling file pattern | logs/ojp-server.%d{yyyy-MM-dd}.log |
| `ojp.server.log.maxHistory` | Number of days to keep logs | 30 |
| `ojp.server.log.totalSizeCap` | Total size cap for all logs | 1GB |
| `ojp.server.log.pattern` | Log message pattern | %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n |

### Configuration Examples

**Via System Properties:**
```bash
java -Dojp.server.logLevel=DEBUG \
     -Dojp.server.log.file=/var/log/ojp/server.log \
     -Dojp.server.log.maxHistory=60 \
     -Dojp.server.log.totalSizeCap=5GB \
     -jar ojp-server.jar
```

**Via Environment Variables:**
```bash
export ojp.server.logLevel=DEBUG
export ojp.server.log.file=/var/log/ojp/server.log
export ojp.server.log.maxHistory=60
export ojp.server.log.totalSizeCap=5GB
./run-server.sh
```

**Via ojp.properties file:**
```properties
# Logging Configuration
ojp.server.logLevel=DEBUG
ojp.server.log.file=/var/log/ojp/server.log
ojp.server.log.maxHistory=60
ojp.server.log.totalSizeCap=5GB
```

## Additional Resources
- Spring Boot guide: `documents/java-frameworks/spring-boot/README.md`
- Configuration examples: `documents/configuration/ojp-server-example.properties`
- SLF4J docs: http://www.slf4j.org/manual.html
- Logback docs: http://logback.qos.ch/documentation.html
