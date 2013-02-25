/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.server.service.restdml;

import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.TableName;
import com.akiban.ais.model.UserTable;
import com.akiban.server.Quote;
import com.akiban.server.error.WrongExpressionArityException;
import com.akiban.server.explain.format.JsonFormatter;
import com.akiban.server.service.Service;
import com.akiban.server.service.config.ConfigurationService;
import com.akiban.server.service.dxl.DXLService;
import com.akiban.server.service.externaldata.ExternalDataService;
import com.akiban.server.service.externaldata.JsonRowWriter;
import com.akiban.server.service.session.Session;
import com.akiban.server.service.session.SessionService;
import com.akiban.server.service.transaction.TransactionService;
import com.akiban.server.service.tree.TreeService;
import com.akiban.server.store.Store;
import com.akiban.server.t3expressions.T3RegistryService;
import com.akiban.sql.embedded.EmbeddedJDBCService;
import com.akiban.sql.embedded.JDBCCallableStatement;
import com.akiban.sql.embedded.JDBCConnection;
import com.akiban.sql.embedded.JDBCParameterMetaData;
import com.akiban.sql.embedded.JDBCResultSet;
import com.akiban.util.AkibanAppender;
import com.google.inject.Inject;
import org.codehaus.jackson.JsonNode;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.akiban.server.service.transaction.TransactionService.CloseableTransaction;

public class RestDMLServiceImpl implements Service, RestDMLService {
    private final SessionService sessionService;
    private final DXLService dxlService;
    private final TransactionService transactionService;
    private final ExternalDataService extDataService;
    private final EmbeddedJDBCService jdbcService;
    private final InsertProcessor insertProcessor;
    private final DeleteProcessor deleteProcessor;
    private final UpdateProcessor updateProcessor;

    @Inject
    public RestDMLServiceImpl(SessionService sessionService,
                              DXLService dxlService,
                              TransactionService transactionService,
                              ExternalDataService extDataService,
                              EmbeddedJDBCService jdbcService,
                              ConfigurationService configService,
                              TreeService treeService,
                              Store store,
                              T3RegistryService registryService) {
        this.sessionService = sessionService;
        this.dxlService = dxlService;
        this.transactionService = transactionService;
        this.extDataService = extDataService;
        this.jdbcService = jdbcService;
        this.insertProcessor = new InsertProcessor (configService, treeService, store, registryService);
        this.deleteProcessor = new DeleteProcessor (configService, treeService, store, registryService);
        this.updateProcessor = new UpdateProcessor (configService, treeService, store, registryService,
                deleteProcessor, insertProcessor);
    }
    
    /* Service */

    @Override
    public void start() {
        // None
    }

    @Override
    public void stop() {
        // None
   }

    @Override
    public void crash() {
        //None
    }

    /* RestDMLService */

    @Override
    public void getAllEntities(PrintWriter writer, TableName tableName, Integer depth) {
        int realDepth = (depth != null) ? Math.max(depth, 0) : -1;
        try (Session session = sessionService.createSession()) {
            extDataService.dumpAllAsJson(session,
                                         writer,
                                         tableName.getSchemaName(),
                                         tableName.getTableName(),
                                         realDepth,
                                         true);
        }
    }

    @Override
    public void getEntities(PrintWriter writer, TableName tableName, Integer depth, String identifiers) {
        int realDepth = (depth != null) ? Math.max(depth, 0) : -1;
        try (Session session = sessionService.createSession();
             CloseableTransaction txn = transactionService.beginCloseableTransaction(session)) {
            UserTable uTable = dxlService.ddlFunctions().getUserTable(session, tableName);
            Index pkIndex = uTable.getPrimaryKeyIncludingInternal().getIndex();
            List<List<String>> pks = PrimaryKeyParser.parsePrimaryKeys(identifiers, pkIndex);
            extDataService.dumpBranchAsJson(session,
                                            writer,
                                            tableName.getSchemaName(),
                                            tableName.getTableName(),
                                            pks,
                                            realDepth,
                                            false);
            txn.commit();
        }
    }

    @Override
    public void insert(PrintWriter writer, TableName rootTable, JsonNode node)  {
        try (Session session = sessionService.createSession();
             CloseableTransaction txn = transactionService.beginCloseableTransaction(session)) {
            AkibanInformationSchema ais = dxlService.ddlFunctions().getAIS(session);
            String pk = insertProcessor.processInsert(session, ais, rootTable, node);
            txn.commit();
            writer.write(pk);
        }
    }

    @Override
    public void delete(PrintWriter writer, TableName tableName, String identifier) {
        try (Session session = sessionService.createSession();
             CloseableTransaction txn = transactionService.beginCloseableTransaction(session)) {
            AkibanInformationSchema ais = dxlService.ddlFunctions().getAIS(session);
            deleteProcessor.processDelete(session, ais, tableName, identifier);
            txn.commit();
        }
    }

    @Override
    public void update(PrintWriter writer, TableName tableName, String pks, JsonNode node) {
        try (Session session = sessionService.createSession();
             CloseableTransaction txn = transactionService.beginCloseableTransaction(session)) {
            AkibanInformationSchema ais = dxlService.ddlFunctions().getAIS(session);
            String pk = updateProcessor.processUpdate(session, ais, tableName, pks, node);
            txn.commit();
            writer.write(pk);
        }
    }

