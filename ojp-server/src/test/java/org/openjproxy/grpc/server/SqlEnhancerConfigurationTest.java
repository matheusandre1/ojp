package org.openjproxy.grpc.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for SQL Enhancer configuration properties.
 */
public class SqlEnhancerConfigurationTest {
    
    private static final String MODE_PROPERTY = "ojp.sql.enhancer.mode";
    private static final String DIALECT_PROPERTY = "ojp.sql.enhancer.dialect";
    private static final String RULES_PROPERTY = "ojp.sql.enhancer.rules";
    private static final String OPTIMIZATION_TIMEOUT_PROPERTY = "ojp.sql.enhancer.optimizationTimeout";
    private static final String LOG_OPTIMIZATIONS_PROPERTY = "ojp.sql.enhancer.logOptimizations";
    private static final String CACHE_ENABLED_PROPERTY = "ojp.sql.enhancer.cacheEnabled";
    private static final String CACHE_SIZE_PROPERTY = "ojp.sql.enhancer.cacheSize";
    private static final String FAIL_ON_VALIDATION_ERROR_PROPERTY = "ojp.sql.enhancer.failOnValidationError";
    
    @BeforeEach
    public void setUp() {
        // Clear any existing properties
        System.clearProperty(MODE_PROPERTY);
        System.clearProperty(DIALECT_PROPERTY);
        System.clearProperty(RULES_PROPERTY);
        System.clearProperty(OPTIMIZATION_TIMEOUT_PROPERTY);
        System.clearProperty(LOG_OPTIMIZATIONS_PROPERTY);
        System.clearProperty(CACHE_ENABLED_PROPERTY);
        System.clearProperty(CACHE_SIZE_PROPERTY);
        System.clearProperty(FAIL_ON_VALIDATION_ERROR_PROPERTY);
    }
    
    @AfterEach
    public void tearDown() {
        // Clean up
        System.clearProperty(MODE_PROPERTY);
        System.clearProperty(DIALECT_PROPERTY);
        System.clearProperty(RULES_PROPERTY);
        System.clearProperty(OPTIMIZATION_TIMEOUT_PROPERTY);
        System.clearProperty(LOG_OPTIMIZATIONS_PROPERTY);
        System.clearProperty(CACHE_ENABLED_PROPERTY);
        System.clearProperty(CACHE_SIZE_PROPERTY);
        System.clearProperty(FAIL_ON_VALIDATION_ERROR_PROPERTY);
    }
    
    @Test
    public void testDefaultConfiguration() {
        ServerConfiguration config = new ServerConfiguration();
        
        // Verify defaults
        assertEquals("VALIDATE", config.getSqlEnhancerMode());
        assertEquals("GENERIC", config.getSqlEnhancerDialect());
        assertEquals("", config.getSqlEnhancerRules());
        assertEquals(100, config.getSqlEnhancerOptimizationTimeout());
        assertTrue(config.isSqlEnhancerLogOptimizations());
        assertTrue(config.isSqlEnhancerCacheEnabled());
        assertEquals(1000, config.getSqlEnhancerCacheSize());
        assertTrue(config.isSqlEnhancerFailOnValidationError());
    }
    
    @Test
    public void testCustomModeConfiguration() {
        System.setProperty(MODE_PROPERTY, "OPTIMIZE");
        ServerConfiguration config = new ServerConfiguration();
        
        assertEquals("OPTIMIZE", config.getSqlEnhancerMode());
    }
    
    @Test
    public void testCustomDialectConfiguration() {
        System.setProperty(DIALECT_PROPERTY, "POSTGRESQL");
        ServerConfiguration config = new ServerConfiguration();
        
        assertEquals("POSTGRESQL", config.getSqlEnhancerDialect());
    }
    
    @Test
    public void testCustomRulesConfiguration() {
        System.setProperty(RULES_PROPERTY, "FILTER_REDUCE,PROJECT_MERGE");
        ServerConfiguration config = new ServerConfiguration();
        
        assertEquals("FILTER_REDUCE,PROJECT_MERGE", config.getSqlEnhancerRules());
    }
    
    @Test
    public void testCustomOptimizationTimeoutConfiguration() {
        System.setProperty(OPTIMIZATION_TIMEOUT_PROPERTY, "200");
        ServerConfiguration config = new ServerConfiguration();
        
        assertEquals(200, config.getSqlEnhancerOptimizationTimeout());
    }
    
    @Test
    public void testCustomLogOptimizationsConfiguration() {
        System.setProperty(LOG_OPTIMIZATIONS_PROPERTY, "false");
        ServerConfiguration config = new ServerConfiguration();
        
        assertFalse(config.isSqlEnhancerLogOptimizations());
    }
    
    @Test
    public void testCustomCacheConfiguration() {
        System.setProperty(CACHE_ENABLED_PROPERTY, "false");
        System.setProperty(CACHE_SIZE_PROPERTY, "500");
        ServerConfiguration config = new ServerConfiguration();
        
        assertFalse(config.isSqlEnhancerCacheEnabled());
        assertEquals(500, config.getSqlEnhancerCacheSize());
    }
    
    @Test
    public void testCustomFailOnValidationErrorConfiguration() {
        System.setProperty(FAIL_ON_VALIDATION_ERROR_PROPERTY, "false");
        ServerConfiguration config = new ServerConfiguration();
        
        assertFalse(config.isSqlEnhancerFailOnValidationError());
    }
    
    @Test
    public void testAllCustomConfiguration() {
        System.setProperty(MODE_PROPERTY, "TRANSLATE");
        System.setProperty(DIALECT_PROPERTY, "MYSQL");
        System.setProperty(RULES_PROPERTY, "FILTER_REDUCE,PROJECT_REDUCE,FILTER_MERGE");
        System.setProperty(OPTIMIZATION_TIMEOUT_PROPERTY, "150");
        System.setProperty(LOG_OPTIMIZATIONS_PROPERTY, "false");
        System.setProperty(CACHE_ENABLED_PROPERTY, "true");
        System.setProperty(CACHE_SIZE_PROPERTY, "2000");
        System.setProperty(FAIL_ON_VALIDATION_ERROR_PROPERTY, "false");
        
        ServerConfiguration config = new ServerConfiguration();
        
        assertEquals("TRANSLATE", config.getSqlEnhancerMode());
        assertEquals("MYSQL", config.getSqlEnhancerDialect());
        assertEquals("FILTER_REDUCE,PROJECT_REDUCE,FILTER_MERGE", config.getSqlEnhancerRules());
        assertEquals(150, config.getSqlEnhancerOptimizationTimeout());
        assertFalse(config.isSqlEnhancerLogOptimizations());
        assertTrue(config.isSqlEnhancerCacheEnabled());
        assertEquals(2000, config.getSqlEnhancerCacheSize());
        assertFalse(config.isSqlEnhancerFailOnValidationError());
    }
}
