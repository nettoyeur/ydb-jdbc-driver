package tech.ydb.jdbc.impl;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.base.Suppliers;

import tech.ydb.jdbc.YdbConnection;
import tech.ydb.jdbc.YdbConst;
import tech.ydb.jdbc.YdbDatabaseMetaData;
import tech.ydb.jdbc.YdbPrepareMode;
import tech.ydb.jdbc.YdbPreparedStatement;
import tech.ydb.jdbc.YdbStatement;
import tech.ydb.jdbc.YdbTypes;
import tech.ydb.jdbc.context.YdbContext;
import tech.ydb.jdbc.context.YdbExecutor;
import tech.ydb.jdbc.context.YdbTxState;
import tech.ydb.jdbc.exception.YdbExecutionException;
import tech.ydb.jdbc.query.QueryType;
import tech.ydb.jdbc.query.YdbQuery;
import tech.ydb.jdbc.settings.FakeTxMode;
import tech.ydb.jdbc.settings.YdbOperationProperties;
import tech.ydb.table.Session;
import tech.ydb.table.query.DataQuery;
import tech.ydb.table.query.DataQueryResult;
import tech.ydb.table.query.ExplainDataQueryResult;
import tech.ydb.table.query.Params;
import tech.ydb.table.result.ResultSetReader;
import tech.ydb.table.result.impl.ProtoValueReaders;
import tech.ydb.table.settings.CommitTxSettings;
import tech.ydb.table.settings.ExecuteDataQuerySettings;
import tech.ydb.table.settings.ExecuteScanQuerySettings;
import tech.ydb.table.settings.ExecuteSchemeQuerySettings;
import tech.ydb.table.settings.ExplainDataQuerySettings;
import tech.ydb.table.settings.KeepAliveSessionSettings;
import tech.ydb.table.settings.PrepareDataQuerySettings;
import tech.ydb.table.settings.RequestSettings;
import tech.ydb.table.settings.RollbackTxSettings;

public class YdbConnectionImpl implements YdbConnection {
    private static final Logger LOGGER = Logger.getLogger(YdbConnectionImpl.class.getName());

    private final YdbContext ctx;
    private final YdbExecutor executor;
    private final Supplier<YdbDatabaseMetaData> metaDataSupplier;
    private final FakeTxMode scanQueryTxMode;
    private final FakeTxMode schemeQueryTxMode;

    private volatile YdbTxState state;

    public YdbConnectionImpl(YdbContext context) throws SQLException {
        this.ctx = context;
        this.metaDataSupplier = Suppliers.memoize(() -> new YdbDatabaseMetaDataImpl(this))::get;
        this.executor = new YdbExecutor(LOGGER);

        YdbOperationProperties props = ctx.getOperationProperties();
        this.scanQueryTxMode = props.getScanQueryTxMode();
        this.schemeQueryTxMode = props.getSchemeQueryTxMode();

        this.state = YdbTxState.create(props.getTransactionLevel(), props.isAutoCommit());
        this.ctx.register();
    }

    <T extends RequestSettings<?>> T withDefaultTimeout(T settings) {
        Duration operation = ctx.getOperationProperties().getDeadlineTimeout();
        if (!operation.isZero() && !operation.isNegative()) {
            settings.setOperationTimeout(operation);
            settings.setTimeout(operation.plusSeconds(1));
        }
        return settings;
    }

    @Override
    public YdbStatement createStatement() throws SQLException {
        return createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY,
                ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }

    @Override
    public YdbPreparedStatement prepareStatement(String sql) throws SQLException {
        return prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY,
                ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }

    @Override
    public String nativeSQL(String sql) {
        try {
            return YdbQuery.from(ctx.getQueryOptions(), sql).getYqlQuery(null);
        } catch (SQLException ex) {
            return ex.getMessage();
        }
    }

