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
 * Integration tests for SQL session affinity feature with Oracle database.
 * Tests that global temporary tables work correctly across multiple requests
 * by ensuring session affinity.
 */
@Slf4j
public class OracleSessionAffinityIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    /**
     * Tests that global temporary tables work across multiple SQL statements.
     * This verifies that CREATE GLOBAL TEMPORARY TABLE triggers session affinity
     * and subsequent operations use the same session/connection.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    public void testTemporaryTableSessionAffinity(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");

        log.info("Testing temporary table session affinity for Oracle: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Oracle global temp tables are permanent, just truncate
            try {
                stmt.execute("TRUNCATE TABLE temp_session_test");
            } catch (SQLException e) {
                // Table doesn't exist, will create it
                try {
                    log.debug("Creating Oracle global temporary table");
                    stmt.execute("CREATE GLOBAL TEMPORARY TABLE temp_session_test (id INT, value VARCHAR2(100)) ON COMMIT PRESERVE ROWS");
                } catch (SQLException ex) {
                    // Might already exist from another session, just truncate
                    stmt.execute("TRUNCATE TABLE temp_session_test");
                }
            }

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
            
            log.info("Oracle temporary table session affinity test passed");

        } finally {
            // Cleanup
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("TRUNCATE TABLE temp_session_test");
            } catch (SQLException e) {
                log.warn("Error during cleanup: {}", e.getMessage());
            }
            conn.close();
        }
    }

    /**
     * Tests that multiple temporary table operations work correctly.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    public void testComplexTemporaryTableOperations(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");

        log.info("Testing complex temporary table operations for Oracle: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Oracle global temp tables are permanent, just truncate
            try {
                stmt.execute("TRUNCATE TABLE temp_complex");
            } catch (SQLException e) {
                // Table doesn't exist, create it
                try {
                    log.debug("Creating complex temp table");
                    stmt.execute("CREATE GLOBAL TEMPORARY TABLE temp_complex (id INT PRIMARY KEY, name VARCHAR2(100), amount DECIMAL(10,2)) ON COMMIT PRESERVE ROWS");
                } catch (SQLException ex) {
                    // Might already exist, just truncate
                    stmt.execute("TRUNCATE TABLE temp_complex");
                }
            }

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
            
            log.info("Oracle complex temporary table operations test passed");

        } finally {
            // Cleanup
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("TRUNCATE TABLE temp_complex");
            } catch (SQLException e) {
                log.warn("Error during cleanup: {}", e.getMessage());
            }
            conn.close();
        }
    }

    /**
     * Tests that temp table persists within same session across transactions.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    public void testTemporaryTablePersistenceAcrossTransactions(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");

        log.info("Testing temporary table persistence across transactions for Oracle: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Oracle global temp tables are permanent, just truncate
            try {
                stmt.execute("TRUNCATE TABLE temp_persist");
            } catch (SQLException e) {
                // Table doesn't exist, create it
                try {
                    stmt.execute("CREATE GLOBAL TEMPORARY TABLE temp_persist (id INT, data VARCHAR2(100)) ON COMMIT PRESERVE ROWS");
                } catch (SQLException ex) {
                    // Might already exist, just truncate
                    stmt.execute("TRUNCATE TABLE temp_persist");
                }
            }

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

            log.info("Oracle temporary table persistence across transactions test passed");

        } finally {
            // Cleanup
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("TRUNCATE TABLE temp_persist");
            } catch (SQLException e) {
                log.warn("Error during cleanup: {}", e.getMessage());
            }
            conn.close();
        }
    }
}
