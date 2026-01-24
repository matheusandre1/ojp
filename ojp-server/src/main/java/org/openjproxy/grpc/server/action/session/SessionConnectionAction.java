package org.openjproxy.grpc.server.action.session;

import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.server.ConnectionAcquisitionManager;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.UnpooledConnectionDetails;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.ValueAction;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Action to acquire a database connection for a given session.
 * Handles lazy allocation, XA vs Regular connections, and pooled vs unpooled
 * modes.
 * 
 * extracted from StatementServiceImpl.sessionConnection().
 */
@Slf4j
public class SessionConnectionAction implements ValueAction<SessionConnectionRequest, ConnectionSessionDTO> {

    private static final SessionConnectionAction INSTANCE = new SessionConnectionAction();

    private SessionConnectionAction() {
        // Private constructor prevents external instantiation
    }

    public static SessionConnectionAction getInstance() {
        return INSTANCE;
    }

    @Override
    public ConnectionSessionDTO execute(SessionConnectionRequest request) throws SQLException {
        ActionContext context = request.getContext();
        SessionInfo sessionInfo = request.getSessionInfo();
        boolean startSessionIfNone = request.isStartSessionIfNone();

        ConnectionSessionDTO.ConnectionSessionDTOBuilder dtoBuilder = ConnectionSessionDTO.builder();
        dtoBuilder.session(sessionInfo);
        Connection conn;

        if (StringUtils.isNotEmpty(sessionInfo.getSessionUUID())) {
            // Session already exists, reuse its connection
            conn = context.getSessionManager().getConnection(sessionInfo);
            if (conn == null) {
                throw new SQLException("Connection not found for this sessionInfo");
            }
            dtoBuilder.dbName(DatabaseUtils.resolveDbName(conn.getMetaData().getURL()));
            if (conn.isClosed()) {
                throw new SQLException("Connection is closed");
            }
        } else {
            // Lazy allocation: check if this is an XA or regular connection
            String connHash = sessionInfo.getConnHash();
            boolean isXA = sessionInfo.getIsXA();

            if (isXA) {
                // XA connection - check if unpooled or pooled mode
                XADataSource xaDataSource = context.getXaDataSourceMap().get(connHash);

                if (xaDataSource != null) {
                    // Unpooled XA mode: create XAConnection on demand
                    try {
                        log.debug("Creating unpooled XAConnection for hash: {}", connHash);
                        XAConnection xaConnection = xaDataSource.getXAConnection();
                        conn = xaConnection.getConnection();

                        // Store the XAConnection in session for XA operations
                        if (startSessionIfNone) {
                            SessionInfo updatedSession = context.getSessionManager().createSession(
                                    sessionInfo.getClientUUID(),
                                    conn);
                            // Store XAConnection as an attribute for XA operations
                            context.getSessionManager().registerAttr(updatedSession, "xaConnection", xaConnection);
                            dtoBuilder.session(updatedSession);
                        }
                        log.debug("Successfully created unpooled XAConnection for hash: {}", connHash);
                    } catch (SQLException e) {
                        log.error("Failed to create unpooled XAConnection for hash: {}. Error: {}",
                                connHash, e.getMessage());
                        throw e;
                    }
                } else {
                    // Pooled XA mode - should already have a session created in connect()
                    // This shouldn't happen as XA sessions are created eagerly
                    throw new SQLException("XA session should already exist. Session UUID is missing.");
                }
            } else {
                // Regular connection - check if pooled or unpooled mode
                UnpooledConnectionDetails unpooledDetails = context.getUnpooledConnectionDetailsMap().get(connHash);

                if (unpooledDetails != null) {
                    // Unpooled mode: create direct connection without pooling
                    try {
                        log.debug("Creating unpooled (passthrough) connection for hash: {}", connHash);
                        conn = java.sql.DriverManager.getConnection(
                                unpooledDetails.getUrl(),
                                unpooledDetails.getUsername(),
                                unpooledDetails.getPassword());
                        log.debug("Successfully created unpooled connection for hash: {}", connHash);
                    } catch (SQLException e) {
                        log.error("Failed to create unpooled connection for hash: {}. Error: {}",
                                connHash, e.getMessage());
                        throw e;
                    }
                } else {
                    // Pooled mode: acquire from datasource (HikariCP by default)
                    DataSource dataSource = context.getDatasourceMap().get(connHash);
                    if (dataSource == null) {
                        throw new SQLException("No datasource found for connection hash: " + connHash);
                    }

                    try {
                        // Use enhanced connection acquisition with timeout protection
                        conn = ConnectionAcquisitionManager.acquireConnection(dataSource, connHash);
                        log.debug("Successfully acquired connection from pool for hash: {}", connHash);
                    } catch (SQLException e) {
                        log.error("Failed to acquire connection from pool for hash: {}. Error: {}",
                                connHash, e.getMessage());

                        // Re-throw the enhanced exception from ConnectionAcquisitionManager
                        throw e;
                    }
                }

                if (startSessionIfNone) {
                    SessionInfo updatedSession = context.getSessionManager().createSession(sessionInfo.getClientUUID(),
                            conn);
                    dtoBuilder.session(updatedSession);
                }

                dtoBuilder.dbName(DatabaseUtils.resolveDbName(conn.getMetaData().getURL()));
            }
        }
        dtoBuilder.connection(conn);

        return dtoBuilder.build();
    }
}
