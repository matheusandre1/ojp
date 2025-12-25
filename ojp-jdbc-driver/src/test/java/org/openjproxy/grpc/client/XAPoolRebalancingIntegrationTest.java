package org.openjproxy.grpc.client;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openjproxy.jdbc.xa.OjpXADataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for XA pool rebalancing behavior.
 * This test uses Testcontainers to create a controlled environment:
 * 1. PostgreSQL database
 * 2. Single OJP server configured with simulated 2-server cluster
 * 
 * The test injects cluster health metadata via connection URL parameters
 * to simulate a 2-server cluster, then simulates one server going down
 * to verify the XA backend pool properly rebalances.
 * 
 * Test flow:
 * 1. Start PostgreSQL via Testcontainers
 * 2. Start OJP server
 * 3. Create a simple test table
 * 4. Connect with URL indicating 2 servers - verify pool is divided (~10 connections)
 * 5. Reconnect with URL indicating 1 server - verify pool expanded (~20 connections)
 */
@Slf4j
@Testcontainers
public class XAPoolRebalancingIntegrationTest {

    private static Network network = Network.newNetwork();
    
    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14-alpine")
            .withNetwork(network)
            .withNetworkAliases("postgres")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    @Container
    private static final GenericContainer<?> ojpServer = new GenericContainer<>("open-j-proxy/ojp:latest")
            .withNetwork(network)
            .withNetworkAliases("ojp-server")
            .withEnv("OJP_BACKEND_URL", "jdbc:postgresql://postgres:5432/testdb")
            .withEnv("OJP_BACKEND_USER", "testuser")
            .withEnv("OJP_BACKEND_PASSWORD", "testpass")
            .withEnv("OJP_XA_POOL_MAX_SIZE", "20")
            .withEnv("OJP_XA_POOL_MIN_IDLE", "20")
            .withExposedPorts(1059)
            .waitingFor(Wait.forLogMessage(".*OJP Server started.*", 1));

    // XID components for testing
    private static final byte[] GLOBAL_TXN_ID_BASE = "global_txn_".getBytes();
    private static final byte[] BRANCH_QUALIFIER = "branch_001".getBytes();
    private static int xidCounter = 0;

