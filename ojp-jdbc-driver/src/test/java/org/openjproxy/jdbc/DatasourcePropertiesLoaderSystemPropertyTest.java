package org.openjproxy.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that system properties override file properties in DatasourcePropertiesLoader
 */
public class DatasourcePropertiesLoaderSystemPropertyTest {

    @AfterEach
    public void cleanup() {
        // Clear any system properties we set during tests
        System.clearProperty("multinode.ojp.connection.pool.enabled");
        System.clearProperty("ojp.connection.pool.enabled");
    }

    @Test
    public void testSystemPropertyOverridesFileProperty() {
        // Set a system property that should override the file property
        System.setProperty("multinode.ojp.connection.pool.enabled", "false");
        
        // Load properties for "multinode" datasource
        Properties props = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource("multinode");
        
        assertNotNull(props, "Properties should not be null");
        
        // The system property should override the file property
        String poolEnabled = props.getProperty("ojp.connection.pool.enabled");
        assertNotNull(poolEnabled, "Pool enabled property should be present");
        assertEquals("false", poolEnabled, "System property should override file property to 'false'");
    }

    @Test
    public void testDefaultDataSourceSystemPropertyOverride() {
        // Set a system property for the default datasource
        System.setProperty("ojp.connection.pool.enabled", "false");
        
        // Load properties for "default" datasource
        Properties props = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource("default");
        
        // The system property should be present
        String poolEnabled = props.getProperty("ojp.connection.pool.enabled");
        assertNotNull(poolEnabled, "Pool enabled property should be present");
        assertEquals("false", poolEnabled, "System property should set pool enabled to 'false'");
    }
}
