package openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration tests for SQL session affinity feature with PostgreSQL database.
 * Tests that temporary tables and session variables work correctly across
 * multiple requests by ensuring session affinity.
 */
@Slf4j
public class PostgresSessionAffinityIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    /**
     * Tests that temporary tables work across multiple SQL statements.
     * This verifies that CREATE TEMPORARY TABLE triggers session affinity
     * and subsequent operations use the same session/connection.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testTemporaryTableSessionAffinity(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "PostgreSQL tests are disabled");

        log.info("Testing temporary table session affinity for PostgreSQL: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Drop temp table if it exists (cleanup from previous run)
            try {
                stmt.execute("DROP TABLE IF EXISTS temp_session_test");
            } catch (SQLException e) {
                // Ignore - table might not exist
            }

            // Create temporary table (this should trigger session affinity)
            log.debug("Creating PostgreSQL temporary table");
            stmt.execute("CREATE TEMPORARY TABLE temp_session_test (id INT, value VARCHAR(100))");

            // Insert data into temporary table (should use same session)
            log.debug("Inserting data into temporary table");
            stmt.execute("INSERT INTO temp_session_test VALUES (1, 'test_value')");

            // Query temporary table (should use same session)
            log.debug("Querying temporary table");
            ResultSet rs = stmt.executeQuery("SELECT id, value FROM temp_session_test");
            
            // Verify data was inserted and retrieved successfully
            Assert.assertTrue("Should have at least one row in temporary table", rs.next());
            Assert.assertEquals("Session data should match", 1, rs.getInt("id"));
            Assert.assertEquals("Session data should match", "test_value", rs.getString("value"));
            
            // Verify no more rows
            Assert.assertFalse("Should have only one row", rs.next());
            
            rs.close();
            
            log.info("PostgreSQL temporary table session affinity test passed");

        } finally {
            conn.close();
        }
    }

    /**
     * Tests session variables for PostgreSQL using SET LOCAL.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testSessionVariableAffinity(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "PostgreSQL tests are disabled");

        log.info("Testing PostgreSQL session variable affinity: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try {
            conn.setAutoCommit(false);
            
            try (Statement stmt = conn.createStatement()) {
                // Set session variable using SET LOCAL (this should trigger session affinity)
                log.debug("Setting PostgreSQL session variable with SET LOCAL");
                stmt.execute("SET LOCAL work_mem = '4MB'");

                // Query the variable (should use same session)
                log.debug("Querying PostgreSQL session variable");
                ResultSet rs = stmt.executeQuery("SHOW work_mem");
                
                // Verify variable value was preserved
                Assert.assertTrue("Should return session variable value", rs.next());
                String workMem = rs.getString(1);
                Assert.assertTrue("work_mem should be 4MB or 4096kB format", 
                                workMem.contains("4MB") || workMem.contains("4096"));
                
                rs.close();
                
                log.info("PostgreSQL session variable affinity test passed");
            }
            
            conn.commit();
        } finally {
            conn.close();
        }
    }

    /**
     * Tests that multiple temporary table operations work correctly.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testComplexTemporaryTableOperations(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "PostgreSQL tests are disabled");

        log.info("Testing complex temporary table operations for PostgreSQL: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Cleanup
            try {
                stmt.execute("DROP TABLE IF EXISTS temp_complex");
            } catch (SQLException e) {
                // Ignore
            }

            // Create temporary table
            log.debug("Creating complex temp table");
            stmt.execute("CREATE TEMPORARY TABLE temp_complex (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(10,2))");

            // Insert multiple rows
            log.debug("Inserting multiple rows");
            stmt.execute("INSERT INTO temp_complex VALUES (1, 'Alice', 100.50)");
            stmt.execute("INSERT INTO temp_complex VALUES (2, 'Bob', 200.75)");
            stmt.execute("INSERT INTO temp_complex VALUES (3, 'Charlie', 150.25)");

            // Update a row
            log.debug("Updating a row");
            stmt.executeUpdate("UPDATE temp_complex SET amount = amount + 50.00 WHERE id = 2");

            // Query and verify
            log.debug("Querying temp table");
            ResultSet rs = stmt.executeQuery("SELECT id, name, amount FROM temp_complex ORDER BY id");
            
            int rowCount = 0;
            while (rs.next()) {
                rowCount++;
                int id = rs.getInt("id");
                String name = rs.getString("name");
                double amount = rs.getDouble("amount");
                
                if (id == 1) {
                    Assert.assertEquals("Alice", name);
                    Assert.assertEquals(100.50, amount, 0.01);
                } else if (id == 2) {
                    Assert.assertEquals("Bob", name);
                    Assert.assertEquals(250.75, amount, 0.01); // Updated
                } else if (id == 3) {
                    Assert.assertEquals("Charlie", name);
                    Assert.assertEquals(150.25, amount, 0.01);
                }
            }
            
            Assert.assertEquals("Should have 3 rows", 3, rowCount);
            rs.close();
            
            log.info("PostgreSQL complex temporary table operations test passed");

        } finally {
            conn.close();
        }
    }

    /**
     * Tests that temp table persists within same session across transactions.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testTemporaryTablePersistenceAcrossTransactions(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "PostgreSQL tests are disabled");

        log.info("Testing temporary table persistence across transactions for PostgreSQL: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Cleanup
            try {
                stmt.execute("DROP TABLE IF EXISTS temp_persist");
            } catch (SQLException e) {
                // Ignore
            }

            // Create temp table
            stmt.execute("CREATE TEMPORARY TABLE temp_persist (id INT, data VARCHAR(100))");

            // Start transaction and insert
            conn.setAutoCommit(false);
            stmt.execute("INSERT INTO temp_persist VALUES (1, 'in_transaction')");
            conn.commit();

            // Start another transaction and query (should still see the temp table)
            conn.setAutoCommit(false);
            ResultSet rs = stmt.executeQuery("SELECT * FROM temp_persist WHERE id = 1");
            Assert.assertTrue("Should find row inserted in previous transaction", rs.next());
            Assert.assertEquals("Data should match", "in_transaction", rs.getString("data"));
            rs.close();
            conn.commit();

            log.info("PostgreSQL temporary table persistence across transactions test passed");

        } finally {
            conn.close();
        }
    }
}
