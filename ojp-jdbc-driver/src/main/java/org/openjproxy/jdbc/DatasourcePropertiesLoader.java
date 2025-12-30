package org.openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Utility class for loading datasource-specific properties from ojp.properties file.
 * Shared by Driver, OjpXADataSource, and MultinodeConnectionManager to avoid code duplication.
 */
@Slf4j
public class DatasourcePropertiesLoader {

    /**
     * Load ojp.properties and extract configuration specific to the given dataSource.
     * System properties (-D flags) take precedence over properties file values.
     * 
     * @param dataSourceName The datasource name to load properties for
     * @return Properties for the specified datasource, or null if none found
     */
    public static Properties loadOjpPropertiesForDataSource(String dataSourceName) {
        Properties allProperties = loadOjpProperties();
        if (allProperties == null || allProperties.isEmpty()) {
            return null;
        }
        
        Properties dataSourceProperties = new Properties();
        
        // Look for dataSource-prefixed properties first: {dataSourceName}.ojp.connection.pool.*
        // Also look for XA-specific properties: {dataSourceName}.ojp.xa.connection.pool.*
        String poolPrefix = dataSourceName + ".ojp.connection.pool.";
        String xaPoolPrefix = dataSourceName + ".ojp.xa.connection.pool.";
        String xaPrefix = dataSourceName + ".ojp.xa.";
        boolean foundDataSourceSpecific = false;
        
        for (String key : allProperties.stringPropertyNames()) {
            if (key.startsWith(poolPrefix) || key.startsWith(xaPoolPrefix) || key.startsWith(xaPrefix)) {
                // Remove the dataSource prefix and keep the standard property name
                String standardKey = key.substring(dataSourceName.length() + 1); // Remove "{dataSourceName}."
                dataSourceProperties.setProperty(standardKey, allProperties.getProperty(key));
                foundDataSourceSpecific = true;
            }
        }
        
        // If no dataSource-specific properties found, and this is the "default" dataSource,
        // look for unprefixed properties: ojp.connection.pool.*, ojp.xa.connection.pool.*, ojp.xa.*
        if (!foundDataSourceSpecific && "default".equals(dataSourceName)) {
            for (String key : allProperties.stringPropertyNames()) {
                if (key.startsWith("ojp.connection.pool.") || 
                    key.startsWith("ojp.xa.connection.pool.") || 
                    key.startsWith("ojp.xa.")) {
                    dataSourceProperties.setProperty(key, allProperties.getProperty(key));
                }
            }
        }
        
        // Merge system properties - they take precedence over file properties
        // Check for datasource-specific system properties first
        Properties systemProps = System.getProperties();
        for (String key : systemProps.stringPropertyNames()) {
            if (key.startsWith(poolPrefix) || key.startsWith(xaPoolPrefix) || key.startsWith(xaPrefix)) {
                // Remove the dataSource prefix and keep the standard property name
                String standardKey = key.substring(dataSourceName.length() + 1);
                dataSourceProperties.setProperty(standardKey, systemProps.getProperty(key));
                foundDataSourceSpecific = true;
                log.debug("Overriding property from system property: {} = {}", standardKey, systemProps.getProperty(key));
            }
        }
        
        // For "default" datasource, also check unprefixed system properties
        if ("default".equals(dataSourceName)) {
            for (String key : systemProps.stringPropertyNames()) {
                if (key.startsWith("ojp.connection.pool.") || 
                    key.startsWith("ojp.xa.connection.pool.") || 
                    key.startsWith("ojp.xa.")) {
                    dataSourceProperties.setProperty(key, systemProps.getProperty(key));
                    log.debug("Overriding property from system property: {} = {}", key, systemProps.getProperty(key));
                }
            }
        }
        
        // If we found any properties, also include the dataSource name as a single property
        // Note: The dataSource-prefixed properties (e.g., "webApp.ojp.connection.pool.*") 
        // are sent to the server with their prefixes removed (e.g., "ojp.connection.pool.*"),
        // and the dataSource name itself is sent separately as "ojp.datasource.name"
        if (!dataSourceProperties.isEmpty()) {
            dataSourceProperties.setProperty("ojp.datasource.name", dataSourceName);
        }
        
        log.debug("Loaded {} properties for dataSource '{}': {}", 
                dataSourceProperties.size(), dataSourceName, dataSourceProperties);
        
        return dataSourceProperties.isEmpty() ? null : dataSourceProperties;
    }

    /**
     * Load the raw ojp.properties file from classpath.
     * 
     * @return All properties from ojp.properties file, or null if not found
     */
    public static Properties loadOjpProperties() {
        Properties properties = new Properties();
        
        // Only try to load from resources/ojp.properties in the classpath
        try (InputStream is = DatasourcePropertiesLoader.class.getClassLoader().getResourceAsStream("ojp.properties")) {
            if (is != null) {
                properties.load(is);
                log.debug("Loaded ojp.properties from resources folder");
                return properties;
            }
        } catch (IOException e) {
            log.debug("Could not load ojp.properties from resources folder: {}", e.getMessage());
        }
        
        log.debug("No ojp.properties file found, using server defaults");
        return null;
    }
}