    private void updateState(YdbTxState newState) {
        if (this.state == newState) {
            return;
        }

        LOGGER.log(Level.FINE, "update tx state: {0} -> {1}", new Object[] { state, newState });
        this.state = newState;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        ensureOpened();
        if (autoCommit == state.isAutoCommit()) {
            return;
        }

        LOGGER.log(Level.FINE, "Set auto-commit: {0}", autoCommit);
        if (autoCommit) {
            commit();
        }
        updateState(state.withAutoCommit(autoCommit));
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        ensureOpened();
        return state.isAutoCommit();
    }

    @Override
    public void commit() throws SQLException {
        ensureOpened();

        if (!state.isInsideTransaction()) {
            return;
        }

        Session session = state.getSession(ctx, executor);
        CommitTxSettings settings = withDefaultTimeout(new CommitTxSettings());

        try {
            executor.clearWarnings();
            executor.execute(
                    "Commit TxId: " + state.txID(),
                    () -> session.commitTransaction(state.txID(), settings)
            );
        } finally {
            updateState(state.withCommit(session));
        }
    }

    @Override
    public void rollback() throws SQLException {
        ensureOpened();

        if (!state.isInsideTransaction()) {
            return;
        }

        Session session = state.getSession(ctx, executor);
        RollbackTxSettings settings = withDefaultTimeout(new RollbackTxSettings());

        try {
            executor.clearWarnings();
            executor.execute(
                    "Rollback TxId: " + state.txID(),
                    () -> session.rollbackTransaction(state.txID(), settings)
            );
        } finally {
            updateState(state.withRollback(session));
        }
    }

    @Override
    public void close() throws SQLException {
        if (isClosed()) {
            return;
        }

        commit(); // like Oracle
        executor.clearWarnings();
        state = null;
        ctx.deregister();
    }

    @Override
    public boolean isClosed() {
        return state == null;
    }

    @Override
    public YdbDatabaseMetaData getMetaData() {
        return metaDataSupplier.get();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        ensureOpened();
        state = state.withReadOnly(readOnly);
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return state.isReadOnly();
    }

    @Override
    public void setCatalog(String catalog) {
        // do nothing
    }

    @Override
    public String getCatalog() {
        return null;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        ensureOpened();
        if (state.transactionLevel() == level) {
            return;
        }

        LOGGER.log(Level.FINE, "Set transaction isolation level: {0}", level);
        updateState(state.withTransactionLevel(level));
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        ensureOpened();
        return state.transactionLevel();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        ensureOpened();
        return executor.toSQLWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        ensureOpened();
        executor.clearWarnings();
    }

    @Override
    public void executeSchemeQuery(YdbQuery query, YdbExecutor executor) throws SQLException {
        ensureOpened();

        if (state.isInsideTransaction()) {
            switch (schemeQueryTxMode) {
                case FAKE_TX:
                    break;
                case SHADOW_COMMIT:
                    commit();
                    break;
                case ERROR:
                default:
                    throw new YdbExecutionException(YdbConst.SCHEME_QUERY_INSIDE_TRANSACTION);

            }
        }

        // Scheme query does not affect transactions or result sets
        ExecuteSchemeQuerySettings settings = withDefaultTimeout(new ExecuteSchemeQuerySettings());
        final String yql = query.getYqlQuery(null);

        try (Session session = executor.createSession(ctx)) {
            executor.execute(QueryType.SCHEME_QUERY + " >>\n" + yql, () -> session.executeSchemeQuery(yql, settings));
        }
    }

    @Override
    public DataQueryResult executeDataQuery(YdbQuery query, YdbExecutor executor,
            ExecuteDataQuerySettings settings, Params params) throws SQLException {
        ensureOpened();

        final String yql = query.getYqlQuery(params);
        final Session session = state.getSession(ctx, executor);
        try {
            DataQueryResult result = executor.call(
                    QueryType.DATA_QUERY + " >>\n" + yql,
                    () -> session.executeDataQuery(yql, state.txControl(), params, settings)
            );
            updateState(state.withDataQuery(session, result.getTxId()));
            return result;
        } catch (SQLException | RuntimeException ex) {
            updateState(state.withRollback(session));
            throw ex;
        }
    }

