package openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration tests for SQL session affinity feature with DB2 database.
 * Tests that declared global temporary tables work correctly across multiple
 * requests by ensuring session affinity.
 * 
 * <p><strong>DB2-Specific Test Design:</strong></p>
 * <p>Unlike other database integration tests, DB2 tests use a unique approach due to
 * DB2's declared global temporary table semantics:</p>
 * 
 * <ul>
 *   <li><strong>Session Persistence:</strong> DB2's DECLARE GLOBAL TEMPORARY TABLE creates
 *       tables that persist for the entire database session, not just a single transaction.</li>
 *   <li><strong>OJP Session Affinity:</strong> When using the OJP proxy URL 
 *       (jdbc:ojp[localhost:1059]_db2://...), the SQL pattern detector triggers session 
 *       affinity for DECLARE GLOBAL TEMPORARY TABLE statements. This binds all subsequent 
 *       operations to the same OJP session UUID, which in turn uses the same underlying 
 *       DB2 connection/session.</li>
 *   <li><strong>Shared State Across Tests:</strong> Because all test methods share the same
 *       DB2 session (via OJP session affinity), temporary tables created in one test remain
 *       visible in subsequent tests.</li>
 * </ul>
 * 
 * <p><strong>Solution:</strong> Tests are ordered using {@code @Order} annotations and reuse
 * a single temp table name ("temp_session_test"). The first test creates the table, and
 * subsequent tests simply clear the existing data with DELETE before using it. This approach:</p>
 * <ul>
 *   <li>Avoids SQLCODE=-286 (duplicate table name) errors</li>
 *   <li>Still properly tests session affinity behavior</li>
 *   <li>Reflects the actual usage pattern in production where the same session can be
 *       reused across multiple operations</li>
 * </ul>
 * 
 * @see org.openjproxy.grpc.server.sql.SqlSessionAffinityDetector
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Db2SessionAffinityIntegrationTest {

    private static boolean isTestDisabled;
    
    /**
     * Shared table name used across all test methods.
     * This is possible because OJP session affinity ensures all tests
     * use the same underlying DB2 session where the temp table persists.
     */
    private static final String SHARED_TABLE_NAME = "temp_session_test";

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    /**
     * Test 1: Creates the temporary table and verifies basic INSERT/SELECT operations.
     * This test runs first and creates the DECLARE GLOBAL TEMPORARY TABLE that will
     * be reused by subsequent tests in the same session.
     * 
     * <p>This test verifies that DECLARE GLOBAL TEMPORARY TABLE triggers session affinity
     * and subsequent operations use the same session/connection.</p>
     */
    @Order(1)
    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    public void testTemporaryTableSessionAffinity(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");

        log.info("Testing temporary table session affinity for DB2: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);
        
        try (Statement stmt = conn.createStatement()) {
            // Try to create the declared global temporary table (this should trigger session affinity)
            // If it already exists from a previous run, catch the duplicate error and just clear data
            log.debug("Creating DB2 declared global temporary table: {}", SHARED_TABLE_NAME);
            try {
                stmt.execute("DECLARE GLOBAL TEMPORARY TABLE " + SHARED_TABLE_NAME + 
                    " (id INT, value VARCHAR(100)) ON COMMIT PRESERVE ROWS");
                log.debug("Successfully created temporary table");
            } catch (SQLException e) {
                // SQLCODE=-286 (SQLSTATE 42727) means table already exists - clear it and continue
                if ("42727".equals(e.getSQLState())) {
                    log.debug("Table already exists, clearing data");
                    stmt.execute("DELETE FROM SESSION." + SHARED_TABLE_NAME);
                } else {
                    throw e;
                }
            }

            // Insert data into temporary table (should use same session)
            log.debug("Inserting data into temporary table");
            stmt.execute("INSERT INTO SESSION." + SHARED_TABLE_NAME + " VALUES (1, 'test_value')");

            // Query temporary table (should use same session)
            log.debug("Querying temporary table");
            ResultSet rs = stmt.executeQuery("SELECT id, value FROM SESSION." + SHARED_TABLE_NAME);
            
            // Verify data was inserted and retrieved successfully
            Assert.assertTrue("Should have at least one row in temporary table", rs.next());
            Assert.assertEquals("Session data should match", 1, rs.getInt("id"));
            Assert.assertEquals("Session data should match", "test_value", rs.getString("value"));
            
            // Verify no more rows
            Assert.assertFalse("Should have only one row", rs.next());
            
            rs.close();
            
            log.info("DB2 temporary table session affinity test passed");

        } finally {
            conn.close();
        }
    }

    /**
     * Test 2: Reuses the existing temp table for complex operations (multiple inserts, update, query).
     * This test runs second and demonstrates that the temp table created in test 1
     * is still accessible in the same DB2 session.
     * 
     * <p>The test clears existing data first, then performs INSERT, UPDATE, and SELECT
     * operations to verify session affinity is maintained across complex operations.</p>
     */
    @Order(2)
    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    public void testComplexTemporaryTableOperations(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");

        log.info("Testing complex temporary table operations for DB2: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);
        
        try (Statement stmt = conn.createStatement()) {
            // Try to create the table if it doesn't exist, or just clear data if it does
            log.debug("Ensuring temp table exists: {}", SHARED_TABLE_NAME);
            try {
                stmt.execute("DECLARE GLOBAL TEMPORARY TABLE " + SHARED_TABLE_NAME + 
                    " (id INT, value VARCHAR(100)) ON COMMIT PRESERVE ROWS");
                log.debug("Successfully created temporary table");
            } catch (SQLException e) {
                // SQLCODE=-286 (SQLSTATE 42727) means table already exists - clear it and continue
                if ("42727".equals(e.getSQLState())) {
                    log.debug("Table already exists, clearing data");
                    stmt.execute("DELETE FROM SESSION." + SHARED_TABLE_NAME);
                } else {
                    throw e;
                }
            }

            // Insert multiple rows
            log.debug("Inserting multiple rows");
            stmt.execute("INSERT INTO SESSION." + SHARED_TABLE_NAME + " VALUES (1, 'Alice')");
            stmt.execute("INSERT INTO SESSION." + SHARED_TABLE_NAME + " VALUES (2, 'Bob')");
            stmt.execute("INSERT INTO SESSION." + SHARED_TABLE_NAME + " VALUES (3, 'Charlie')");

            // Update a row
            log.debug("Updating a row");
            stmt.executeUpdate("UPDATE SESSION." + SHARED_TABLE_NAME + " SET value = 'Robert' WHERE id = 2");

            // Query and verify
            log.debug("Querying temp table");
            ResultSet rs = stmt.executeQuery("SELECT id, value FROM SESSION." + SHARED_TABLE_NAME + " ORDER BY id");
            
            int rowCount = 0;
            while (rs.next()) {
                rowCount++;
                int id = rs.getInt("id");
                String value = rs.getString("value");
                
                if (id == 1) {
                    Assert.assertEquals("Alice", value);
                } else if (id == 2) {
                    Assert.assertEquals("Robert", value); // Updated
                } else if (id == 3) {
                    Assert.assertEquals("Charlie", value);
                }
            }
            
            Assert.assertEquals("Should have 3 rows", 3, rowCount);
            rs.close();
            
            log.info("DB2 complex temporary table operations test passed");

        } finally {
            conn.close();
        }
    }

    /**
     * Test 3: Verifies that the temp table persists within the same session across transactions.
     * This test runs third and demonstrates that commit/rollback operations don't affect
     * the temp table's existence (only its data, based on ON COMMIT PRESERVE ROWS).
     * 
     * <p>The test clears existing data, inserts new data in a transaction, commits,
     * and then verifies the data is still accessible in a subsequent transaction.</p>
     */
    @Order(3)
    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    public void testTemporaryTablePersistenceAcrossTransactions(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");

        log.info("Testing temporary table persistence across transactions for DB2: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);
        
        try (Statement stmt = conn.createStatement()) {
            // Try to create the table if it doesn't exist, or just clear data if it does
            log.debug("Ensuring temp table exists: {}", SHARED_TABLE_NAME);
            try {
                stmt.execute("DECLARE GLOBAL TEMPORARY TABLE " + SHARED_TABLE_NAME + 
                    " (id INT, value VARCHAR(100)) ON COMMIT PRESERVE ROWS");
                log.debug("Successfully created temporary table");
            } catch (SQLException e) {
                // SQLCODE=-286 (SQLSTATE 42727) means table already exists - clear it and continue
                if ("42727".equals(e.getSQLState())) {
                    log.debug("Table already exists, clearing data");
                    stmt.execute("DELETE FROM SESSION." + SHARED_TABLE_NAME);
                } else {
                    throw e;
                }
            }

            // Start transaction and insert
            conn.setAutoCommit(false);
            stmt.execute("INSERT INTO SESSION." + SHARED_TABLE_NAME + " VALUES (1, 'in_transaction')");
            conn.commit();

            // Start another transaction and query (should still see the temp table and data)
            conn.setAutoCommit(false);
            ResultSet rs = stmt.executeQuery("SELECT * FROM SESSION." + SHARED_TABLE_NAME + " WHERE id = 1");
            Assert.assertTrue("Should find row inserted in previous transaction", rs.next());
            Assert.assertEquals("Data should match", "in_transaction", rs.getString("value"));
            rs.close();
            conn.commit();

            log.info("DB2 temporary table persistence across transactions test passed");

        } finally {
            conn.close();
        }
    }
}
