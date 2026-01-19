package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TransactionInfo;
import com.openjproxy.grpc.TransactionStatus;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.grpc.server.ConnectionAcquisitionManager;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.utils.SessionInfoUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * Action to start a transaction.
 * Extracts logic from StatementServiceImpl.startTransaction.
 */
@Slf4j
public class StartTransactionAction implements Action<SessionInfo, SessionInfo> {

    private static final StartTransactionAction INSTANCE = new StartTransactionAction();

    private StartTransactionAction() {

    }

    public static StartTransactionAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {

        log.info("Starting transaction");

        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(context, sessionInfo);

        try {
            SessionInfo activeSessionInfo = sessionInfo;
            Connection conn;

            // Start a session if none started yet.
            if (StringUtils.isEmpty(sessionInfo.getSessionUUID())) {
                DataSource ds = context.getDatasourceMap().get(sessionInfo.getConnHash());
                if (ds == null) {
                    throw new SQLException("No datasource found for connection hash: " + sessionInfo.getConnHash());
                }

                // Use ConnectionAcquisitionManager for better monitoring/timeout handling
                conn = ConnectionAcquisitionManager.acquireConnection(ds, sessionInfo.getConnHash());
                activeSessionInfo = context.getSessionManager().createSession(sessionInfo.getClientUUID(), conn);
            } else {
                conn = context.getSessionManager().getConnection(sessionInfo);
                if (conn == null) {
                    throw new SQLException("Connection not found for session: " + sessionInfo.getSessionUUID());
                }
            }

            // Start a transaction
            if (conn.getAutoCommit()) {
                conn.setAutoCommit(Boolean.FALSE);
            }

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ACTIVE)
                    .setTransactionUUID(UUID.randomUUID().toString())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = SessionInfoUtils.newBuilderFrom(activeSessionInfo);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);

            responseObserver.onNext(sessionInfoBuilder.build());
            log.debug("Transaction started successfully for session: {}", activeSessionInfo.getSessionUUID());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            log.error("SQL Error starting transaction: {}", se.getMessage());
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected error starting transaction", e);
            sendSQLExceptionMetadata(new SQLException("Unable to start transaction: " + e.getMessage()),
                    responseObserver);
        }
    }

    /**
     * Gets the target server identifier from the incoming request.
     * Simply echoes back what the client sent without any override.
     */
    private String getTargetServer(SessionInfo incomingSessionInfo) {
        // Echo back the targetServer from incoming request, or return empty string if
        // not present
        if (incomingSessionInfo != null &&
                incomingSessionInfo.getTargetServer() != null &&
                !incomingSessionInfo.getTargetServer().isEmpty()) {
            return incomingSessionInfo.getTargetServer();
        }
        // Return empty string if client didn't send targetServer
        return "";
    }
}
