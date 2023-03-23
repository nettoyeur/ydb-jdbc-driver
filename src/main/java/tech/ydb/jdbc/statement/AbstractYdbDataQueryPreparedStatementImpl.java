package tech.ydb.jdbc.statement;

import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.google.common.base.Suppliers;

import tech.ydb.jdbc.YdbParameterMetaData;
import tech.ydb.jdbc.common.QueryType;
import tech.ydb.jdbc.common.TypeDescription;
import tech.ydb.jdbc.common.YdbQuery;
import tech.ydb.jdbc.connection.YdbConnectionImpl;
import tech.ydb.table.query.DataQuery;

import static tech.ydb.jdbc.YdbConst.UNSUPPORTED_QUERY_TYPE_IN_PS;

public abstract class AbstractYdbDataQueryPreparedStatementImpl extends AbstractYdbPreparedStatementImpl {

    private final Supplier<YdbParameterMetaData> metaDataSupplier;
    private final DataQuery dataQuery;

    protected AbstractYdbDataQueryPreparedStatementImpl(YdbConnectionImpl connection,
                                                        int resultSetType,
                                                        YdbQuery query,
                                                        DataQuery dataQuery) throws SQLException {
        super(connection, query, resultSetType);
        this.dataQuery = Objects.requireNonNull(dataQuery);
        this.metaDataSupplier = Suppliers.memoize(() ->
                new YdbParameterMetaDataImpl(getParameterTypes()))::get;
    }

    @Override
    public YdbParameterMetaData getParameterMetaData() {
        return metaDataSupplier.get();
    }

    protected DataQuery getDataQuery() {
        return dataQuery;
    }

    @Override
    protected boolean executeImpl() throws SQLException {
        QueryType queryType = getQueryType();
        switch (queryType) {
            case DATA_QUERY:
                return executeDataQueryImpl(query, getParams());
            case SCAN_QUERY:
                return executeScanQueryImpl();
            default:
                throw new SQLException(UNSUPPORTED_QUERY_TYPE_IN_PS + queryType);
        }
    }

    protected abstract Map<String, TypeDescription> getParameterTypes();
}
