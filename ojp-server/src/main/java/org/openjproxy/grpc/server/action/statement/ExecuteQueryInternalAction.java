package org.openjproxy.grpc.server.action.statement;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.StatementRequest;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.action.ValueAction;
import org.openjproxy.grpc.server.action.session.SessionConnectionAction;
import org.openjproxy.grpc.server.action.session.SessionConnectionRequest;
import org.openjproxy.grpc.server.resultset.ResultSetHandler;
import org.openjproxy.grpc.server.statement.StatementFactory;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.sql.SqlEnhancerEngine;
import org.openjproxy.grpc.server.sql.SqlEnhancementResult;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import lombok.Builder;
import lombok.Value;

@Slf4j
public class ExecuteQueryInternalAction
        implements ValueAction<ExecuteQueryInternalAction.ExecuteQueryInternalRequest, OpResult> {

    private static final ExecuteQueryInternalAction INSTANCE = new ExecuteQueryInternalAction();

    private ExecuteQueryInternalAction() {
    }

    public static ExecuteQueryInternalAction getInstance() {
        return INSTANCE;
    }

    @Value
    @Builder
    public static class ExecuteQueryInternalRequest {
        ActionContext context;
        StatementRequest statementRequest;
        StreamObserver<OpResult> responseObserver;
    }

    @Override
    public OpResult execute(ExecuteQueryInternalRequest internalRequest) throws SQLException {
        StatementRequest request = internalRequest.getStatementRequest();
        ActionContext context = internalRequest.getContext();
        StreamObserver<OpResult> responseObserver = internalRequest.getResponseObserver();

        ConnectionSessionDTO dto;
        SessionConnectionRequest sessionConnRequest = SessionConnectionRequest.builder()
                .context(context)
                .sessionInfo(request.getSession())
                .startSessionIfNone(true)
                .build();
        dto = SessionConnectionAction.getInstance().execute(sessionConnRequest);

        long enhancementStartTime = System.currentTimeMillis();

        String sql = request.getSql();
        if (context.getServerConfiguration().isSqlEnhancerEnabled()) {
            SqlEnhancerEngine sqlEnhancerEngine = new SqlEnhancerEngine(
                    context.getServerConfiguration().isSqlEnhancerEnabled());

            SqlEnhancementResult result = sqlEnhancerEngine.enhance(request.getSql());
            sql = result.getEnhancedSql();

            long enhancementDuration = System.currentTimeMillis() - enhancementStartTime;

            if (result.isModified()) {
                log.debug("SQL was enhanced in {}ms: {} -> {}", enhancementDuration,
                        request.getSql().substring(0, Math.min(request.getSql().length(), 50)),
                        sql.substring(0, Math.min(sql.length(), 50)));
            } else if (enhancementDuration > 10) {
                log.debug("SQL enhancement took {}ms (no modifications)", enhancementDuration);
            }
        }

        List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());
        if (CollectionUtils.isNotEmpty(params)) {
            PreparedStatement ps = StatementFactory.createPreparedStatement(context.getSessionManager(), dto, sql,
                    params, request);
            String resultSetUUID = context.getSessionManager().registerResultSet(dto.getSession(), ps.executeQuery());
            ResultSetHandler.getInstance().handleResultSet(context, dto.getSession(), resultSetUUID, responseObserver);
        } else {
            Statement stmt = StatementFactory.createStatement(context.getSessionManager(), dto.getConnection(),
                    request);
            String resultSetUUID = context.getSessionManager().registerResultSet(dto.getSession(),
                    stmt.executeQuery(sql));
            ResultSetHandler.getInstance().handleResultSet(context, dto.getSession(), resultSetUUID, responseObserver);
        }

        return null; // The result is returned via StreamObserver
    }
}
