package openjproxy.jdbc.testutil;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import java.util.stream.Stream;

/**
 * Custom ArgumentsProvider for SQL Server integration tests.
 * Provides connection details from TestContainers when SQL Server tests are enabled.
 * This allows tests to use TestContainers instead of external SQL Server instances.
 */
public class SQLServerConnectionProvider implements ArgumentsProvider {
    
    // OJP proxy server configuration - can be overridden via system property
    private static final String OJP_PROXY_HOST = System.getProperty("ojp.proxy.host", "localhost");
    private static final String OJP_PROXY_PORT = System.getProperty("ojp.proxy.port", "1059");
    private static final String OJP_PROXY_ADDRESS = OJP_PROXY_HOST + ":" + OJP_PROXY_PORT;
    
    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
        if (!SQLServerTestContainer.isEnabled()) {
            // Return empty stream when tests are disabled
            return Stream.empty();
        }
        
        // Initialize and start the TestContainer
        SQLServerTestContainer.getInstance();
        
        // Get connection details from the TestContainer
        String containerJdbcUrl = SQLServerTestContainer.getJdbcUrl();
        String username = SQLServerTestContainer.getUsername();
        String password = SQLServerTestContainer.getPassword();
        
        // Build OJP JDBC URL from the container URL
        // TestContainer URL format: jdbc:sqlserver://localhost:RANDOM_PORT;encrypt=false;...
        // We need to extract the connection string and wrap it with OJP format
        // OJP format: jdbc:ojp[localhost:1059]_sqlserver://...
        String driverClass = "org.openjproxy.jdbc.Driver";
        String ojpUrl = "jdbc:ojp[" + OJP_PROXY_ADDRESS + "]_" + containerJdbcUrl.substring(5); // Remove "jdbc:" prefix
        
        // Return a single set of arguments with the TestContainer connection details
        return Stream.of(
            Arguments.of(driverClass, ojpUrl, username, password)
        );
    }
}
