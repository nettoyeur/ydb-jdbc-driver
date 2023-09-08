package tech.ydb.jdbc.settings;

import java.sql.Connection;
import java.time.Duration;
import java.util.Collection;

import javax.annotation.Nullable;


public class YdbOperationProperty<T> extends AbstractYdbProperty<T, Void> {
    private static final PropertiesCollector<YdbOperationProperty<?>> PROPERTIES = new PropertiesCollector<>();

    public static final YdbOperationProperty<Duration> JOIN_DURATION =
            new YdbOperationProperty<>(
                    "joinDuration",
                    "Default timeout for all YDB operations",
                    "5m",
                    Duration.class,
                    PropertyConverter.durationValue());

    public static final YdbOperationProperty<Duration> QUERY_TIMEOUT =
            new YdbOperationProperty<>(
                    "queryTimeout",
                    "Default timeout for all YDB data queries, scheme and explain operations",
                    "0s",
                    Duration.class,
                    PropertyConverter.durationValue());

    public static final YdbOperationProperty<Duration> SCAN_QUERY_TIMEOUT =
            new YdbOperationProperty<>(
                    "scanQueryTimeout",
                    "Default timeout for all YDB scan queries",
                    "5m",
                    Duration.class,
                    PropertyConverter.durationValue());

    public static final YdbOperationProperty<Boolean> FAIL_ON_TRUNCATED_RESULT =
            new YdbOperationProperty<>(
                    "failOnTruncatedResult",
                    "Throw an exception when received truncated result",
                    "false",
                    Boolean.class,
                    PropertyConverter.booleanValue());

    public static final YdbOperationProperty<Duration> SESSION_TIMEOUT =
            new YdbOperationProperty<>(
                    "sessionTimeout",
                    "Default timeout to create a session",
                    "5s",
                    Duration.class,
                    PropertyConverter.durationValue());

    public static final YdbOperationProperty<Duration> DEADLINE_TIMEOUT =
            new YdbOperationProperty<>(
                    "deadlineTimeout",
                    "Deadline timeout for all operations",
                    "0s",
                    Duration.class,
                    PropertyConverter.durationValue());

    public static final YdbOperationProperty<Boolean> AUTOCOMMIT =
            new YdbOperationProperty<>(
                    "autoCommit",
                    "Auto commit all operations",
                    "true",
                    Boolean.class,
                    PropertyConverter.booleanValue());

    public static final YdbOperationProperty<Integer> TRANSACTION_LEVEL =
            new YdbOperationProperty<>(
                    "transactionLevel",
                    "Default transaction isolation level",
                    String.valueOf(Connection.TRANSACTION_SERIALIZABLE),
                    Integer.class,
                    PropertyConverter.integerValue());

    //

    // Some JDBC driver specific options

    public static final YdbOperationProperty<Boolean> ENFORCE_SQL_V1 =
            new YdbOperationProperty<>("enforceSqlV1",
                    "Enforce SQL v1 grammar by adding --!syntax_v1 in the beginning of each SQL statement",
                    "true",
                    Boolean.class,
                    PropertyConverter.booleanValue());

    public static final YdbOperationProperty<Boolean> ENFORCE_VARIABLE_PREFIX =
            new YdbOperationProperty<>("enforceVariablePrefix",
                    "Enforce variable prefixes, add $ symbol to all named variables when absent",
                    "true",
                    Boolean.class,
                    PropertyConverter.booleanValue());

    public static final YdbOperationProperty<Boolean> CACHE_CONNECTIONS_IN_DRIVER =
            new YdbOperationProperty<>("cacheConnectionsInDriver",
                    "Cache YDB connections in YdbDriver, cached by combination or url and properties",
                    "true",
                    Boolean.class,
                    PropertyConverter.booleanValue());

    public static final YdbOperationProperty<Boolean> DETECT_SQL_OPERATIONS =
            new YdbOperationProperty<>("detectSqlOperations",
                    "Detect and execute SQL operation based on SQL keywords",
                    "true",
                    Boolean.class,
                    PropertyConverter.booleanValue());

    public static final YdbOperationProperty<Boolean> DISABLE_PREPARE_DATAQUERY =
            new YdbOperationProperty<>("disablePrepareDataQuery",
                    "Disable executing #prepareDataQuery when creating PreparedStatements",
                    "false",
                    Boolean.class,
                    PropertyConverter.booleanValue());

    public static final YdbOperationProperty<Boolean> DISABLE_AUTO_PREPARED_BATCHES =
            new YdbOperationProperty<>("disableAutoPreparedBatches",
                    "Disable automatically detect list of tuples or structs in prepared statement",
                    "false",
                    Boolean.class,
                    PropertyConverter.booleanValue());


    public static final YdbOperationProperty<Boolean> DISABLE_JDBC_PARAMETERS =
            new YdbOperationProperty<>("disableJdbcParameters",
                    "Disable auto detect JDBC standart parameters '?'",
                    "false",
                    Boolean.class,
                    PropertyConverter.booleanValue());

    protected YdbOperationProperty(String name,
                                   String description,
                                   @Nullable String defaultValue,
                                   Class<T> type,
                                   PropertyConverter<T> converter) {
        super(name, description, defaultValue, type, converter, (op, value) -> {});
        PROPERTIES.register(this);
    }

    public static Collection<YdbOperationProperty<?>> properties() {
        return PROPERTIES.properties();
    }
}
