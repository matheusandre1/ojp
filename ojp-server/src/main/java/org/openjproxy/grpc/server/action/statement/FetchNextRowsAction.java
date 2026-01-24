package org.openjproxy.grpc.server.action.statement;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ResultSetFetchRequest;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.session.SessionConnectionAction;
import org.openjproxy.grpc.server.action.session.SessionConnectionRequest;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.resultset.ResultSetHandler;

import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

@Slf4j
public class FetchNextRowsAction implements Action<ResultSetFetchRequest, OpResult> {

    private static final FetchNextRowsAction INSTANCE = new FetchNextRowsAction();

    private FetchNextRowsAction() {
    }

    public static FetchNextRowsAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, ResultSetFetchRequest request,
            StreamObserver<OpResult> responseObserver) {
        log.debug("Executing fetch next rows for result set  {}", request.getResultSetUUID());

        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());

        try {
            SessionConnectionRequest sessionConnRequest = SessionConnectionRequest.builder()
                    .context(context)
                    .sessionInfo(request.getSession())
                    .startSessionIfNone(false)
                    .build();
            ConnectionSessionDTO dto = SessionConnectionAction.getInstance().execute(sessionConnRequest);

            ResultSetHandler.getInstance().handleResultSet(context, dto.getSession(), request.getResultSetUUID(),
                    responseObserver);
        } catch (SQLException e) {
            log.error("Failure fetch next rows for result set: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        }
    }
}
