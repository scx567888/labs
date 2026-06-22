package dev.scx.sql.sql_server;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.microsoft.sqlserver.jdbc.SQLServerDriver;
import dev.scx.sql.JDBCConnectionInfo;
import dev.scx.sql.dialect.Dialect;
import dev.scx.sql.schema.Column;
import dev.scx.sql.schema.DataTypeKind;
import dev.scx.sql.schema.Index;
import dev.scx.sql.schema.Table;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

/**
 * todo 待完成
 *
 * @author scx567888
 * @version 0.0.1
 */
public class SQLServerDialect implements Dialect {

    private static final SQLServerDriver DRIVER = initDRIVER();

    private static SQLServerDriver initDRIVER() {
        return new SQLServerDriver();
    }

    @Override
    public boolean canHandle(String url) {
        try {
            return DRIVER.acceptsURL(url);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public boolean canHandle(DataSource dataSource) {
        try {
            return dataSource instanceof SQLServerDataSource || dataSource.isWrapperFor(SQLServerDataSource.class);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public DataSource createDataSource(JDBCConnectionInfo connectionInfo) {
        var sqlServerDataSource = new SQLServerDataSource();
        sqlServerDataSource.setURL(connectionInfo.url());
        sqlServerDataSource.setUser(connectionInfo.username());
        sqlServerDataSource.setPassword(connectionInfo.password());
        return sqlServerDataSource;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "[" + identifier + "]";
    }

    @Override
    public DataTypeKind dialectTypeNameToDataTypeKind(String dialectTypeName) {
        return null;
    }

    @Override
    public String dataTypeKindToDialectTypeName(DataTypeKind dataTypeKind) {
        return "";
    }

    @Override
    public List<String> getCreateTableDDLs(Table table) {
        return List.of();
    }

    @Override
    public List<String> getAddColumnDDLs(Table table, Column column) {
        return List.of();
    }

    @Override
    public List<String> getDropColumnDDLs(Table table, Column column) {
        return List.of();
    }

    @Override
    public List<String> getAddIndexDDLs(Table table, Index index) {
        return List.of();
    }

    @Override
    public List<String> getDropIndexDDLs(Table table, Index index) {
        return List.of();
    }

}