    @Override
    public void runSQL(PrintWriter writer, HttpServletRequest request, String sql) throws SQLException {
        runSQLInternal(writer, request, Collections.singletonList(sql), OutputType.ARRAY, CommitMode.AUTO);
    }

    @Override
    public void runSQL(PrintWriter writer, HttpServletRequest request, List<String> sql) throws SQLException {
        runSQLInternal(writer, request, sql, OutputType.OBJECT, CommitMode.MANUAL);
    }

    @Override
    public void explainSQL(PrintWriter writer, HttpServletRequest request, String sql) throws IOException, SQLException {
        try (JDBCConnection conn = jdbcConnection(request)) {
            new JsonFormatter().format(conn.explain(sql), writer);
        }
    }

    @Override
    public void callProcedure(PrintWriter writer, HttpServletRequest request, String jsonpArgName,
                              TableName procName, Map<String,List<String>> params) throws SQLException {
        try (JDBCConnection conn = jdbcConnection(request);
             JDBCCallableStatement call = conn.prepareCall(procName)) {
            for (Map.Entry<String,List<String>> entry : params.entrySet()) {
                if (jsonpArgName.equals(entry.getKey()))
                    continue;
                if (entry.getValue().size() != 1)
                    throw new WrongExpressionArityException(1, entry.getValue().size());
                call.setString(entry.getKey(), entry.getValue().get(0));
            }
            boolean results = call.execute();
            AkibanAppender appender = AkibanAppender.of(writer);
            appender.append('{');
            boolean first = true;
            JDBCParameterMetaData md = (JDBCParameterMetaData)call.getParameterMetaData();
            for (int i = 1; i <= md.getParameterCount(); i++) {
                String name;
                switch (md.getParameterMode(i)) {
                case ParameterMetaData.parameterModeOut:
                case ParameterMetaData.parameterModeInOut:
                    name = md.getParameterName(i);
                    if (name == null)
                        name = String.format("arg%d", i);
                    if (first)
                        first = false;
                    else
                        appender.append(',');
                    appender.append('"');
                    Quote.DOUBLE_QUOTE.append(appender, name);
                    appender.append("\":");
                    call.formatAsJson(i, appender);
                    break;
                }
            }
            int nresults = 0;
            while(results) {
                beginResultSetArray(appender, first, nresults++);
                first = false;
                collectResults((JDBCResultSet) call.getResultSet(), appender);
                endResultSetArray(appender);
                results = call.getMoreResults();
            }
            appender.append('}');
        }
    }

    private void runSQLInternal(PrintWriter writer,
                                HttpServletRequest request, List<String> sqlList,
                                OutputType outputType, CommitMode commitMode) throws SQLException {
        boolean useSubArrays = (outputType == OutputType.OBJECT);
        try (Connection conn = jdbcConnection(request);
             Statement s = conn.createStatement()) {
            AkibanAppender appender = AkibanAppender.of(writer);
            int nresults = 0;
            commitMode.begin(conn);
            outputType.begin(appender);
            for(String sql : sqlList) {
                String trimmed = sql.trim();
                if(trimmed.isEmpty()) {
                    continue;
                }
                if(useSubArrays) {
                    beginResultSetArray(appender, nresults == 0, nresults);
                }
                boolean res = s.execute(trimmed);
                if(res) {
                    collectResults((JDBCResultSet)s.getResultSet(), appender);
                } else {
                    int updateCount = s.getUpdateCount();
                    appender.append("\n{\"update_count\":");
                    appender.append(updateCount);
                    appender.append("}\n");
                }
                if(useSubArrays) {
                    endResultSetArray(appender);
                }
                ++nresults;
            }
            commitMode.end(conn);
            outputType.end(appender);
        }
    }

    private static void beginResultSetArray(AkibanAppender appender, boolean first, int resultOffset) {
        String name = (resultOffset == 0) ? "result_set" : String.format("result_set_%d", resultOffset);
        if(!first) {
            appender.append(",");
        }
        appender.append('"');
        appender.append(name);
        appender.append("\":[");
    }

    private static void endResultSetArray(AkibanAppender appender) {
        appender.append(']');
    }

    private static void collectResults(JDBCResultSet resultSet, AkibanAppender appender) throws SQLException {
        SQLOutputCursor cursor = new SQLOutputCursor(resultSet);
        JsonRowWriter jsonRowWriter = new JsonRowWriter(cursor);
        if(jsonRowWriter.writeRows(cursor, appender, "\n", cursor)) {
            appender.append('\n');
        }
    }

    private JDBCConnection jdbcConnection(HttpServletRequest request) throws SQLException {
        return (JDBCConnection) jdbcService.newConnection(new Properties(), request.getUserPrincipal());
    }


    private static enum OutputType {
        ARRAY('[', ']'),
        OBJECT('{', '}');

        public void begin(AkibanAppender appender) {
            appender.append(beginChar);
        }

        public void end(AkibanAppender appender) {
            appender.append(endChar);
        }

        private OutputType(char beginChar, char endChar) {
            this.beginChar = beginChar;
            this.endChar = endChar;
        }

        public final char beginChar;
        public final char endChar;
    }

    private static enum CommitMode {
        AUTO,
        MANUAL;

        public void begin(Connection conn) throws SQLException {
            conn.setAutoCommit(this == AUTO);
        }

        public void end(Connection conn) throws SQLException {
            if(this == MANUAL) {
                conn.commit();
            }
        }
    }
}