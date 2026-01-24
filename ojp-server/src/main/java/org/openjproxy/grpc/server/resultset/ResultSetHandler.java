package org.openjproxy.grpc.server.resultset;

import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.dto.OpQueryResult;
import org.openjproxy.grpc.server.HydratedResultSetMetadata;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.lob.LobProcessor;
import org.openjproxy.grpc.server.utils.DateTimeUtils;

import java.sql.Clob;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
public class ResultSetHandler {

    private static final ResultSetHandler INSTANCE = new ResultSetHandler();
    private static final List<String> INPUT_STREAM_TYPES = Arrays.asList("RAW", "BINARY VARYING", "BYTEA");
    private final static String RESULT_SET_METADATA_ATTR_PREFIX = "rsMetadata|";

    private ResultSetHandler() {
    }

    public static ResultSetHandler getInstance() {
        return INSTANCE;
    }

    @SneakyThrows
    public void handleResultSet(ActionContext context, SessionInfo session, String resultSetUUID,
            StreamObserver<OpResult> responseObserver) {
        log.debug("Handling result set {}", resultSetUUID);
        ResultSet rs = context.getSessionManager().getResultSet(session, resultSetUUID);
        int columnCount = rs.getMetaData().getColumnCount();

        this.collectResultSetMetadata(context, session, resultSetUUID, rs);

        // Fetch result set metadata for the first block
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            labels.add(rs.getMetaData().getColumnName(i + 1));
        }
        OpQueryResult.OpQueryResultBuilder queryResultBuilder = OpQueryResult.builder().labels(labels);

        List<Object[]> results = new ArrayList<>();
        int row = 0;
        boolean justSent = false;
        String resultSetMode = "";

        DbName dbName = context.getDbNameMap().get(session.getConnHash());

        forEachRow: while (rs.next()) {
            justSent = false;
            row++;
            Object[] rowValues = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                int colType = rs.getMetaData().getColumnType(i + 1);
                String colTypeName = rs.getMetaData().getColumnTypeName(i + 1);
                Object currentValue = null;
                // Postgres uses type BYTEA which translates to type VARBINARY
                switch (colType) {
                    case Types.VARBINARY: {
                        if (DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName)) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        if ("BLOB".equalsIgnoreCase(colTypeName)) {
                            currentValue = LobProcessor.treatAsBlob(context.getSessionManager(), session, rs, i,
                                    context.getDbNameMap());
                        } else {
                            currentValue = LobProcessor.treatAsBinary(context.getSessionManager(), session, dbName, rs,
                                    i,
                                    INPUT_STREAM_TYPES);
                        }
                        break;
                    }
                    case Types.BLOB, Types.LONGVARBINARY: {
                        if (DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName)) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        currentValue = LobProcessor.treatAsBlob(context.getSessionManager(), session, rs, i,
                                context.getDbNameMap());
                        break;
                    }
                    case Types.CLOB: {
                        if (DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName)) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        Clob clob = rs.getClob(i + 1);
                        if (clob == null) {
                            currentValue = null;
                        } else {
                            String clobUUID = UUID.randomUUID().toString();
                            // CLOB needs to be prefixed as per it can be read in the JDBC driver by
                            // getString method and it would be valid to return just a UUID as string
                            currentValue = CommonConstants.OJP_CLOB_PREFIX + clobUUID;
                            context.getSessionManager().registerLob(session, clob, clobUUID);
                        }
                        break;
                    }
                    case Types.BINARY: {
                        if (DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName)) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        currentValue = LobProcessor.treatAsBinary(context.getSessionManager(), session, dbName, rs, i,
                                INPUT_STREAM_TYPES);
                        break;
                    }
                    case Types.DATE: {
                        Date date = rs.getDate(i + 1);
                        if ("YEAR".equalsIgnoreCase(colTypeName)) {
                            currentValue = date.toLocalDate().getYear();
                        } else {
                            currentValue = date;
                        }
                        break;
                    }
                    case Types.TIMESTAMP: {
                        currentValue = rs.getTimestamp(i + 1);
                        break;
                    }
                    default: {
                        currentValue = rs.getObject(i + 1);
                        // com.microsoft.sqlserver.jdbc.DateTimeOffset special case as per it does not
                        // implement any standar java.sql interface.
                        if ("datetimeoffset".equalsIgnoreCase(colTypeName) && colType == -155) {
                            currentValue = DateTimeUtils.extractOffsetDateTime(currentValue);
                        }
                        break;
                    }
                }
                rowValues[i] = currentValue;

            }
            results.add(rowValues);

            if ((DbName.DB2.equals(dbName) || DbName.SQL_SERVER.equals(dbName))
                    && CommonConstants.RESULT_SET_ROW_BY_ROW_MODE.equalsIgnoreCase(resultSetMode)) {
                break forEachRow;
            }

            if (row % CommonConstants.ROWS_PER_RESULT_SET_DATA_BLOCK == 0) {
                justSent = true;
                // Send a block of records
                responseObserver.onNext(ResultSetWrapper.wrapResults(session, results, queryResultBuilder,
                        resultSetUUID, resultSetMode));
                queryResultBuilder = OpQueryResult.builder();// Recreate the builder to not send labels in every block.
                results = new ArrayList<>();
            }
        }

        if (!justSent) {
            // Send a block of remaining records
            responseObserver.onNext(
                    ResultSetWrapper.wrapResults(session, results, queryResultBuilder, resultSetUUID, resultSetMode));
        }

        responseObserver.onCompleted();
    }

    @SneakyThrows
    private void collectResultSetMetadata(ActionContext context, SessionInfo session, String resultSetUUID,
            ResultSet rs) {
        context.getSessionManager().registerAttr(session, RESULT_SET_METADATA_ATTR_PREFIX +
                resultSetUUID, new HydratedResultSetMetadata(rs.getMetaData()));
    }
}
