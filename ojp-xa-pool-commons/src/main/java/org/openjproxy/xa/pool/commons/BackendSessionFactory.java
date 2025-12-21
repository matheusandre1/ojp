package org.openjproxy.xa.pool.commons;

import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.openjproxy.xa.pool.XABackendSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.SQLException;

/**
 * Apache Commons Pool 2 factory for creating and managing {@link XABackendSession} instances.
 * <p>
 * This factory handles the complete lifecycle of pooled XA sessions:
 * </p>
 * <ul>
 *   <li><strong>makeObject</strong> - Creates new XAConnection and wraps it in BackendSessionImpl</li>
 *   <li><strong>validateObject</strong> - Checks session health via {@link XABackendSession#isHealthy()}</li>
 *   <li><strong>activateObject</strong> - Called when borrowing from pool (no-op for XA)</li>
 *   <li><strong>passivateObject</strong> - Called when returning to pool, invokes {@link XABackendSession#reset()}</li>
 *   <li><strong>destroyObject</strong> - Closes the session permanently</li>
 * </ul>
 * 
 * <h3>Reset on Passivate:</h3>
 * <p>
 * When a session is returned to the pool, {@code passivateObject} calls {@link XABackendSession#reset()}
 * to clean up session state. If reset() fails, the object is marked as invalid and will be destroyed.
 * </p>
 * 
 * <p><strong>Critical:</strong> This factory is only used for sessions that have completed their
 * XA transaction (COMMITTED or ROLLEDBACK). Sessions in PREPARED state are NOT returned to the
 * pool and therefore never reach passivateObject.</p>
 */
public class BackendSessionFactory implements PooledObjectFactory<XABackendSession> {
    private static final Logger log = LoggerFactory.getLogger(BackendSessionFactory.class);
    
    private final XADataSource xaDataSource;
    
    /**
     * Creates a new backend session factory.
     *
     * @param xaDataSource the XA data source to create connections from
     */
    public BackendSessionFactory(XADataSource xaDataSource) {
        if (xaDataSource == null) {
            throw new IllegalArgumentException("xaDataSource cannot be null");
        }
        this.xaDataSource = xaDataSource;
    }
    
    @Override
    public PooledObject<XABackendSession> makeObject() throws Exception {
        log.debug("Creating new backend session");
        
        try {
            // Create XAConnection from the vendor XADataSource
            XAConnection xaConnection = xaDataSource.getXAConnection();
            
            // Wrap in our XABackendSession implementation
            BackendSessionImpl session = new BackendSessionImpl(xaConnection);
            
            // Open the session (obtains Connection and XAResource)
            session.open();
            
            log.info("Backend session created successfully");
            
            return new DefaultPooledObject<>(session);
            
        } catch (SQLException e) {
            log.error("Failed to create backend session", e);
            throw e;
        }
    }
    
    @Override
    public void destroyObject(PooledObject<XABackendSession> p) throws Exception {
        XABackendSession session = p.getObject();
        
        log.debug("Destroying backend session");
        
        try {
            session.close();
            log.info("Backend session destroyed");
        } catch (Exception e) {
            log.error("Error destroying backend session", e);
            throw e;
        }
    }
    
    @Override
    public boolean validateObject(PooledObject<XABackendSession> p) {
        XABackendSession session = p.getObject();
        
        boolean isHealthy = session.isHealthy();
        
        if (!isHealthy) {
            log.warn("Backend session validation failed");
        }
        
        return isHealthy;
    }
    
    @Override
    public void activateObject(PooledObject<XABackendSession> p) throws Exception {
        // No activation needed for XA sessions
        // The session is already open and ready to use
        log.debug("Backend session activated (no-op)");
    }
    
    @Override
    public void passivateObject(PooledObject<XABackendSession> p) throws Exception {
        XABackendSession session = p.getObject();
        
        log.debug("Passivating backend session (resetting state)");
        
        try {
            // Reset session to clean state before returning to pool
            // This is ONLY called after transaction completion (COMMITTED/ROLLEDBACK)
            session.reset();
            
            log.debug("Backend session passivated successfully");
            
        } catch (Exception e) {
            log.error("Failed to reset session during passivation", e);
            // Mark object as invalid - pool will destroy it instead of reusing
            throw e;
        }
    }
}
