package org.openjproxy.grpc.server.action.statement;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.StatementRequest;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;

/**
 * Action to execute a SQL query and return a result set.
 * Extracts the logic from StatementServiceImpl.executeQuery().
 */
@Slf4j
public class ExecuteQueryAction implements Action<StatementRequest, OpResult> {

    private static final ExecuteQueryAction INSTANCE = new ExecuteQueryAction();

    private ExecuteQueryAction() {
        // Private constructor prevents external instantiation
    }

    public static ExecuteQueryAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing query for {}", request.getSql());
        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());

        // Process cluster health from the request
        processClusterHealth(request.getSession());

        try {
            circuitBreaker.preCheck(stmtHash);

            // Get the appropriate slow query segregation manager for this datasource
            String connHash = request.getSession().getConnHash();
            SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(connHash);

            // Execute with slow query segregation
            manager.executeWithSegregation(stmtHash, () -> {
                executeQueryInternal(request, responseObserver);
                return null; // Void return for query execution
            });

            circuitBreaker.onSuccess(stmtHash);
        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("Failure during query execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during query execution: " + e.getMessage(), e);
            if (e.getCause() instanceof SQLException) {
                circuitBreaker.onFailure(stmtHash, (SQLException) e.getCause());
                sendSQLExceptionMetadata((SQLException) e.getCause(), responseObserver);
            } else {
                SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            }
        }
    }
}