    @BeforeAll
    public static void setup() throws Exception {
        // Wait for containers to be ready
        postgres.start();
        ojpServer.start();
        
        log.info("PostgreSQL started at: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());
        log.info("OJP Server started at: {}:{}", ojpServer.getHost(), ojpServer.getFirstMappedPort());
        
        // Create test table directly on PostgreSQL
        try (Connection conn = postgres.createConnection("")) {
            Statement stmt = conn.createStatement();
            stmt.execute("DROP TABLE IF EXISTS test_rebalance");
            stmt.execute("CREATE TABLE test_rebalance (id SERIAL PRIMARY KEY, value VARCHAR(100))");
            stmt.close();
            log.info("Test table created successfully");
        }
    }

    @AfterAll
    public static void teardown() {
        if (ojpServer != null) {
            ojpServer.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    public void testXAPoolRebalancesOnSimulatedClusterChange() throws Exception {
        log.info("=== Starting XA Pool Rebalancing Test ===");
        
        String ojpHost = ojpServer.getHost();
        Integer ojpPort = ojpServer.getFirstMappedPort();
        String ojpUrl = String.format("jdbc:ojp:postgresql://%s:%d,%s:%d/testdb", 
                ojpHost, ojpPort, ojpHost, ojpPort + 1);  // Simulate 2-server cluster
        
        // Phase 1: Insert with 2-server cluster URL
        log.info("Phase 1: Inserting data with 2-server cluster configuration");
        log.info("URL: {}", ojpUrl);
        
        OjpXADataSource xaDataSource = createXADataSource(ojpUrl);
        int initialConnections = performXAInsert(xaDataSource, "phase1_value");
        
        log.info("Initial connection count: {}", initialConnections);
        
        // Verify initial pool size is divided (should be around 8-12 connections)
        // With 2 servers in URL, each should get ~50% of the pool capacity
        assertTrue(initialConnections >= 8 && initialConnections <= 13,
                "Initial connections should be 8-13 (divided pool), but got: " + initialConnections);
        
        // Wait for pool to stabilize
        Thread.sleep(3000);
        
        // Phase 2: Insert with 1-server cluster URL (simulating server failure)
        log.info("Phase 2: Inserting data with 1-server cluster configuration (server failure simulation)");
        String singleServerUrl = String.format("jdbc:ojp:postgresql://%s:%d/testdb", ojpHost, ojpPort);
        log.info("URL: {}", singleServerUrl);
        
        OjpXADataSource singleServerDataSource = createXADataSource(singleServerUrl);
        int expandedConnections = performXAInsert(singleServerDataSource, "phase2_value");
        
        log.info("Expanded connection count: {}", expandedConnections);
        
        // Verify pool expanded to handle full capacity (should be around 18-22 connections)
        // With 1 server in URL, it should use 100% of the pool capacity
        assertTrue(expandedConnections >= 16 && expandedConnections <= 23,
                "Expanded connections should be 16-23 (full pool), but got: " + expandedConnections);
        
        // Verify the expansion happened (should grow by at least 50%)
        double growthRatio = (double) expandedConnections / initialConnections;
        assertTrue(growthRatio >= 1.4,
                String.format("Growth ratio should be >= 1.4x, but got: %.2fx (%d -> %d)",
                        growthRatio, initialConnections, expandedConnections));
        
        log.info("=== XA Pool Rebalancing Test PASSED ===");
        log.info("Pool successfully expanded from {} to {} connections ({}x growth)",
                initialConnections, expandedConnections, String.format("%.2f", growthRatio));
    }

    /**
     * Creates an XA DataSource with the given URL
     */
    private OjpXADataSource createXADataSource(String url) {
        OjpXADataSource dataSource = new OjpXADataSource();
        dataSource.setUrl(url);
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    /**
     * Performs an XA insert transaction and returns the current PostgreSQL connection count
     */
    private int performXAInsert(OjpXADataSource dataSource, String value) throws Exception {
        XAConnection xaConn = null;
        Connection conn = null;
        
        try {
            // Get XA connection
            xaConn = dataSource.getXAConnection();
            XAResource xaResource = xaConn.getXAResource();
            conn = xaConn.getConnection();
            
            // Create unique XID
            Xid xid = createXid();
            
            // Start XA transaction
            log.info("Starting XA transaction");
            xaResource.start(xid, XAResource.TMNOFLAGS);
            
            // Execute insert
            PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO test_rebalance (value) VALUES (?)");
            pstmt.setString(1, value);
            int inserted = pstmt.executeUpdate();
            pstmt.close();
            
            log.info("Inserted {} row(s)", inserted);
            
            // End XA transaction
            xaResource.end(xid, XAResource.TMSUCCESS);
            
            // Prepare
            int prepared = xaResource.prepare(xid);
            log.info("XA prepare result: {}", prepared);
            
            // Commit
            xaResource.commit(xid, false);
            log.info("XA transaction committed successfully");
            
            // Wait for pool to adjust and stabilize
            return waitForConnectionCount(8, 60);
            
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception e) {
                    log.warn("Error closing connection: {}", e.getMessage());
                }
            }
            if (xaConn != null) {
                try {
                    xaConn.close();
                } catch (Exception e) {
                    log.warn("Error closing XA connection: {}", e.getMessage());
                }
            }
        }
    }
    
    /**
     * Creates a unique XID for each transaction
     */
    private Xid createXid() {
        byte[] globalId = (new String(GLOBAL_TXN_ID_BASE) + (++xidCounter)).getBytes();
        return new TestXid(globalId, BRANCH_QUALIFIER, xidCounter);
    }


    /**
     * Waits for the connection count to reach at least the minimum expected count.
     * Polls every 5 seconds for up to maxWaitSeconds.
     */
    private int waitForConnectionCount(int minExpected, int maxWaitSeconds) throws Exception {
        int elapsed = 0;
        int currentCount = 0;
        
        while (elapsed < maxWaitSeconds) {
            currentCount = getPostgresConnectionCount();
            log.info("Current PostgreSQL connections: {} (waiting for >= {})", currentCount, minExpected);
            
            if (currentCount >= minExpected) {
                log.info("Connection count reached: {}", currentCount);
                return currentCount;
            }
            
            Thread.sleep(5000);
            elapsed += 5;
        }
        
        log.warn("Timeout waiting for connection count. Expected >= {}, got {}", minExpected, currentCount);
        return currentCount;
    }

    /**
     * Queries PostgreSQL to get the current number of active connections to the test database.
     * Counts idle connections from the OJP server backend pool.
     */
    private int getPostgresConnectionCount() throws Exception {
        try (Connection conn = postgres.createConnection("")) {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(
                    "SELECT count(*) FROM pg_stat_activity " +
                    "WHERE datname = '" + postgres.getDatabaseName() + "' " +
                    "AND state = 'idle' " +
                    "AND application_name != 'psql'");
            
            if (rs.next()) {
                int count = rs.getInt(1);
                rs.close();
                stmt.close();
                return count;
            }
            
            rs.close();
            stmt.close();
            return 0;
        }
    }

    /**
     * Simple Xid implementation for testing
     */
    private static class TestXid implements Xid {
        private final byte[] globalTxnId;
        private final byte[] branchQualifier;
        private final int formatId;

        public TestXid(byte[] globalTxnId, byte[] branchQualifier, int formatId) {
            this.globalTxnId = globalTxnId;
            this.branchQualifier = branchQualifier;
            this.formatId = formatId;
        }

        @Override
        public int getFormatId() {
            return formatId;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return globalTxnId;
        }

        @Override
        public byte[] getBranchQualifier() {
            return branchQualifier;
        }
    }
}