    @Override
    public ResultSetReader executeScanQuery(YdbQuery query, YdbExecutor executor, Params params) throws SQLException {
        ensureOpened();

        if (state.isInsideTransaction()) {
            switch (scanQueryTxMode) {
                case FAKE_TX:
                    break;
                case SHADOW_COMMIT:
                    commit();
                    break;
                case ERROR:
                default:
                    throw new YdbExecutionException(YdbConst.SCAN_QUERY_INSIDE_TRANSACTION);

            }
        }

        String yql = query.getYqlQuery(params);
        Collection<ResultSetReader> resultSets = new LinkedBlockingQueue<>();
        Duration scanQueryTimeout = ctx.getOperationProperties().getScanQueryTimeout();
        ExecuteScanQuerySettings settings = ExecuteScanQuerySettings.newBuilder()
                .withRequestTimeout(scanQueryTimeout)
                .build();
        try (Session session = executor.createSession(ctx)) {
            executor.execute(QueryType.SCAN_QUERY + " >>\n" + yql,
                    () -> session.executeScanQuery(yql, params, settings).start(resultSets::add));
        }

        return ProtoValueReaders.forResultSets(resultSets);
    }

    @Override
    public ExplainDataQueryResult executeExplainQuery(YdbQuery query, YdbExecutor executor) throws SQLException {
        ensureOpened();

        String yql = query.getYqlQuery(null);
        ExplainDataQuerySettings settings = withDefaultTimeout(new ExplainDataQuerySettings());
        try (Session session = executor.createSession(ctx)) {
            String msg = QueryType.EXPLAIN_QUERY + " >>\n" + yql;
            return executor.call(msg, () -> session.explainDataQuery(yql, settings));
        }
    }

    DataQuery prepareDataQuery(YdbQuery query) throws SQLException {
        String yql = query.getYqlQuery(null);
        PrepareDataQuerySettings settings = withDefaultTimeout(new PrepareDataQuerySettings());
        try (Session session = executor.createSession(ctx)) {
            String msg = "Preparing Query >>\n" + yql;
            return executor.call(msg, () -> session.prepareDataQuery(yql, settings));
        }
    }

    @Override
    public YdbStatement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return createStatement(resultSetType, resultSetConcurrency, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }

    @Override
    public YdbPreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareStatement(sql, resultSetType, resultSetConcurrency, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }

    @Override
    public Map<String, Class<?>> getTypeMap() {
        return new HashMap<>(); // TODO: handle this out
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) {
        // not supported
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        ensureOpened();

        if (holdability != ResultSet.HOLD_CURSORS_OVER_COMMIT) {
            throw new SQLFeatureNotSupportedException(YdbConst.RESULT_SET_HOLDABILITY_UNSUPPORTED);
        }
    }

    @Override
    public int getHoldability() throws SQLException {
        ensureOpened();

        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public YdbStatement createStatement(int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        ensureOpened();
        checkStatementParams(resultSetType, resultSetConcurrency, resultSetHoldability);
        return new YdbStatementImpl(this, resultSetType);
    }

    @Override
    public YdbPreparedStatement prepareStatement(String origSql, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        checkStatementParams(resultSetType, resultSetConcurrency, resultSetHoldability);
        return prepareStatement(origSql, resultSetType, YdbPrepareMode.AUTO);
    }

    @Override
    public YdbPreparedStatement prepareStatement(String sql, YdbPrepareMode mode) throws SQLException {
        return prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, mode);
    }

