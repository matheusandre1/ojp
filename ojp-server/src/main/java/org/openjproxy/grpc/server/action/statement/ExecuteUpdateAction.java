package org.openjproxy.grpc.server.action.statement;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SqlErrorType;
import com.openjproxy.grpc.StatementRequest;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.SlowQuerySegregationManager;
import org.openjproxy.grpc.server.SqlStatementXXHash;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;

import java.sql.SQLDataException;
import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * Action to execute a database update (INSERT, UPDATE, DELETE).
 * Handles slow query segregation, circuit breaking, and cluster health
 * processing.
 */
@Slf4j
public class ExecuteUpdateAction implements Action<StatementRequest, OpResult> {

    private static final ExecuteUpdateAction INSTANCE = new ExecuteUpdateAction();

    private ExecuteUpdateAction() {
        // Private constructor prevents external instantiation
    }

    public static ExecuteUpdateAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing update {}", request.getSql());
        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());

        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());

        try {
            context.getCircuitBreaker().preCheck(stmtHash);

            // Get the appropriate slow query segregation manager for this datasource
            String connHash = request.getSession().getConnHash();
            SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(context, connHash);

            // Execute with slow query segregation
            OpResult result = manager.executeWithSegregation(stmtHash, () -> {
                ExecuteUpdateInternalAction.ExecuteUpdateInternalRequest internalRequest = ExecuteUpdateInternalAction.ExecuteUpdateInternalRequest
                        .builder()
                        .context(context)
                        .statementRequest(request)
                        .build();
                return ExecuteUpdateInternalAction.getInstance().execute(internalRequest);
            });

            responseObserver.onNext(result);
            responseObserver.onCompleted();
            context.getCircuitBreaker().onSuccess(stmtHash);

        } catch (SQLDataException e) {
            context.getCircuitBreaker().onFailure(stmtHash, e);
            log.error("SQL data failure during update execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver, SqlErrorType.SQL_DATA_EXCEPTION);
        } catch (SQLException e) {
            context.getCircuitBreaker().onFailure(stmtHash, e);
            log.error("Failure during update execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during update execution: " + e.getMessage(), e);
            if (e.getCause() instanceof SQLException) {
                context.getCircuitBreaker().onFailure(stmtHash, (SQLException) e.getCause());
                sendSQLExceptionMetadata((SQLException) e.getCause(), responseObserver);
            } else {
                SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
                context.getCircuitBreaker().onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            }
        }
    }

    /**
     * Gets the slow query segregation manager for a specific connection hash.
     * If no manager exists, creates a disabled one as a fallback.
     */
    private SlowQuerySegregationManager getSlowQuerySegregationManagerForConnection(ActionContext context,
            String connHash) {
        SlowQuerySegregationManager manager = context.getSlowQuerySegregationManagers().get(connHash);
        if (manager == null) {
            log.warn("No SlowQuerySegregationManager found for connection hash {}, creating disabled fallback",
                    connHash);
            // Create a disabled manager as fallback
            manager = new SlowQuerySegregationManager(1, 0, 0, 0, 0, 0, false);
            context.getSlowQuerySegregationManagers().put(connHash, manager);
        }
        return manager;
    }
}
