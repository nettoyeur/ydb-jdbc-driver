package tech.ydb.jdbc.impl.helper;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import tech.ydb.jdbc.settings.YdbLookup;

/**
 *
 * @author Aleksandr Gorshenin
 */
public class SqlQueries {
    public enum JdbcQuery {
        STANDART,
        TYPED,
        BATCHED,
    }

    public enum YqlQuery {
        SIMPLE,
        BATCHED,
    }

    private static final String CREATE_TABLE = YdbLookup.stringFileReference("classpath:sql/create.sql");
    private static final String DROP_TABLE = YdbLookup.stringFileReference("classpath:sql/drop.sql");
    private static final String INIT_TABLE = YdbLookup.stringFileReference("classpath:sql/init.sql");

    private static final String SELECT = YdbLookup.stringFileReference("classpath:sql/select.sql");

    private static final String SIMPLE_UPSERT_ALL = YdbLookup.stringFileReference("classpath:sql/upsert/simple.sql");
    private static final String NAMED_UPSERT_ALL = YdbLookup.stringFileReference("classpath:sql/upsert/named.sql");
    private static final String BATCHED_UPSERT_ALL = YdbLookup.stringFileReference("classpath:sql/upsert/batched.sql");
    private static final String INDEXED_UPSERT_ALL = YdbLookup.stringFileReference("classpath:sql/upsert/types.sql");

    private static final String SELECT_ALL = "select * from #tableName";
    private static final String DELETE_ALL = "delete from #tableName";
    private static final String SELECT_COLUMN = "select key, #column from #tableName";
    private static final String WRONG_SELECT = "select key2 from #tableName";

    private static final Map<JdbcQuery, String> JDBC_UPSERT_ONE = ImmutableMap.of(
            JdbcQuery.STANDART, "" +
                    "upsert into #tableName (key, #column) values (?, ?)",

            JdbcQuery.TYPED, "" +
                    "declare $p1 as Int32;\n" +
                    "declare $p2 as #type;\n" +
                    "upsert into #tableName (key, #column) values ($p1, $p2)",

            JdbcQuery.BATCHED, "" +
                    "declare $values as List<Struct<p1:Int32,p2:#type>>;\n" +
                    "$mapper = ($row) -> (AsStruct($row.p1 as key, $row.p2 as #column));\n" +
                    "upsert into #tableName select * from as_table(ListMap($values, $mapper));"
    );

    private static final Map<YqlQuery, String> YQL_UPSERT_ONE = ImmutableMap.of(
            YqlQuery.SIMPLE, "" +
                    "declare $key as Int32;\n" +
                    "declare $#column as #type;\n" +
                    "upsert into #tableName (key, #column) values ($key, $#column)",

            YqlQuery.BATCHED, "" +
                    "declare $values as List<Struct<key:Int32,#column:#type>>;\n" +
                    "upsert into #tableName select * from as_table($values);"
    );

    private final String tableName;

    public SqlQueries(String tableName) {
        this.tableName = tableName;
    }

    public String withTableName(String query) {
        return query.replace("#tableName", tableName);
    }

    public String createTableSQL() {
        return withTableName(CREATE_TABLE);
    }

    public String dropTableSQL() {
        return withTableName(DROP_TABLE);
    }

    public String initTableSQL() {
        return withTableName(INIT_TABLE);
    }

    /** @return select * from #tableName */
    public String selectAllSQL() {
        return withTableName(SELECT_ALL);
    }

    /** @return select key, c_Bool, c_Int8, ... , from #tableName */
    public String selectSQL() {
        return withTableName(SELECT);
    }

    /** @return select key2 from #tableName */
    public String wrongSelectSQL() {
        return withTableName(WRONG_SELECT);
    }

    /** @return delete from #tableName */
    public String deleteAllSQL() {
        return withTableName(DELETE_ALL);
    }

    public String namedUpsertAll(YqlQuery mode) {
        switch (mode) {
            case BATCHED:
                return withTableName(BATCHED_UPSERT_ALL, tableName);
            case SIMPLE:
            default:
                return withTableName(NAMED_UPSERT_ALL, tableName);

        }
    }

    public String simpleUpsertAllSQL() {
        return withTableName(SIMPLE_UPSERT_ALL, tableName);
    }

    public String indexesUpsertAllSQL() {
        return withTableName(INDEXED_UPSERT_ALL, tableName);
    }

    /**
     * @param column name of column
     * @return select key, #column from #tableName */
    public String selectColumn(String column) {
        return SELECT_COLUMN
                .replaceAll("#column", column)
                .replaceAll("#tableName", tableName);
    }

    public String upsertOne(JdbcQuery query, String column, String type) {
        return JDBC_UPSERT_ONE.get(query)
                .replaceAll("#column", column)
                .replaceAll("#type", type)
                .replaceAll("#tableName", tableName);
    }

    public String upsertOne(YqlQuery query, String column, String type) {
        return YQL_UPSERT_ONE.get(query)
                .replaceAll("#column", column)
                .replaceAll("#type", type)
                .replaceAll("#tableName", tableName);
    }

    private static String withTableName(String query, String tableName) {
        return query.replace("#tableName", tableName);
    }

    public static String selectAllValuesSql(String tableName) {
        return withTableName(SELECT_ALL, tableName);
    }

    public static String upsertAllValuesSql(String tableName) {
        return withTableName(INIT_TABLE, tableName);
    }
}