    @Override
    public YdbPreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
            throw new SQLFeatureNotSupportedException(YdbConst.AUTO_GENERATED_KEYS_UNSUPPORTED);
        }
        return prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }

    private YdbPreparedStatement prepareStatement(String sql, int resultSetType, YdbPrepareMode mode) throws SQLException {
        ensureOpened();
        executor.clearWarnings();

        YdbQuery query = YdbQuery.from(ctx.getQueryOptions(), sql);

        if (query.type() != QueryType.DATA_QUERY && query.type() != QueryType.SCAN_QUERY) {
            throw new SQLException(YdbConst.UNSUPPORTED_QUERY_TYPE_IN_PS + query.type());
        }

        YdbJdbcParams params = YdbJdbcParams.create(this, query, mode);
        return new YdbPreparedStatementImpl(this, query, params, resultSetType);
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        ensureOpened();

        Session session = state.getSession(ctx, executor);
        try {
            KeepAliveSessionSettings settings = new KeepAliveSessionSettings().setTimeout(Duration.ofSeconds(timeout));
            Session.State keepAlive = executor.call(
                    "Keep alive: " + state.txID(),
                    () -> session.keepAlive(settings)
            );
            return keepAlive == Session.State.READY;
        } finally {
            updateState(state.withKeepAlive(session));
        }
    }

    @Override
    public void setClientInfo(String name, String value) {
        // not supported
    }

    @Override
    public void setClientInfo(Properties properties) {
        // not supported
    }

    @Override
    public String getClientInfo(String name) {
        return null; // not supported
    }

    @Override
    public Properties getClientInfo() {
        return new Properties(); // not supported
    }

    @Override
    public void setSchema(String schema) {
        // not supported
    }

    @Override
    public String getSchema() {
        return null;
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        ensureOpened();
        return (int) ctx.getOperationProperties().getDeadlineTimeout().toMillis();
    }

    @Override
    public YdbTypes getYdbTypes() {
        return YdbTypesImpl.getInstance();
    }

    @Override
    public String getYdbTxId() {
        return state.txID();
    }

    @Override
    public YdbContext getCtx() {
        return ctx;
    }

    private void ensureOpened() throws SQLException {
        if (state == null) {
            throw new SQLException(YdbConst.CLOSED_CONNECTION);
        }
    }

    private void checkStatementParams(int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY && resultSetType != ResultSet.TYPE_SCROLL_INSENSITIVE) {
            throw new SQLFeatureNotSupportedException(YdbConst.RESULT_SET_TYPE_UNSUPPORTED);
        }
        if (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            throw new SQLFeatureNotSupportedException(YdbConst.RESULT_SET_CONCURRENCY_UNSUPPORTED);
        }
        if (resultSetHoldability != ResultSet.HOLD_CURSORS_OVER_COMMIT) {
            throw new SQLFeatureNotSupportedException(YdbConst.RESULT_SET_HOLDABILITY_UNSUPPORTED);
        }
    }

    // UNSUPPORTED
    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.PREPARED_CALLS_UNSUPPORTED);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.PREPARED_CALLS_UNSUPPORTED);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.PREPARED_CALLS_UNSUPPORTED);
    }

    @Override
    public YdbPreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.AUTO_GENERATED_KEYS_UNSUPPORTED);
    }

    @Override
    public YdbPreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.AUTO_GENERATED_KEYS_UNSUPPORTED);
    }

    @Override
    public Clob createClob() throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.CLOB_UNSUPPORTED);
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.BLOB_UNSUPPORTED);
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.NCLOB_UNSUPPORTED);
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.SQLXML_UNSUPPORTED);
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.ARRAYS_UNSUPPORTED);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.STRUCTS_UNSUPPORTED);
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.SAVEPOINTS_UNSUPPORTED);
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.SAVEPOINTS_UNSUPPORTED);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.SAVEPOINTS_UNSUPPORTED);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.SAVEPOINTS_UNSUPPORTED);
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.ABORT_UNSUPPORTED);
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        throw new SQLFeatureNotSupportedException(YdbConst.SET_NETWORK_TIMEOUT_UNSUPPORTED);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return iface.cast(this);
        }
        throw new SQLException(YdbConst.CANNOT_UNWRAP_TO + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isAssignableFrom(getClass());
    }
}
